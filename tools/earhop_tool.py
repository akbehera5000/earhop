#!/usr/bin/env python3
"""EarHop protocol tool: selftest, listen, send. Standard library only."""
import argparse, hashlib, hmac, json, socket, time

PORT = 47474
VECTOR_SECRET = "test-secret"
VECTOR_CANON = "1|mac|1700000000000|7|1|both|0"
VECTOR_HMAC = "389cb0b6b95b236ec9f8ee2a76263d87cf0f876d590b14c976960cbe424b4c00"
VECTOR2_CANON = "2|mac|1700000000000|7|1|both|0|1"
VECTOR3_CANON = "3|mac|1700000000000|7|1|both|0|1|buds"
VECTOR3_HMAC = "e0b6ceded3c4002c40a606404586e70634245c77a4f5aff28eedb1352866659d"
VECTOR3_BLE = "ea0000077752983dac7ce48782b05515"

# Bluetooth LE fallback payload: 16 bytes carried as one 128-bit service UUID.
# [0]=0xEA magic [1]=dev code [2..3]=seq (big endian) [4]=flags [5..12]=first 8 bytes of
# HMAC-SHA256(secret, bytes 0..4 + 13..15) [13..15]=minutes since 1970, low 24 bits, big endian.
# Flags: bit0 playing, bits1-2 ear (0 unknown 1 none 2 one 3 both), bit3 owner, bit4 call,
# bit5 profile known, bit6 profile is Galaxy Buds (else AirPods).
# The minute stamp is signed, so a recorded advertisement is refused once it is more than 2 minutes old.
DEV_CODES = {"mac": 0, "phone": 1, "tab": 2}
EAR_CODES = {"unknown": 0, "none": 1, "one": 2, "both": 3}


def ble_minute(ms):
    return (ms // 60000) & 0xFFFFFF


PROF_BITS = {"": 0, "airpods": 32, "buds": 96}


def ble_encode(secret, dev, seq, playing, ear, owner, call, minute, prof=""):
    flags = playing | (EAR_CODES[ear] << 1) | (owner << 3) | (call << 4) | PROF_BITS[prof]
    head = bytes([0xEA, DEV_CODES[dev], (seq >> 8) & 0xFF, seq & 0xFF, flags])
    stamp = bytes([(minute >> 16) & 0xFF, (minute >> 8) & 0xFF, minute & 0xFF])
    mac = hmac.new(secret.encode(), head + stamp, hashlib.sha256).digest()[:8]
    return head + mac + stamp


def ble_decode(secret, data, now_minute):
    if len(data) != 16 or data[0] != 0xEA:
        return None
    mac = hmac.new(secret.encode(), data[:5] + data[13:16], hashlib.sha256).digest()[:8]
    if not hmac.compare_digest(mac, data[5:13]):
        return None
    minute = (data[13] << 16) | (data[14] << 8) | data[15]
    d = (now_minute - minute) & 0xFFFFFF
    if min(d, 0x1000000 - d) > 2:
        return None
    names = {v: k for k, v in DEV_CODES.items()}
    ears = {v: k for k, v in EAR_CODES.items()}
    f = data[4]
    return {"dev": names.get(data[1]), "seq": (data[2] << 8) | data[3], "playing": f & 1,
            "ear": ears[(f >> 1) & 3], "owner": (f >> 3) & 1, "call": (f >> 4) & 1,
            "prof": ("buds" if f & 64 else "airpods") if f & 32 else ""}


def canon(dev, ts, seq, playing, ear, owner, call=None, v=None, prof=""):
    """v1 has no call field; v2 appends it; v3 also appends the earbud profile. Default is v2 when call is given."""
    if v is None:
        v = 2 if call is not None else 1
    if v == 1:
        return f"1|{dev}|{ts}|{seq}|{playing}|{ear}|{owner}"
    if v == 2:
        return f"2|{dev}|{ts}|{seq}|{playing}|{ear}|{owner}|{call or 0}"
    return f"3|{dev}|{ts}|{seq}|{playing}|{ear}|{owner}|{call or 0}|{prof}"


def sign(secret, s):
    return hmac.new(secret.encode(), s.encode(), hashlib.sha256).hexdigest()


def encode(secret, dev, seq, playing, ear, owner, ts=None, call=0, prof=""):
    ts = int(time.time() * 1000) if ts is None else ts
    sig = sign(secret, canon(dev, ts, seq, playing, ear, owner, call, v=3, prof=prof))
    return json.dumps({"v": 3, "dev": dev, "ts": ts, "seq": seq, "playing": playing, "ear": ear,
                       "owner": owner, "call": call, "prof": prof, "sig": sig}, separators=(",", ":")).encode()


def decode(secret, data, now_ms=None):
    try:
        m = json.loads(data)
        if m["v"] not in (1, 2, 3):
            return None
        if m["v"] == 1:
            m["call"] = 0
        if m["v"] != 3:
            m["prof"] = ""
        if m["prof"] not in ("", "airpods", "buds"):
            return None
        c = canon(m["dev"], m["ts"], m["seq"], m["playing"], m["ear"], m["owner"], m["call"], v=m["v"], prof=m["prof"])
        if not hmac.compare_digest(sign(secret, c), str(m["sig"])):
            return None
        now = int(time.time() * 1000) if now_ms is None else now_ms
        if abs(now - m["ts"]) > 30000:
            return None
        return m
    except Exception:
        return None


def selftest():
    assert canon("mac", 1700000000000, 7, 1, "both", 0) == VECTOR_CANON
    assert sign(VECTOR_SECRET, VECTOR_CANON) == VECTOR_HMAC
    assert canon("mac", 1700000000000, 7, 1, "both", 0, 1) == VECTOR2_CANON
    assert canon("mac", 1700000000000, 7, 1, "both", 0, 1, v=3, prof="buds") == VECTOR3_CANON
    assert sign(VECTOR_SECRET, VECTOR3_CANON) == VECTOR3_HMAC
    p = encode("s", "tab", 1, 0, "none", 1, call=1, prof="buds")
    assert decode("s", p)["dev"] == "tab" and decode("s", p)["call"] == 1 and decode("s", p)["prof"] == "buds"
    m = ble_minute(1700000000000)
    b = ble_encode("test-secret", "mac", 7, 1, "both", 0, 1, m)
    assert ble_decode("test-secret", b, m)["call"] == 1 and ble_decode("x", b, m) is None
    assert ble_decode("test-secret", b, m + 2) is not None and ble_decode("test-secret", b, m + 3) is None
    assert ble_decode("test-secret", b, m)["prof"] == ""
    b3 = ble_encode("test-secret", "mac", 7, 1, "both", 0, 1, m, prof="buds")
    assert ble_decode("test-secret", b3, m)["prof"] == "buds" and b3.hex() == VECTOR3_BLE
    print("v2 canonical:", VECTOR2_CANON, sign(VECTOR_SECRET, VECTOR2_CANON))
    print("ble vector:", b.hex())
    assert decode("wrong", p) is None
    assert decode("s", encode("s", "tab", 1, 0, "none", 1, ts=1)) is None
    print("selftest OK")


def listen(secret, port):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_REUSEPORT"):
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    s.bind(("", port))
    print(f"listening on UDP {port}")
    while True:
        data, addr = s.recvfrom(2048)
        m = decode(secret, data)
        print(addr[0], m if m else "REJECTED")


def send(secret, dev, playing, ear, owner, host, port):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.sendto(encode(secret, dev, int(time.time()) % 100000, playing, ear, owner), (host, port))
    print("sent")


if __name__ == "__main__":
    a = argparse.ArgumentParser()
    sub = a.add_subparsers(dest="cmd", required=True)
    sub.add_parser("selftest")
    l = sub.add_parser("listen"); l.add_argument("--secret", required=True); l.add_argument("--port", type=int, default=PORT)
    t = sub.add_parser("send"); t.add_argument("--secret", required=True); t.add_argument("--dev", default="pytool")
    t.add_argument("--playing", type=int, default=0); t.add_argument("--ear", default="none"); t.add_argument("--owner", type=int, default=0)
    t.add_argument("--host", default="255.255.255.255"); t.add_argument("--port", type=int, default=PORT)
    r = a.parse_args()
    if r.cmd == "selftest": selftest()
    elif r.cmd == "listen": listen(r.secret, r.port)
    else: send(r.secret, r.dev, r.playing, r.ear, r.owner, r.host, r.port)
