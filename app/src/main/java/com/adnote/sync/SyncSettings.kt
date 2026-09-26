package com.adnote.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SyncSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val remoteRootDir: String = "AdNote",
    val enabled: Boolean = false,
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(str: String?): SyncSettings =
            if (str.isNullOrBlank()) SyncSettings() else runCatching { json.decodeFromString<SyncSettings>(str) }.getOrDefault(SyncSettings())

        fun toJson(settings: SyncSettings): String =
            json.encodeToString(SyncSettings.serializer(), settings)
    }
}
