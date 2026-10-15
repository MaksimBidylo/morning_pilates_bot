package morningpilates.telegram.training.reply

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.ParseMode

internal data class TrainingReply(
    val text: String,
    val replyMarkup: InlineKeyboardMarkup? = null,
    val parseMode: ParseMode? = null,
)
