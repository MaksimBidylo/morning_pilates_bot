package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.telegram.training.reply.TrainingReply

internal const val CANCEL_TRAINING_CALLBACK = "training:cancel"
internal const val ADD_TECHNIQUE_YES_CALLBACK = "training:add_technique:yes"
internal const val ADD_TECHNIQUE_NO_CALLBACK = "training:add_technique:no"
internal const val ADD_TECHNIQUE_DESCRIPTION_YES_CALLBACK = "training:add_technique_description:yes"
internal const val ADD_TECHNIQUE_DESCRIPTION_NO_CALLBACK = "training:add_technique_description:no"
internal const val ADD_ANOTHER_TECHNIQUE_YES_CALLBACK = "training:add_another_technique:yes"
internal const val ADD_ANOTHER_TECHNIQUE_NO_CALLBACK = "training:add_another_technique:no"
internal const val ADD_NOTE_YES_CALLBACK = "training:add_note:yes"
internal const val ADD_NOTE_NO_CALLBACK = "training:add_note:no"
internal const val CONFIRMATION_YES_CALLBACK = "training:confirmation:yes"
internal const val CONFIRMATION_NO_CALLBACK = "training:confirmation:no"

internal fun TrainingReply.withCreationQuestionToken(token: String): TrainingReply {
    val keyboard = replyMarkup ?: return this
    return copy(
        replyMarkup =
            InlineKeyboardMarkup.create(
                keyboard.inlineKeyboard.map { row ->
                    row.map { button ->
                        check(button is InlineKeyboardButton.CallbackData)
                        button.copy(callbackData = "${button.callbackData}:$token")
                    }
                },
            ),
    )
}

internal fun TrainingReply.callbackData(): Set<String> =
    replyMarkup
        ?.inlineKeyboard
        .orEmpty()
        .flatten()
        .map {
            check(it is InlineKeyboardButton.CallbackData)
            it.callbackData
        }.toSet()
