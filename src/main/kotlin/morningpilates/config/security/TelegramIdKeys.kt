package morningpilates.config.security

class TelegramIdKeys(
    val encryptionKey: ByteArray,
    val lookupKey: ByteArray,
) {
    override fun toString(): String = "TelegramIdKeys(encryptionKey=***, lookupKey=***)"
}
