package com.adnote.recognition

import com.adnote.model.Page

interface Recognizer {
    /** 对单页笔记的手写笔画进行手写识别。若无笔画返回空串。 */
    suspend fun recognize(page: Page): Result<String>

    /** 检查识别模型是否已下载并可用。 */
    suspend fun isModelDownloaded(): Boolean

    /** 手动触发下载识别模型。 */
    suspend fun downloadModel(): Result<Unit>
}
