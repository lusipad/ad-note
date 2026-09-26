package com.adnote.ui

import android.app.Application
import android.content.Context
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
