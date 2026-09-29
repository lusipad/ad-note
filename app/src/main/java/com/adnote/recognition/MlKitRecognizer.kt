package com.adnote.recognition

import com.adnote.ink.LineGrouper
import com.adnote.model.Page
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitRecognizer(
    private val languageTag: String = "zh-Hani-CN",
) : Recognizer {

    private val modelIdentifier: DigitalInkRecognitionModelIdentifier? by lazy {
        runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag) }.getOrNull()
    }

    private val model: DigitalInkRecognitionModel? by lazy {
        modelIdentifier?.let { DigitalInkRecognitionModel.builder(it).build() }
    }

    private val remoteModelManager: RemoteModelManager by lazy {
        RemoteModelManager.getInstance()
    }

    @Volatile private var client: DigitalInkRecognizer? = null

    private val recognizer: DigitalInkRecognizer? by lazy {
        model?.let {
            DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(it).build())
        }.also { client = it }
    }

    /** 释放 ML Kit 识别器；只有已经创建过才会关闭。 */
    fun close() {
        client?.close()
        client = null
    }

    override suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        val targetModel = model ?: return@withContext false
        runCatching {
            remoteModelManager.isModelDownloaded(targetModel).await()
        }.getOrDefault(false)
    }

    override suspend fun downloadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        val targetModel = model ?: return@withContext Result.failure(
            IllegalStateException("无法找到语言模型标识符: $languageTag")
        )
        runCatching {
            val conditions = DownloadConditions.Builder().build()
            remoteModelManager.download(targetModel, conditions).await()
            Unit
        }.recoverCatching { err ->
            throw IllegalStateException(
                "手写识别模型下载失败（可能需要能访问 Google 服务的网络环境）：${err.message}",
                err
            )
        }
    }

    override suspend fun recognize(page: Page): Result<String> = withContext(Dispatchers.Default) {
        if (page.strokes.isEmpty()) {
            return@withContext Result.success("")
        }

        // 先分行：只有荧光笔的页面没有可识别内容，不必碰模型和网络
        val lines = LineGrouper.group(page.strokes)
        if (lines.isEmpty()) return@withContext Result.success("")

        val inkClient = recognizer ?: return@withContext Result.failure(
            IllegalStateException("ML Kit 识别器初始化失败")
        )

        val downloaded = isModelDownloaded()
        if (!downloaded) {
            val dlResult = downloadModel()
            if (dlResult.isFailure) {
                return@withContext Result.failure(
                    dlResult.exceptionOrNull() ?: IllegalStateException("手写模型未下载")
                )
            }
        }

        runCatching {
            val texts = ArrayList<String>(lines.size)
            for (line in lines) {
                val inkBuilder = Ink.builder()
                for (stroke in line) {
                    val strokeBuilder = Ink.Stroke.builder()
                    for (pt in stroke.points) {
                        strokeBuilder.addPoint(Ink.Point.create(pt.x, pt.y, pt.t))
                    }
                    inkBuilder.addStroke(strokeBuilder.build())
                }
                val text = inkClient.recognize(inkBuilder.build()).await().candidates.firstOrNull()?.text.orEmpty().trim()
                if (text.isNotEmpty()) texts += text
            }
            texts.joinToString("\n")
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { exception ->
        if (cont.isActive) cont.resumeWithException(exception)
    }
}
