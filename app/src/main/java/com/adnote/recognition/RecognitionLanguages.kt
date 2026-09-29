package com.adnote.recognition

data class RecognitionLanguage(val tag: String, val displayName: String)

/** 可选的 ML Kit 手写识别语言。标签已对照 ML Kit 18.1.0 的 DigitalInkRecognitionModelIdentifier 核对（日语是 ja，不是 ja-JP）。 */
object RecognitionLanguages {
    const val DEFAULT = "zh-Hani-CN"

    val ALL = listOf(
        RecognitionLanguage("zh-Hani-CN", "中文（简体）"),
        RecognitionLanguage("zh-Hani-TW", "中文（繁体）"),
        RecognitionLanguage("en-US", "英语"),
        RecognitionLanguage("ja", "日语"),
    )

    fun byTag(tag: String?): RecognitionLanguage = ALL.firstOrNull { it.tag == tag } ?: ALL[0]
}
