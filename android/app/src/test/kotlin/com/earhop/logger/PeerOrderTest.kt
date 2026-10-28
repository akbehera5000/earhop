package com.earhop.logger

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerOrderTest {
    @Test fun newerWifiCopyIsTaken() = assertEquals(Pair(true, 11), PeerOrder.accept(10, 11, ble = false))
    @Test fun duplicateIsNotTaken() = assertEquals(false, PeerOrder.accept(10, 10, ble = false).first)
    @Test fun lateCopyIsNotTaken() = assertEquals(false, PeerOrder.accept(10, 8, ble = false).first)
    @Test fun bluetoothCopyIsWidened() = assertEquals(Pair(true, 0x10005), PeerOrder.accept(0x10003, 5, ble = true))
    @Test fun olderBluetoothCopyAfterWifiIsNotTaken() = assertEquals(false, PeerOrder.accept(0x10003, 2, ble = true).first)
    @Test fun bluetoothAcrossWrap() = assertEquals(Pair(true, 0x20001), PeerOrder.accept(0x1FFFF, 1, ble = true))
    @Test fun restartedDeviceIsTaken() = assertEquals(Pair(true, 3), PeerOrder.accept(5000, 3, ble = false))
    @Test fun firstCopyIsTaken() = assertEquals(Pair(true, 42), PeerOrder.accept(null, 42, ble = true))
}
