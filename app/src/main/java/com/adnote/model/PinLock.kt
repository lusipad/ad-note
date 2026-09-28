package com.adnote.model

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 笔记 PIN 锁：保存 "盐:SHA-256(盐 + PIN)"。
 * 这是打开笔记时的访问限制，笔记文件与同步内容本身并不加密。
 */
object PinLock {

    fun hash(pin: String, salt: String = newSalt()): String = "$salt:${sha256(salt + pin)}"

    fun verify(pin: String, stored: String?): Boolean {
        if (stored.isNullOrEmpty()) return true
        val salt = stored.substringBefore(':', "")
        if (salt.isEmpty()) return false
        return MessageDigest.isEqual(hash(pin, salt).toByteArray(), stored.toByteArray())
    }

    fun isValidPin(pin: String): Boolean = pin.length in 4..12 && pin.all { it.isDigit() }

    private fun newSalt(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
