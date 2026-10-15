package morningpilates.telegram.support

import com.github.kotlintelegrambot.entities.CallbackQuery
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.training.reply.toSavedTrainingReply
import morningpilates.training.FakeTrainingRepository.DeleteCall
import morningpilates.training.SavedTraining
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal const val DELETE_TRAINING = "Удалить тренировку"
internal const val CONFIRM_DELETE = "Да, удалить"
internal const val CANCEL_DELETE = "Отмена"
internal const val DELETED_NOTICE = "Тренировка удалена."
internal const val EMPTY_LATEST =
    "Тренировок пока нет.\n\nЗапиши свою первую тренировку с помощью команды /training_add."

internal enum class DeleteViewMode { LATEST, DATE }

internal class TrainingViewDeleteTestFixture {
    val base = TrainingDateViewTestFixture()
    val repository get() = base.repository
    val user get() = base.user

    // The first two records share date and time, but have different IDs and contents.
    val trainings =
        base.trainings.mapIndexed { index, training ->
            if (index == 1) {
                training.copy(
                    details =
                        training.details.copy(
                            time =
                                base.trainings
                                    .first()
                                    .details.time,
                        ),
                )
            } else {
                training
            }
        }

    suspend fun openCard(
        bot: TestTelegramBot,
        mode: DeleteViewMode,
        position: Int = 1,
        values: List<SavedTraining> = trainings,
    ): EditedTelegramMessage {
        val card =
            if (mode == DeleteViewMode.LATEST) {
                repository.findLatestResults[user] = Result.success(values[position])
                val replies = bot.sendCommandAndCollectReplies("/training_get_last", user.value)
                assertEquals(1, replies.size)
                bot.currentMessage(TelegramMessageId(replies.single().messageId))
            } else {
                base.configure(values)
                var current = base.enterDate(bot, base.openQuestion(bot))
                repeat(position) { current = press(bot, current, DATE_VIEW_NEXT) }
                current
            }
        assertCard(card, mode, values, position)
        return card
    }

    fun assertCard(
        card: EditedTelegramMessage,
        mode: DeleteViewMode,
        values: List<SavedTraining>,
        position: Int,
    ) {
        val details = values[position].details
        val shared = details.toSavedTrainingReply()
        assertEquals("HTML", card.parseMode)
        assertEquals(
            shared.text.substringAfter("<blockquote expandable>").substringBefore("</blockquote>"),
            card.text.substringAfter("<blockquote expandable>").substringBefore("</blockquote>"),
        )
        val buttons = mutableSetOf(DELETE_TRAINING, DATE_VIEW_FINISH)
        if (mode == DeleteViewMode.DATE) {
            val date = details.date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))
            assertTrue(card.text.contains("Тренировки за $date"))
            assertTrue(card.text.contains("Тренировка ${position + 1} из ${values.size}"))
            buttons += DATE_VIEW_CHANGE
            if (position > 0) buttons += DATE_VIEW_PREVIOUS
            if (position < values.lastIndex) buttons += DATE_VIEW_NEXT
        } else {
            assertTrue(card.text.contains("Последняя тренировка"))
        }
        base.assertButtons(card, buttons)
    }

    suspend fun openConfirmation(
        bot: TestTelegramBot,
        card: EditedTelegramMessage,
        selected: SavedTraining,
    ): EditedTelegramMessage {
        val dates = repository.findByDateCalls.toList()
        val latest = repository.findLatestCalls.toList()
        val deletes = repository.deleteCalls.toList()
        val saves = repository.saveCalls.toList()
        val result = press(bot, card, DELETE_TRAINING)
        val date = selected.details.date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))
        val time = selected.details.time.format(DateTimeFormatter.ofPattern("HH:mm"))
        assertEquals("Удалить тренировку за $date в $time? Это действие нельзя отменить.", result.text)
        base.assertButtons(result, setOf(CONFIRM_DELETE, CANCEL_DELETE))
        assertEquals(dates, repository.findByDateCalls)
        assertEquals(latest, repository.findLatestCalls)
        assertEquals(deletes, repository.deleteCalls)
        assertEquals(saves, repository.saveCalls)
        return result
    }

    fun capture(
        bot: TestTelegramBot,
        message: EditedTelegramMessage,
        button: String,
    ): CallbackQuery {
        assertTrue(message.inlineKeyboard.flatten().any { it.text == button }, "Expected button '$button' on the view")
        return bot.captureCallback(message, button, user)
    }

    suspend fun press(
        bot: TestTelegramBot,
        message: EditedTelegramMessage,
        button: String,
    ): EditedTelegramMessage {
        val result = bot.deliverViewCallbackOnce(capture(bot, message, button))
        assertTrue(result.replies.isEmpty(), "Viewing and deletion must edit the same message")
        return bot.currentMessage(message.messageId)
    }

    fun allowDelete(selected: SavedTraining) {
        repository.deleteResults[DeleteCall(user, selected.id)] = Result.success(true)
    }

    fun assertDeleted(selected: SavedTraining) {
        assertEquals(listOf(DeleteCall(user, selected.id)), repository.deleteCalls)
        assertTrue(repository.saveCalls.isEmpty())
    }

    suspend fun assertStaleConfirmation(
        bot: TestTelegramBot,
        callback: CallbackQuery,
    ) {
        val view = base.activeView()
        val deleted = repository.deleteCalls.toList()
        val operations = repository.operations.toList()
        val originalMessage = bot.currentMessage(TelegramMessageId(assertNotNull(callback.message).messageId))
        base.assertIgnoredViewCallback(bot, callback)
        assertEquals(deleted, repository.deleteCalls)
        assertEquals(operations, repository.operations)
        assertEquals(view, base.activeView())
        assertEquals(originalMessage, bot.currentMessage(originalMessage.messageId))
    }
}
