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
                TrainingReply(listOfNotNull(warning, DATE_VIEW_QUESTION).joinToString("\n\n"))
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
    return reply.copy(
        replyMarkup =
            InlineKeyboardMarkup.create(
                actions.map { InlineKeyboardButton.CallbackData(it.buttonText, token.callbackData(it)) },
            ),
    )
}
