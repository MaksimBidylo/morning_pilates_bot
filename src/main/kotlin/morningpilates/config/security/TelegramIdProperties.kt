package morningpilates.config.security

import kotlinx.serialization.Serializable
import java.util.Base64

private const val TELEGRAM_ID_KEY_SIZE_BYTES = 32

@Serializable
class TelegramIdProperties(
    val encryptionKey: String,
    val lookupKey: String,
) {
    fun decodeKeys(): TelegramIdKeys {
        val decodedEncryptionKey = decodeKey("encryptionKey", encryptionKey)
        val decodedLookupKey = decodeKey("lookupKey", lookupKey)
        require(!decodedEncryptionKey.contentEquals(decodedLookupKey)) {
            "Encryption and lookup keys must have different contents"
        }
        return TelegramIdKeys(decodedEncryptionKey, decodedLookupKey)
    }

    override fun toString(): String = "TelegramIdProperties(encryptionKey=***, lookupKey=***)"

    private fun decodeKey(
        parameterName: String,
        encodedKey: String,
    ): ByteArray {
        val decodedKey =
            try {
                Base64.getDecoder().decode(encodedKey)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("$parameterName must contain valid Base64")
            }
        require(decodedKey.size == TELEGRAM_ID_KEY_SIZE_BYTES) {
            "$parameterName must decode to exactly $TELEGRAM_ID_KEY_SIZE_BYTES bytes"
        }
        return decodedKey
    }
}
