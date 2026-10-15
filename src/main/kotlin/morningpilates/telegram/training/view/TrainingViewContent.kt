package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.telegram.training.reply.TrainingReply
import morningpilates.training.SavedTraining
import java.time.format.DateTimeFormatter

private val DELETE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu")
private val DELETE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

internal data class TrainingViewContent(
    val training: Result<SavedTraining?> = Result.success(null),
    val dateSearch: TrainingDateSearch? = null,
    val deletionTarget: SavedTraining? = null,
    val deletionOutcome: TrainingDeletionOutcome? = null,
) {
    fun actions(): Set<TrainingViewAction> =
        when {
            deletionOutcome == TrainingDeletionOutcome.Unavailable -> {
                setOf(TrainingViewAction.Finish)
            }

            deletionOutcome == TrainingDeletionOutcome.Failed -> {
                setOf(TrainingViewAction.RetryDelete, TrainingViewAction.CancelDelete)
            }

            deletionTarget != null -> {
                setOf(TrainingViewAction.ConfirmDelete, TrainingViewAction.CancelDelete)
            }

            else -> {
                dateSearch?.viewActions() ?: training.viewActions()
            }
        }

    fun reply(
        token: TrainingViewToken,
        actions: Set<TrainingViewAction>,
    ): TrainingReply {
        val target = deletionTarget
        val reply =
            if (target == null) {
                dateSearch?.toTrainingViewReply(token, actions) ?: training.toTrainingViewReply(token, actions)
            } else {
                target.toDeletionConfirmationReply(token, actions)
            }
        val text =
            when (deletionOutcome) {
                TrainingDeletionOutcome.Failed -> {
                    "Не удалось удалить тренировку. Попробуйте ещё раз."
                }

                TrainingDeletionOutcome.Unavailable -> {
                    "Тренировка больше недоступна."
                }

                TrainingDeletionOutcome.Deleted -> {
                    if (training.isFailure) {
                        "Тренировка удалена. Не удалось загрузить следующую тренировку."
                    } else {
                        "Тренировка удалена.\n\n${reply.text}"
                    }
                }

                null -> {
                    reply.text
                }
            }
        return reply.copy(text = text)
    }
}

private fun SavedTraining.toDeletionConfirmationReply(
    token: TrainingViewToken,
    actions: Set<TrainingViewAction>,
): TrainingReply {
    val date = details.date.format(DELETE_DATE_FORMAT)
    val time = details.time.format(DELETE_TIME_FORMAT)
    return TrainingReply(
        "Удалить тренировку за $date в $time? Это действие нельзя отменить.",
        replyMarkup =
            InlineKeyboardMarkup.create(
                actions.map { InlineKeyboardButton.CallbackData(it.buttonText, token.callbackData(it)) },
            ),
    )
}
