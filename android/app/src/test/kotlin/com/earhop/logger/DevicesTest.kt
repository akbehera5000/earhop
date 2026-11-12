package com.earhop.logger

import org.junit.Assert.assertEquals
import org.junit.Test

/** Device kinds guessed from names and the names shown, the same rules as Devices.swift on the Mac. */
class DevicesTest {
    @Test fun kindGuesses() {
        assertEquals(DeviceKind.PHONE, DeviceKind.guess("phone2"))
        assertEquals(DeviceKind.TABLET, DeviceKind.guess("tab"))
        assertEquals(DeviceKind.WINDOWS, DeviceKind.guess("work-pc"))
        assertEquals(DeviceKind.IPHONE, DeviceKind.guess("iphone"))
        assertEquals(DeviceKind.MAC, DeviceKind.guess("mac-mini"))
        assertEquals(DeviceKind.OTHER, DeviceKind.guess("kitchen"))
    }

    @Test fun kindIdsMatchTheMac() {
        assertEquals(listOf("mac", "phone", "tablet", "iphone", "ipad", "windows", "linux", "other"), DeviceKind.values().map { it.id })
        assertEquals(DeviceKind.WINDOWS, DeviceKind.of("windows"))
        assertEquals(null, DeviceKind.of("toaster"))
    }

    @Test fun prettyNames() {
        assertEquals("iPhone-2", DeviceBook.pretty("iphone-2"))
        assertEquals("Tablet", DeviceBook.pretty("tab"))
        assertEquals("Work-pc", DeviceBook.pretty("work-pc"))
    }
}
