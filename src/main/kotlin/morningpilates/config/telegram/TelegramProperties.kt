package morningpilates.config.telegram

import kotlinx.serialization.Serializable

@Serializable
data class TelegramProperties(
    val botToken: String,
    val trainingDateCalendarEnabled: Boolean = false,
    val trainingSearchDateCalendarEnabled: Boolean = false,
    val trainingTimePickerEnabled: Boolean = false,
)
