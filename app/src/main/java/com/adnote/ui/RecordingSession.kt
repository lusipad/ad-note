package com.adnote.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.adnote.R
import com.adnote.model.Recording

/**
 * 进程级的录音会话。录音不再跟着编辑器走：熄屏、切到别的应用、换一篇笔记都继续录，
 * 由前台服务 [RecordingService] 保活（通知栏里可以看到并停止）。
 *
 * 停止后录音记到开始录音的那篇笔记上：那篇笔记正开在编辑器里时交给编辑器写进内存中的笔记，
 * 否则直接写进存储（编辑器内存里的旧笔记不会再把它覆盖掉）。
 * 只在主线程调用。
 */
object RecordingSession {

    data class Active(val noteId: String, val noteTitle: String, val path: String, val pageId: String?)

    private var recorder: AudioRecorder? = null

    var active: Active? = null
        private set

    val elapsedMs: Long get() = recorder?.elapsedMs ?: 0L

    private val listeners = ArrayList<Pair<String, (Recording) -> Unit>>()

    /** 编辑器打开某篇笔记时注册，录音结束且属于这篇笔记时由它接收。 */
    fun addListener(noteId: String, listener: (Recording) -> Unit) {
        listeners += noteId to listener
    }

    fun removeListener(listener: (Recording) -> Unit) {
        listeners.removeAll { it.second === listener }
    }

    /** 开始录音；失败时抛出异常（麦克风被占用等）。 */
    fun start(context: Context, noteId: String, noteTitle: String, pageId: String?) {
        check(active == null) { "已经在录音" }
        val app = context.applicationContext
        val repo = AdNoteApp.instance.repository
        val path = repo.newAssetPath("audio", "m4a")
        val r = AudioRecorder(app)
        r.start(repo.assetFile(noteId, path))
        recorder = r
        active = Active(noteId, noteTitle, path, pageId)
        // 前台服务起不来（极少见）也不影响录音本身，只是熄屏后可能被系统停掉
        runCatching { app.startForegroundService(Intent(app, RecordingService::class.java)) }
    }

    /**
     * 停止录音并把它记到笔记上。返回保存下来的录音，太短被丢弃时返回 null。
     */
    fun stop(context: Context): Pair<Active, Recording?>? {
        val a = active ?: return null
        val duration = recorder?.stop() ?: 0L
        recorder = null
        active = null
        val app = context.applicationContext
        runCatching { app.stopService(Intent(app, RecordingService::class.java)) }

        val repo = AdNoteApp.instance.repository
        if (duration < MIN_DURATION_MS) {
            repo.assetFile(a.noteId, a.path).delete()
            return a to null
        }
        val rec = Recording(path = a.path, createdAt = System.currentTimeMillis(), durationMs = duration, pageId = a.pageId)
        val listener = listeners.lastOrNull { it.first == a.noteId }?.second
        if (listener != null) {
            listener(rec)
        } else {
            repo.load(a.noteId)?.let { n ->
                repo.saveAsync(n.copy(recordings = n.recordings + rec, updatedAt = System.currentTimeMillis()))
            }
        }
        return a to rec
    }

    private const val MIN_DURATION_MS = 800L
}

/** 录音期间的前台服务：让熄屏、切到后台时系统不停掉录音，并在通知栏提供「停止」。 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RecordingSession.stop(this)?.let { (a, rec) ->
                val msg = if (rec == null) "录音太短，已丢弃" else "录音已保存到「${a.noteTitle}」"
                android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val active = RecordingSession.active
        if (active == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification(active)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(active: RecordingSession.Active): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_LOW).apply {
                description = "录音进行中的提示，可在这里停止录音"
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, EditorActivity.intent(this, active.noteId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("正在录音")
            .setContentText("「${active.noteTitle}」 · 熄屏后继续录制")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止录音", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.adnote.action.STOP_RECORDING"
    }
}
