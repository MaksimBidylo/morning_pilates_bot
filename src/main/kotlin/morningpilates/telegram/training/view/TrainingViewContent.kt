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
    val notice: String? = null,
) {
    fun actions(): Set<TrainingViewAction> =
        if (deletionTarget != null) {
            setOf(TrainingViewAction.ConfirmDelete, TrainingViewAction.CancelDelete)
        } else {
            dateSearch?.viewActions() ?: training.viewActions()
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
                val date = target.details.date.format(DELETE_DATE_FORMAT)
                val time = target.details.time.format(DELETE_TIME_FORMAT)
                TrainingReply(
                    "Удалить тренировку за $date в $time? Это действие нельзя отменить.",
                    replyMarkup =
                        InlineKeyboardMarkup.create(
                            actions.map { InlineKeyboardButton.CallbackData(it.buttonText, token.callbackData(it)) },
                        ),
                )
            }
        return if (notice == null) reply else reply.copy(text = "$notice\n\n${reply.text}")
    }
}
