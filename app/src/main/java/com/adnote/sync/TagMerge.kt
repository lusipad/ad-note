package com.adnote.sync

/**
 * 标签三方合并。base 是上次同步后双方一致的标签：
 * 任一方从 base 里删掉的标签不再保留，任一方新增的标签都保留。
 * 顺序：base 中保留的 → 本地新增 → 远端新增。
 */
object TagMerge {
    fun merge(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        val baseSet = base.toSet()
        val removedLocal = baseSet - local.toSet()
        val removedRemote = baseSet - remote.toSet()
        val result = LinkedHashSet<String>()
        base.filter { it !in removedLocal && it !in removedRemote }.forEach { result += it }
        local.filter { it !in baseSet }.forEach { result += it }
        remote.filter { it !in baseSet }.forEach { result += it }
        return result.toList()
    }
}
