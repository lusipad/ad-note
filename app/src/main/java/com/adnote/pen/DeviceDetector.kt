package com.adnote.pen

import android.os.Build

enum class DeviceBrand(val displayName: String) {
    ONYX("文石 (BOOX) / 得到阅读器"),
    IREADER("掌阅 (iReader)"),
    XIAOMI("小米 (Xiaomi / Redmi)"),
    VIVO("vivo / iQOO"),
    HUAWEI("华为 (Huawei / Honor)"),
    HANVON("汉王 (Hanvon)"),
    OTHER("通用 Android 设备"),
}

enum class ScreenCategory(val displayName: String) {
    EINK("墨水屏 (E-ink)"),
    COLOR_SCREEN("普通彩屏 (LCD/OLED)"),
}

data class DeviceInfo(
    val brand: DeviceBrand,
    val manufacturer: String,
    val model: String,
    val screenCategory: ScreenCategory,
) {
    val summary: String
        get() = "${brand.displayName} · $model (${screenCategory.displayName})"
}

object DeviceDetector {

    fun detect(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty(),
        model: String = Build.MODEL.orEmpty(),
        device: String = Build.DEVICE.orEmpty(),
    ): DeviceInfo {
        val fields = listOf(
            manufacturer.lowercase(),
            brand.lowercase(),
            model.lowercase(),
            device.lowercase()
        )

        val detectedBrand = when {
            containsAny(fields, "onyx", "boox", "dedao", "getread") -> DeviceBrand.ONYX
            containsAny(fields, "ireader", "zhangyue") -> DeviceBrand.IREADER
            containsAny(fields, "xiaomi", "redmi") -> DeviceBrand.XIAOMI
            containsAny(fields, "vivo", "iqoo") -> DeviceBrand.VIVO
            containsAny(fields, "huawei", "honor") -> DeviceBrand.HUAWEI
            containsAny(fields, "hanvon") -> DeviceBrand.HANVON
            else -> DeviceBrand.OTHER
        }

        val isEink = when (detectedBrand) {
            DeviceBrand.ONYX, DeviceBrand.IREADER, DeviceBrand.HANVON -> true
            else -> containsAny(fields, "eink", "epd", "moan", "ink", "bigme", "hyread", "hisense")
        }

        val screenCategory = if (isEink) ScreenCategory.EINK else ScreenCategory.COLOR_SCREEN

        return DeviceInfo(
            brand = detectedBrand,
            manufacturer = manufacturer,
            model = model,
            screenCategory = screenCategory,
        )
    }

    private fun containsAny(haystacks: List<String>, vararg needles: String): Boolean {
        for (h in haystacks) {
            for (n in needles) {
                if (h.contains(n)) return true
            }
        }
        return false
    }
}
