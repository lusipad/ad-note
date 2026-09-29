package com.adnote.sync

/** 自动同步的触发条件。不依赖 Android，便于测试。 */
class SyncPolicy(private val minIntervalMs: Long = 60_000L) {
    fun shouldRun(enabled: Boolean, configured: Boolean, online: Boolean, hasDirty: Boolean, lastRunAt: Long, now: Long): Boolean =
        enabled && configured && online && hasDirty && now - lastRunAt >= minIntervalMs
}
