package morningpilates.config

internal const val TEST_TELEGRAM_ID_ENCRYPTION_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
internal const val TEST_TELEGRAM_ID_LOOKUP_KEY = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="

internal fun telegramIdTestKeys(): Map<String, String> =
    mapOf(
        "encryptionKey" to TEST_TELEGRAM_ID_ENCRYPTION_KEY,
        "lookupKey" to TEST_TELEGRAM_ID_LOOKUP_KEY,
    )
