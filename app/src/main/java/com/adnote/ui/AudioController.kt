package com.adnote.ui

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** 录音（AAC / m4a）。 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null
    val elapsedMs: Long get() = if (isRecording) SystemClock.elapsedRealtime() - startedAt else 0L

    fun start(file: File) {
        file.parentFile?.mkdirs()
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(file.path)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
        startedAt = SystemClock.elapsedRealtime()
    }

    /** 停止并返回录音时长（毫秒）。 */
    fun stop(): Long {
        val r = recorder ?: return 0L
        val duration = elapsedMs
        runCatching { r.stop() }
        r.release()
        recorder = null
        return duration
    }
}

/** 录音回放，同一时间只播放一段。 */
class AudioPlayer {

    private var player: MediaPlayer? = null
    var playingPath: String? = null
        private set

    fun play(file: File, key: String, onDone: () -> Unit) {
        stop()
        val p = MediaPlayer()
        p.setDataSource(file.path)
        p.setOnCompletionListener {
            stop()
            onDone()
        }
        p.prepare()
        p.start()
        player = p
        playingPath = key
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playingPath = null
    }
}
