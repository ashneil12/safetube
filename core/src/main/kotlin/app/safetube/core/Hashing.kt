package app.safetube.core

import java.security.MessageDigest
import java.security.SecureRandom

object Hashing {
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun pinHash(pin: String, salt: String): String = sha256("$salt:$pin")
}
