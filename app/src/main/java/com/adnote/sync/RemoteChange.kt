package com.adnote.sync

/**
 * 判断远端 md 自上次同步后是否被改过。
 * 两边都有 ETag 时以 ETag 为准；否则比较内容哈希；本地什么都没记录（首次连接）视为改过，
 * 让调用方走合并流程而不是直接覆盖。
 */
object RemoteChange {
    fun detect(localEtag: String?, localHash: String?, remoteEtag: String?, remoteBody: String): Boolean {
        if (localEtag != null && remoteEtag != null) return localEtag != remoteEtag
        if (localHash != null) return ContentHash.sha256(remoteBody) != localHash
        return true
    }
}
