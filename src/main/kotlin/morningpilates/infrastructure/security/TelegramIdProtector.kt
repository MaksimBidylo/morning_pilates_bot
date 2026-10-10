package morningpilates.infrastructure.security

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TelegramIdProtector(
    encryptionKey: ByteArray,
    lookupKey: ByteArray,
) {
    companion object {
        private const val AES_KEY_SIZE_BYTES = 32
        private const val HMAC_KEY_SIZE_BYTES = 32
        private const val NONCE_SIZE_BYTES = 12
        private const val TAG_SIZE_BYTES = 16
        private const val TAG_SIZE_BITS = TAG_SIZE_BYTES * Byte.SIZE_BITS
        private const val ENCRYPTED_ID_SIZE_BYTES = NONCE_SIZE_BYTES + Long.SIZE_BYTES + TAG_SIZE_BYTES
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }

    init {
        require(encryptionKey.size == AES_KEY_SIZE_BYTES) { "AES-256 requires a 32-byte encryption key" }
        require(lookupKey.size == HMAC_KEY_SIZE_BYTES) { "HMAC-SHA256 lookup requires a 32-byte key" }
        require(!encryptionKey.contentEquals(lookupKey)) { "Encryption and lookup keys must have different contents" }
    }

    private val aesKey = SecretKeySpec(encryptionKey, "AES")
    private val hmacKey = SecretKeySpec(lookupKey, HMAC_ALGORITHM)
    private val random = SecureRandom()

    fun encrypt(telegramId: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE_BYTES).also(random::nextBytes)
        val cipher = createCipher(Cipher.ENCRYPT_MODE, nonce)

        return nonce + cipher.doFinal(telegramIdBytes(telegramId))
    }

    fun decrypt(encrypted: ByteArray): Long {
        if (encrypted.size != ENCRYPTED_ID_SIZE_BYTES) {
            throw AEADBadTagException("Invalid encrypted Telegram ID length")
        }
        val nonce = encrypted.copyOfRange(0, NONCE_SIZE_BYTES)
        val cipher = createCipher(Cipher.DECRYPT_MODE, nonce)
        val plaintext = cipher.doFinal(encrypted, NONCE_SIZE_BYTES, encrypted.size - NONCE_SIZE_BYTES)

        return ByteBuffer.wrap(plaintext).long
    }

    fun lookup(telegramId: Long): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(hmacKey)

        return mac.doFinal(telegramIdBytes(telegramId))
    }

    private fun createCipher(
        mode: Int,
        nonce: ByteArray,
    ): Cipher =
        Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(mode, aesKey, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        }

    private fun telegramIdBytes(telegramId: Long): ByteArray =
        ByteBuffer
            .allocate(Long.SIZE_BYTES)
            .putLong(telegramId)
            .array()
}
