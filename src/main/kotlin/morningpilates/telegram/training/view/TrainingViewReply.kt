package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.telegram.training.reply.TrainingReply
import morningpilates.telegram.training.reply.toSavedTrainingReply
import morningpilates.training.SavedTraining

internal const val VIEW_LOAD_ERROR = "Не удалось загрузить тренировки. Попробуйте ещё раз."
private val EMPTY_VIEW_REPLY =
    TrainingReply("Тренировок пока нет.\n\nЗапиши свою первую тренировку с помощью команды /training_add.")
private val CARD_ACTIONS = setOf(TrainingViewAction.Finish)
private val ERROR_ACTIONS = setOf(TrainingViewAction.Retry, TrainingViewAction.Finish)

internal fun Result<SavedTraining?>.viewActions(): Set<TrainingViewAction> =
    fold(
        onSuccess = { if (it == null) emptySet() else CARD_ACTIONS },
        onFailure = { ERROR_ACTIONS },
    )

internal fun Result<SavedTraining?>.toTrainingViewReply(
    token: TrainingViewToken,
    actions: Set<TrainingViewAction>,
): TrainingReply {
    val reply =
        fold(
            onSuccess = { it?.details?.toSavedTrainingReply(title = "Последняя тренировка") ?: EMPTY_VIEW_REPLY },
            onFailure = { TrainingReply(VIEW_LOAD_ERROR) },
        )
    return reply.copy(
        replyMarkup =
            if (actions.isEmpty()) {
                InlineKeyboardMarkup.create()
            } else {
                InlineKeyboardMarkup.create(
                    actions.map {
                        InlineKeyboardButton.CallbackData(it.buttonText, token.callbackData(it))
                    },
                )
            },
    )
}

internal fun TrainingViewToken.callbackData(action: TrainingViewAction): String = "view:${action.callbackName}:$value"
