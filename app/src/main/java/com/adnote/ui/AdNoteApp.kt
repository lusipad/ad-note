package com.adnote.ui

import android.app.Application
import android.content.Context
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
