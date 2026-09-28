package com.adnote.ui

import android.app.Application
import android.content.Context
import com.adnote.ink.ClipContent
import com.adnote.model.StylusButtonAction
import com.adnote.model.ToolState
import com.adnote.recognition.MlKitRecognizer
import com.adnote.recognition.Recognizer
import com.adnote.storage.NoteRepository
import com.adnote.sync.SyncEngine
import com.adnote.sync.SyncSettings
import com.adnote.sync.WebDavClient

class AdNoteApp : Application() {

    lateinit var repository: NoteRepository
        private set

    lateinit var recognizer: Recognizer
        private set

    var syncSettings: SyncSettings = SyncSettings()
        private set

    var syncEngine: SyncEngine? = null
        private set

    var stylusOnly: Boolean = false
        private set

    var preferOnyx: Boolean = true
        private set

    /** 音量键翻页（墨水屏阅读器常用的实体翻页方式）。 */
    var volumeKeyPaging: Boolean = true
        private set

    /** 手指左右滑动翻页（手指不书写时生效：防误触模式或文石设备）。 */
    var fingerSwipePaging: Boolean = true
        private set

    /** 翻页后自动识别离开页面的手写（需已下载离线模型）。 */
    var autoRecognize: Boolean = true
        private set

    /** 在笔迹上来回涂抹即删除。 */
    var scratchOut: Boolean = true
        private set

    /** 画完停住半秒，自动规整成直线、矩形、圆等形状。 */
    var shapeHold: Boolean = true
        private set

    /** 笔身按键的作用（标准触控通道）。 */
    var stylusButtonAction: StylusButtonAction = StylusButtonAction.ERASER
        private set

    /** 墨水屏每翻多少页自动全刷一次；0 表示不自动全刷。 */
    var fullRefreshEvery: Int = 6
        private set

    /** 套索复制/剪切的内容，可跨页、跨笔记粘贴。 */
    var clipboard: ClipContent? = null

    /** 编辑器上次使用的工具、笔型、颜色与粗细。 */
    var toolState: ToolState = ToolState()
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        repository = NoteRepository(filesDir)
        recognizer = MlKitRecognizer()
        loadSettings()
    }

    fun loadSettings() {
        val prefs = getSharedPreferences("adnote_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("sync_settings", null)
        syncSettings = SyncSettings.fromJson(json)
        stylusOnly = prefs.getBoolean("stylus_only", false)
        preferOnyx = prefs.getBoolean("prefer_onyx", true)
        volumeKeyPaging = prefs.getBoolean("volume_key_paging", true)
        fingerSwipePaging = prefs.getBoolean("finger_swipe_paging", true)
        toolState = ToolState.fromJson(prefs.getString("tool_state", null))
        autoRecognize = prefs.getBoolean("auto_recognize", true)
        scratchOut = prefs.getBoolean("scratch_out", true)
        shapeHold = prefs.getBoolean("shape_hold", true)
        stylusButtonAction = runCatching {
            StylusButtonAction.valueOf(prefs.getString("stylus_button", null) ?: "ERASER")
        }.getOrDefault(StylusButtonAction.ERASER)
        fullRefreshEvery = prefs.getInt("full_refresh_every", 6)

        syncEngine = if (syncSettings.isConfigured) {
            val client = WebDavClient(
                baseUrl = syncSettings.serverUrl,
                username = syncSettings.username,
                password = syncSettings.password,
                remoteRootDir = syncSettings.remoteRootDir
            )
            SyncEngine(repository, client)
        } else null
    }

    fun setStylusOnly(enabled: Boolean) {
        stylusOnly = enabled
        getSharedPreferences("adnote_settings", Context.MODE_PRIVATE).edit().putBoolean("stylus_only", enabled).apply()
    }

    fun setPreferOnyx(enabled: Boolean) {
        preferOnyx = enabled
        getSharedPreferences("adnote_settings", Context.MODE_PRIVATE).edit().putBoolean("prefer_onyx", enabled).apply()
    }

    fun setVolumeKeyPaging(enabled: Boolean) {
        volumeKeyPaging = enabled
        getSharedPreferences("adnote_settings", Context.MODE_PRIVATE).edit().putBoolean("volume_key_paging", enabled).apply()
    }

    fun setFingerSwipePaging(enabled: Boolean) {
        fingerSwipePaging = enabled
        getSharedPreferences("adnote_settings", Context.MODE_PRIVATE).edit().putBoolean("finger_swipe_paging", enabled).apply()
    }

    private fun prefs() = getSharedPreferences("adnote_settings", Context.MODE_PRIVATE)

    fun setAutoRecognize(v: Boolean) { autoRecognize = v; prefs().edit().putBoolean("auto_recognize", v).apply() }

    fun setScratchOut(v: Boolean) { scratchOut = v; prefs().edit().putBoolean("scratch_out", v).apply() }

    fun setShapeHold(v: Boolean) { shapeHold = v; prefs().edit().putBoolean("shape_hold", v).apply() }

    fun setStylusButtonAction(v: StylusButtonAction) {
        stylusButtonAction = v
        prefs().edit().putString("stylus_button", v.name).apply()
    }

    fun setFullRefreshEvery(v: Int) { fullRefreshEvery = v; prefs().edit().putInt("full_refresh_every", v).apply() }

    fun saveToolState(state: ToolState) {
        toolState = state
        getSharedPreferences("adnote_settings", Context.MODE_PRIVATE).edit().putString("tool_state", ToolState.toJson(state)).apply()
    }

    fun updateSettings(newSettings: SyncSettings) {
        val prefs = getSharedPreferences("adnote_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("sync_settings", SyncSettings.toJson(newSettings)).apply()
        loadSettings()
    }

    companion object {
        lateinit var instance: AdNoteApp
            private set
    }
}
