package morningpilates.config

class TelegramIdKeys(
    val encryptionKey: ByteArray,
    val lookupKey: ByteArray,
) {
    override fun toString(): String = "TelegramIdKeys(encryptionKey=***, lookupKey=***)"
}
