package com.earhop.logger

import android.content.Context
import org.json.JSONObject

/**
 * What kind of device a name belongs to, for icons and words (same kinds as DeviceKind in Devices.swift). Devices say
 * what they are with a "hello" control message; older builds that never say get a guess from their name, and a kind
 * picked by hand in Devices wins over both.
 */
enum class DeviceKind(val id: String, val title: String, val icon: Int) {
    MAC("mac", "Mac", R.drawable.ic_dev_laptop),
    PHONE("phone", "Android phone", R.drawable.ic_dev_phone),
    TABLET("tablet", "Android tablet", R.drawable.ic_dev_tablet),
    IPHONE("iphone", "iPhone", R.drawable.ic_dev_phone),
    IPAD("ipad", "iPad", R.drawable.ic_dev_tablet),
    WINDOWS("windows", "Windows PC", R.drawable.ic_dev_windows),
    LINUX("linux", "Linux computer", R.drawable.ic_dev_desktop),
    OTHER("other", "Other device", R.drawable.ic_dev_desktop);

    companion object {
        fun of(id: String?): DeviceKind? = values().firstOrNull { it.id == id }

        /** A guess from the device name, for devices that have not said what they are. */
        fun guess(name: String): DeviceKind {
            val n = name.lowercase()
            return when {
                n.startsWith("mac") -> MAC
                n.startsWith("iphone") -> IPHONE
                n.startsWith("ipad") -> IPAD
                n.startsWith("tab") -> TABLET
                n.startsWith("phone") || n.startsWith("android") || n.startsWith("pixel") || n.startsWith("galaxy") -> PHONE
                n.startsWith("pc") || n.startsWith("win") || n.endsWith("-pc") || n.startsWith("laptop") -> WINDOWS
                n.startsWith("linux") || n.startsWith("ubuntu") || n.startsWith("fedora") -> LINUX
                else -> OTHER
            }
        }
    }
}

/**
 * Every device this one knows: names learned from the network (NameBook), their announced kinds and the kinds picked
 * by hand, kept in the "earhop" preferences. Pure apart from the preferences, so it works for any number of devices.
 */
object DeviceBook {
    private fun p(ctx: Context) = ctx.getSharedPreferences("earhop", Context.MODE_PRIVATE)

    private fun map(ctx: Context, key: String): Map<String, String> = try {
        val o = JSONObject(p(ctx).getString(key, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }

    private fun putMap(ctx: Context, key: String, m: Map<String, String>) {
        p(ctx).edit().putString(key, JSONObject(m).toString()).apply()
    }

    fun kind(ctx: Context, name: String): DeviceKind =
        DeviceKind.of(map(ctx, "deviceKindOverrides")[name]) ?: DeviceKind.of(map(ctx, "deviceKinds")[name]) ?: DeviceKind.guess(name)

    fun announced(ctx: Context, name: String): DeviceKind? = DeviceKind.of(map(ctx, "deviceKinds")[name])
    fun override(ctx: Context, name: String): DeviceKind? = DeviceKind.of(map(ctx, "deviceKindOverrides")[name])

    /** Saves what a device said it is; true when it changed. */
    fun setAnnounced(ctx: Context, name: String, kind: DeviceKind): Boolean {
        val m = map(ctx, "deviceKinds").toMutableMap()
        if (m[name] == kind.id) return false
        m[name] = kind.id
        putMap(ctx, "deviceKinds", m)
        return true
    }

    /** A kind picked by hand, or null to go back to automatic. */
    fun setOverride(ctx: Context, name: String, kind: DeviceKind?) {
        val m = map(ctx, "deviceKindOverrides").toMutableMap()
        if (kind == null) m.remove(name) else m[name] = kind.id
        putMap(ctx, "deviceKindOverrides", m)
    }

    /** This device's own kind: a tablet by its screen, else a phone. */
    fun selfKind(ctx: Context): DeviceKind =
        if (ctx.resources.configuration.smallestScreenWidthDp >= 600) DeviceKind.TABLET else DeviceKind.PHONE

    /** Every other device known here, in priority order, then by name. */
    fun all(ctx: Context): List<String> {
        val self = p(ctx).getString("name", "phone") ?: "phone"
        val known = (RuleStore.priority(ctx) + NameBook.all + (p(ctx).getStringSet("knownDevices", emptySet()) ?: emptySet()) +
            EhState.peers.keys).filter { it != self && DeviceNames.valid(it) }.distinct()
        val ranked = RuleStore.priority(ctx).filter { it in known }
        return ranked + known.filter { it !in ranked }.sorted()
    }

    /** Forgets a device learned earlier. mac, phone and tab always stay (the defaults). */
    fun forget(ctx: Context, name: String) {
        if (name in setOf("mac", "phone", "tab")) return
        NameBook.remove(name)
        val known = (p(ctx).getStringSet("knownDevices", emptySet()) ?: emptySet()) - name
        p(ctx).edit().putStringSet("knownDevices", known).apply()
        RuleStore.setPriority(ctx, RuleStore.priority(ctx).filter { it != name })
        putMap(ctx, "deviceKinds", map(ctx, "deviceKinds") - name)
        putMap(ctx, "deviceKindOverrides", map(ctx, "deviceKindOverrides") - name)
        val ex = ClipSync.excluded(ctx) - name
        p(ctx).edit().putStringSet("clipExclude", ex).apply()
    }

    /** "phone" to "Phone", "iphone-2" to "iPhone-2", "tab" to "Tablet". */
    fun pretty(n: String): String = when {
        n == "mac" -> "Mac"
        n == "phone" -> "Phone"
        n == "tab" -> "Tablet"
        n.startsWith("iphone") -> "iPhone" + n.removePrefix("iphone")
        n.startsWith("ipad") -> "iPad" + n.removePrefix("ipad")
        else -> n.replaceFirstChar { it.uppercase() }
    }
}

/** The "hello" control message: `{"type","app","version"}`, what this device is (Hello in Devices.swift). */
object Hello {
    fun encode(kind: DeviceKind, app: String, version: String): ByteArray =
        JSONObject().put("app", app).put("type", kind.id).put("version", version).toString().toByteArray()

    fun decode(d: ByteArray): DeviceKind? = try {
        DeviceKind.of(JSONObject(String(d, Charsets.UTF_8)).optString("type"))
    } catch (e: Exception) { null }
}
