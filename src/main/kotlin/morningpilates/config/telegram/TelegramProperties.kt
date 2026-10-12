package morningpilates.config.telegram

import kotlinx.serialization.Serializable

@Serializable
data class TelegramProperties(
    val botToken: String,
)
