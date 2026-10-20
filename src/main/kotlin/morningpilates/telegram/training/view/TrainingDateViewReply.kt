package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.telegram.training.reply.TrainingReply
import morningpilates.telegram.training.reply.toSavedTrainingReply
import java.time.format.DateTimeFormatter

internal const val DATE_VIEW_QUESTION = "Введите дату тренировки. Формат: ДД.ММ.ГГГГ"
private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu")

internal fun TrainingDateSearch.viewActions(): Set<TrainingViewAction> =
    buildSet {
        if (loadFailed) {
            add(TrainingViewAction.Retry)
        } else if (date != null) {
            if (trainings.isNotEmpty()) add(TrainingViewAction.Delete)
            if (index > 0) add(TrainingViewAction.Previous)
            if (index < trainings.lastIndex) add(TrainingViewAction.Next)
            add(TrainingViewAction.ChangeDate)
        }
        add(TrainingViewAction.Finish)
    }

internal fun TrainingDateSearch.toTrainingViewReply(
    token: TrainingViewToken,
    actions: Set<TrainingViewAction>,
): TrainingReply {
    val formattedDate = date?.format(DATE_FORMAT)
    val reply =
        when {
            date == null -> {
                val question =
                    if (calendar == null) {
                        DATE_VIEW_QUESTION
                    } else {
                        "Выберите дату в календаре или отправьте её сообщением. Формат: ДД.ММ.ГГГГ"
                    }
                val parts = if (calendar == null) listOfNotNull(warning, question) else listOfNotNull(question, warning)
                TrainingReply(parts.joinToString("\n\n"))
            }

            loadFailed -> {
                TrainingReply(VIEW_LOAD_ERROR)
            }

            trainings.isEmpty() -> {
                TrainingReply("За $formattedDate тренировок не найдено.")
            }

            else -> {
                trainings[index].details.toSavedTrainingReply(
                    title = "Тренировки за $formattedDate\nТренировка ${index + 1} из ${trainings.size}",
                )
            }
        }
    val withActions =
        reply.copy(
            replyMarkup =
                InlineKeyboardMarkup.create(
                    actions.map { InlineKeyboardButton.CallbackData(it.buttonText, token.callbackData(it)) },
                ),
        )
    return calendar?.takeIf { isWaitingForDate }?.reply(withActions, SEARCH_CALENDAR_PREFIX, token.value) ?: withActions
}
