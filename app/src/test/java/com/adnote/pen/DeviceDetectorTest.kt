package com.adnote.pen

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceDetectorTest {

    @Test
    fun testDetectXiaomiPad() {
        val info = DeviceDetector.detect(
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            model = "23049PCD8G", // Xiaomi Pad 6
            device = "pipa"
        )
        assertEquals(DeviceBrand.XIAOMI, info.brand)
        assertEquals(ScreenCategory.COLOR_SCREEN, info.screenCategory)
    }

    @Test
    fun testDetectVivoPad() {
        val info = DeviceDetector.detect(
            manufacturer = "vivo",
            brand = "vivo",
            model = "PA2170", // vivo Pad
            device = "PA2170"
        )
        assertEquals(DeviceBrand.VIVO, info.brand)
        assertEquals(ScreenCategory.COLOR_SCREEN, info.screenCategory)
    }

    @Test
    fun testDetectIReader() {
        val info = DeviceDetector.detect(
            manufacturer = "iReader",
            brand = "iReader",
            model = "Smart 4",
            device = "Smart4"
        )
        assertEquals(DeviceBrand.IREADER, info.brand)
        assertEquals(ScreenCategory.EINK, info.screenCategory)
    }

    @Test
    fun testDetectOnyxBoox() {
        val info = DeviceDetector.detect(
            manufacturer = "Onyx",
            brand = "BOOX",
            model = "NoteAir3",
            device = "NoteAir3"
        )
        assertEquals(DeviceBrand.ONYX, info.brand)
        assertEquals(ScreenCategory.EINK, info.screenCategory)
    }

    @Test
    fun testDetectHanvon() {
        val info = DeviceDetector.detect(
            manufacturer = "Hanvon",
            brand = "Hanvon",
            model = "N10",
            device = "N10"
        )
        assertEquals(DeviceBrand.HANVON, info.brand)
        assertEquals(ScreenCategory.EINK, info.screenCategory)
    }

    @Test
    fun testDetectMoanEink() {
        val info = DeviceDetector.detect(
            manufacturer = "Moan",
            brand = "inkPalm",
            model = "inkPalm5",
            device = "inkPalm5"
        )
        assertEquals(DeviceBrand.OTHER, info.brand)
        assertEquals(ScreenCategory.EINK, info.screenCategory)
    }
}
