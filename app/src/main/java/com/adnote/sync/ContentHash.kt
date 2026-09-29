package com.adnote.sync

import java.security.MessageDigest

object ContentHash {
    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
