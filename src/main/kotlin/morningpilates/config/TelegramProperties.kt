package morningpilates.config

import kotlinx.serialization.Serializable

@Serializable
data class TelegramProperties(
    val botToken: String,
)
