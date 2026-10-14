package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.SavedTraining
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingId
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FAILURE_USER = TelegramUserId(42L)
private val FAILURE_CHAT = TelegramChatId(4_200L)
private val FAILURE_TRAINING =
    SavedTraining(
        TrainingId(UUID.fromString("00000000-0000-0000-0000-000000000098")),
        TrainingDetails(LocalDate.of(2026, 10, 12), LocalTime.of(6, 30), note = "Сохранённая тренировка"),
    )
private const val LOAD_ERROR = "Не удалось загрузить тренировки. Попробуйте ещё раз."
private const val RETRY_VIEW = "Повторить попытку"
private const val FINISH_FAILED_VIEW = "Завершить просмотр"
private val LOAD_FAILURE: Result<SavedTraining?> = Result.failure(IllegalStateException("PostgreSQL is unavailable"))
private const val EMPTY_FAILURE_VIEW =
    "Тренировок пока нет.\n\nЗапиши свою первую тренировку с помощью команды /training_add."

internal class TrainingViewFailureTest {
    private val repository = FakeTrainingRepository()
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingMessageSessionStorage()
    private val events = mutableListOf<FlowEvent>()
    private val service = TrainingFlowService(repository)
    private val observed =
        object : FlowHandler by service {
            override fun handle(
                state: FlowState,
                event: FlowEvent,
            ) = service.handle(state, event).also { events += event }
        }
    private val manager = FlowSessionManager(storage, listOf(observed))

    @ParameterizedTest(name = "database failure with active draft = {0}")
    @ValueSource(booleans = [false, true])
    fun `first database failure displays an active error view without changing creation`(activeDraft: Boolean) =
        runBlocking<Unit> {
            createBot().use { bot ->
                if (activeDraft) startDraft(bot)
                val session = storage.get(FAILURE_USER)
                val registration = messages.get(FAILURE_USER)
                val question = registration?.let { bot.currentMessage(it.messageId) }
                val token = messages.questionToken(FAILURE_USER)
                val beforeEvents = events.toList()
                val before = bot.apiCalls.size

                val error = openError(bot)

                assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls.drop(before))
                assertEquals(FAILURE_CHAT, error.chatId)
                assertEquals(session, storage.get(FAILURE_USER))
                assertEquals(registration, messages.get(FAILURE_USER))
                assertEquals(token, messages.questionToken(FAILURE_USER))
                if (registration != null) assertEquals(question, bot.currentMessage(registration.messageId))
                assertEquals(beforeEvents, events)
                assertEquals(listOf(FAILURE_USER), repository.findLatestCalls)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @ParameterizedTest(name = "retry returns an empty diary = {0}")
    @ValueSource(booleans = [false, true])
    fun `retry reloads for the same user in the same message and invalidates the old retry`(empty: Boolean) =
        runBlocking<Unit> {
            createBot().use { bot ->
                val error = openError(bot)
                val retry = bot.captureCallback(error, RETRY_VIEW)
                repository.findLatestResults[FAILURE_USER] = Result.success(if (empty) null else FAILURE_TRAINING)
                val before = bot.apiCalls.size

                val result = bot.deliverCallbackAndCollectReplies(retry)

                assertTrue(result.acknowledged)
                assertTrue(result.replies.isEmpty())
                assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(before))
                assertEquals(listOf(TelegramCallbackAnswer(retry.id, null)), bot.answerCallbackQueryRequests)
                val current = bot.currentMessage(error.messageId)
                assertEquals(FAILURE_CHAT, current.chatId)
                if (empty) {
                    assertEquals(EMPTY_FAILURE_VIEW, current.text)
                    assertTrue(current.inlineKeyboard.isEmpty())
                } else {
                    assertTrue(current.text.startsWith("Последняя тренировка\n\n<blockquote expandable>"))
                    assertTrue(current.text.contains("Сохранённая тренировка"))
                    assertEquals("HTML", current.parseMode)
                    assertEquals(listOf(FINISH_FAILED_VIEW), current.inlineKeyboard.flatten().map { it.text })
                }
                assertEquals(listOf(FAILURE_USER, FAILURE_USER), repository.findLatestCalls)
                val after = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(retry).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(after))
                assertEquals(listOf(FAILURE_USER, FAILURE_USER), repository.findLatestCalls)
                assertEquals(current, bot.currentMessage(error.messageId))
                assertEquals(1, bot.sendMessageRequests.size)
                assertNull(storage.get(FAILURE_USER))
                assertTrue(events.isEmpty())
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `repeated database failures keep one usable error screen even when Telegram reports no changes`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val error = openError(bot)
                repeat(2) {
                    bot.failNextEditMessageText("Bad Request: message is not modified")
                    val before = bot.apiCalls.size
                    val result = bot.pressInlineButtonAndCollectReplies(bot.currentMessage(error.messageId), RETRY_VIEW)
                    assertTrue(result.acknowledged)
                    assertTrue(result.replies.isEmpty())
                    assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(before))
                    assertEquals(error, bot.editedMessages.last(), "The unchanged response requires identical content")
                    assertEquals(error, bot.currentMessage(error.messageId))
                }
                assertEquals(listOf(FAILURE_USER, FAILURE_USER, FAILURE_USER), repository.findLatestCalls)
                assertEquals(2, bot.answerCallbackQueryRequests.size)
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(bot.pressInlineButtonAndCollectReplies(error, FINISH_FAILED_VIEW).acknowledged)
                assertEquals(
                    TelegramDeleteAttempt(FAILURE_CHAT, error.messageId, true),
                    bot.deleteMessageAttempts.last(),
                )
                assertNull(storage.get(FAILURE_USER))
                assertTrue(events.isEmpty())
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @ParameterizedTest(name = "close an error view using cancel command = {0}")
    @ValueSource(booleans = [false, true])
    fun `both ways close an error view and permit a fresh view`(command: Boolean) =
        runBlocking<Unit> {
            createBot().use { bot ->
                val error = openError(bot)
                val old = bot.captureCallback(error, RETRY_VIEW)
                val before = bot.apiCalls.size
                if (command) {
                    assertTrue(bot.sendCommandAndCollectReplies("/cancel", messageId = 3_101L).isEmpty())
                    assertEquals(listOf("deleteMessage", "deleteMessage"), bot.apiCalls.drop(before))
                    assertEquals(
                        TelegramDeleteAttempt(FAILURE_CHAT, TelegramMessageId(3_101L), true),
                        bot.deleteMessageAttempts.last(),
                    )
                } else {
                    assertTrue(bot.pressInlineButtonAndCollectReplies(error, FINISH_FAILED_VIEW).acknowledged)
                    assertEquals(listOf("deleteMessage", "answerCallbackQuery"), bot.apiCalls.drop(before))
                }
                assertTrue(TelegramDeleteAttempt(FAILURE_CHAT, error.messageId, true) in bot.deleteMessageAttempts)
                repository.findLatestResults[FAILURE_USER] = Result.success(FAILURE_TRAINING)
                val next = bot.sendCommand("/training_get_last", messageId = 2_714L)
                assertNotEquals(error.messageId, TelegramMessageId(next.messageId))
                val calls = repository.findLatestCalls.toList()
                val after = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(old).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(after))
                assertEquals(calls, repository.findLatestCalls)
                assertEquals(2, bot.sendMessageRequests.size)
                assertTrue(events.isEmpty())
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `database failure replaces an existing card with an error screen in the same message`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                repository.findLatestResults[FAILURE_USER] = Result.success(FAILURE_TRAINING)
                val sent = bot.sendCommand("/training_get_last", messageId = 2_713L)
                val messageId = TelegramMessageId(sent.messageId)
                repository.findLatestResults[FAILURE_USER] = LOAD_FAILURE
                val before = bot.apiCalls.size

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_714L).isEmpty())

                assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(before))
                assertErrorScreen(bot.currentMessage(messageId))
                assertEquals(1, bot.sendMessageRequests.size)
                assertNull(storage.get(FAILURE_USER))
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @ParameterizedTest
    @EnumSource(InvalidRetry::class)
    fun `a foreign or forged retry cannot read data and the legitimate retry still works`(kind: InvalidRetry) =
        runBlocking<Unit> {
            createBot().use { bot ->
                val error = openError(bot)
                val original = bot.captureCallback(error, RETRY_VIEW)
                val message = assertNotNull(original.message)
                val foreign =
                    when (kind) {
                        InvalidRetry.USER -> {
                            original.copy(from = original.from.copy(id = 84L))
                        }

                        InvalidRetry.CHAT -> {
                            original.copy(message = message.copy(chat = message.chat.copy(id = 8_400L)))
                        }

                        InvalidRetry.MESSAGE -> {
                            original.copy(message = message.copy(messageId = error.messageId.value + 1))
                        }

                        InvalidRetry.TOKEN -> {
                            original.copy(data = original.data + "invalid")
                        }

                        InvalidRetry.CROSS_NAMESPACE -> {
                            original.copy(data = original.data + ":training:cancel")
                        }
                    }
                repository.findLatestResults[FAILURE_USER] = Result.success(FAILURE_TRAINING)
                repository.findLatestResults[TelegramUserId(84L)] =
                    Result.success(
                        FAILURE_TRAINING.copy(details = FAILURE_TRAINING.details.copy(note = "Чужая тренировка")),
                    )
                val before = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(foreign).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
                assertEquals(listOf(FAILURE_USER), repository.findLatestCalls)
                assertEquals(error, bot.currentMessage(error.messageId))
                assertTrue(bot.deliverCallbackAndCollectReplies(original).acknowledged)
                assertEquals(listOf(FAILURE_USER, FAILURE_USER), repository.findLatestCalls)
                assertTrue(bot.currentMessage(error.messageId).text.contains("Сохранённая тренировка"))
                assertEquals(2, bot.answerCallbackQueryRequests.size)
                assertEquals(1, bot.sendMessageRequests.size)
                assertNull(storage.get(FAILURE_USER))
                assertNull(storage.get(TelegramUserId(84L)))
                assertTrue(events.isEmpty())
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @ParameterizedTest(name = "missing previous message showed an empty diary = {0}")
    @ValueSource(booleans = [false, true])
    fun `confirmed missing view is replaced without changing the active training`(empty: Boolean) =
        runBlocking<Unit> {
            createBot().use { bot ->
                val question = startDraft(bot)
                val session = assertNotNull(storage.get(FAILURE_USER))
                val registration = messages.get(FAILURE_USER)
                val token = messages.questionToken(FAILURE_USER)
                val beforeEvents = events.toList()
                repository.findLatestResults[FAILURE_USER] = Result.success(if (empty) null else FAILURE_TRAINING)
                val sent = bot.sendCommand("/training_get_last", messageId = 2_713L)
                val previous = bot.currentMessage(TelegramMessageId(sent.messageId))
                val old = if (empty) null else bot.captureCallback(previous, FINISH_FAILED_VIEW)
                repository.findLatestResults[FAILURE_USER] = Result.success(FAILURE_TRAINING)
                bot.failNextEditMessageText("Bad Request: message to edit not found")
                val before = bot.apiCalls.size

                val replies = bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_714L)

                assertEquals(listOf("editMessageText", "sendMessage", "deleteMessage"), bot.apiCalls.drop(before))
                assertEquals(1, replies.size)
                val replacement = bot.currentMessage(TelegramMessageId(replies.single().messageId))
                assertNotEquals(previous.messageId, replacement.messageId)
                assertEquals(FAILURE_CHAT, replacement.chatId)
                assertTrue(replacement.text.contains("Сохранённая тренировка"))
                assertEquals("HTML", replacement.parseMode)
                assertEquals(listOf(FINISH_FAILED_VIEW), replacement.inlineKeyboard.flatten().map { it.text })
                assertEquals(
                    TelegramDeleteAttempt(FAILURE_CHAT, TelegramMessageId(2_714L), true),
                    bot.deleteMessageAttempts.last(),
                )
                assertEquals(session, storage.get(FAILURE_USER))
                assertEquals(registration, messages.get(FAILURE_USER))
                assertEquals(token, messages.questionToken(FAILURE_USER))
                assertEquals(question, bot.currentMessage(question.messageId))
                assertEquals(beforeEvents, events)
                if (old != null) {
                    val after = bot.apiCalls.size
                    assertTrue(bot.deliverCallbackAndCollectReplies(old).acknowledged)
                    assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(after))
                }
                assertTrue(bot.pressInlineButtonAndCollectReplies(replacement, FINISH_FAILED_VIEW).acknowledged)
                assertEquals(
                    TelegramDeleteAttempt(FAILURE_CHAT, replacement.messageId, true),
                    bot.deleteMessageAttempts.last(),
                )
                assertEquals(session, storage.get(FAILURE_USER))
                assertEquals(beforeEvents, events)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `temporary Telegram failure keeps the original view and draft and permits manual refresh`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val question = startDraft(bot)
                val session = storage.get(FAILURE_USER)
                val registration = messages.get(FAILURE_USER)
                val token = messages.questionToken(FAILURE_USER)
                val beforeEvents = events.toList()
                repository.findLatestResults[FAILURE_USER] = Result.success(FAILURE_TRAINING)
                val sent = bot.sendCommand("/training_get_last", messageId = 2_713L)
                val original = bot.currentMessage(TelegramMessageId(sent.messageId))
                repository.findLatestResults[FAILURE_USER] =
                    Result.success(FAILURE_TRAINING.copy(details = FAILURE_TRAINING.details.copy(note = "Обновлено")))
                bot.respondToNextEditMessageText(
                    503,
                    """{"ok":false,"error_code":503,"description":"Service Unavailable: retry later"}""",
                )
                val before = bot.apiCalls.size

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_714L).isEmpty())

                assertEquals(listOf("editMessageText"), bot.apiCalls.drop(before))
                assertEquals(original, bot.currentMessage(original.messageId))
                assertEquals(2, bot.sendMessageRequests.size)
                assertTrue(bot.deleteMessageAttempts.none { it.messageId == TelegramMessageId(2_714L) })
                assertEquals(session, storage.get(FAILURE_USER))
                assertEquals(registration, messages.get(FAILURE_USER))
                assertEquals(token, messages.questionToken(FAILURE_USER))
                assertEquals(question, bot.currentMessage(question.messageId))
                assertEquals(beforeEvents, events)
                val after = bot.apiCalls.size
                assertTrue(bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_715L).isEmpty())
                assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(after))
                val refreshed = bot.currentMessage(original.messageId)
                assertTrue(refreshed.text.contains("Обновлено"))
                assertEquals(original.inlineKeyboard, refreshed.inlineKeyboard)
                assertEquals(session, storage.get(FAILURE_USER))
                assertEquals(beforeEvents, events)
                assertTrue(bot.pressInlineButtonAndCollectReplies(refreshed, FINISH_FAILED_VIEW).acknowledged)
                assertEquals(2, bot.sendMessageRequests.size)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `failed first card send preserves the draft and a later command creates a usable view`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val question = startDraft(bot)
                val session = storage.get(FAILURE_USER)
                val registration = messages.get(FAILURE_USER)
                val token = messages.questionToken(FAILURE_USER)
                val beforeEvents = events.toList()
                repository.findLatestResults[FAILURE_USER] = Result.success(FAILURE_TRAINING)
                bot.failNextSendMessage("Bad Request: failed to send")
                val before = bot.apiCalls.size

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_713L).isEmpty())

                assertEquals(listOf("sendMessage"), bot.apiCalls.drop(before))
                assertTrue(bot.deleteMessageAttempts.none { it.messageId == TelegramMessageId(2_713L) })
                assertEquals(session, storage.get(FAILURE_USER))
                assertEquals(registration, messages.get(FAILURE_USER))
                assertEquals(token, messages.questionToken(FAILURE_USER))
                assertEquals(question, bot.currentMessage(question.messageId))
                assertEquals(beforeEvents, events)
                val after = bot.apiCalls.size
                val sent = bot.sendCommand("/training_get_last", messageId = 2_714L)
                assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls.drop(after))
                val current = bot.currentMessage(TelegramMessageId(sent.messageId))
                assertTrue(bot.pressInlineButtonAndCollectReplies(current, FINISH_FAILED_VIEW).acknowledged)
                assertEquals(session, storage.get(FAILURE_USER))
                assertEquals(beforeEvents, events)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `failed replacement keeps the old registration and a later command can recover`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val question = startDraft(bot)
                val session = storage.get(FAILURE_USER)
                val registration = messages.get(FAILURE_USER)
                val beforeEvents = events.toList()
                repository.findLatestResults[FAILURE_USER] = Result.success(FAILURE_TRAINING)
                val sent = bot.sendCommand("/training_get_last", messageId = 2_713L)
                val original = bot.currentMessage(TelegramMessageId(sent.messageId))
                val old = bot.captureCallback(original, FINISH_FAILED_VIEW)
                bot.failNextEditMessageText("Bad Request: message to edit not found")
                bot.failNextSendMessage("Bad Request: failed to send")
                val before = bot.apiCalls.size

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_714L).isEmpty())

                assertEquals(listOf("editMessageText", "sendMessage"), bot.apiCalls.drop(before))
                assertEquals(original.messageId, bot.editedMessages.last().messageId)
                assertEquals(original, bot.currentMessage(original.messageId))
                assertTrue(bot.deleteMessageAttempts.none { it.messageId == TelegramMessageId(2_714L) })
                assertEquals(session, storage.get(FAILURE_USER))
                assertEquals(registration, messages.get(FAILURE_USER))
                assertEquals(question, bot.currentMessage(question.messageId))
                assertEquals(beforeEvents, events)

                bot.failNextEditMessageText("Bad Request: message to edit not found")
                val after = bot.apiCalls.size
                val recovered = bot.sendCommand("/training_get_last", messageId = 2_715L)
                assertEquals(listOf("editMessageText", "sendMessage", "deleteMessage"), bot.apiCalls.drop(after))
                assertEquals(original.messageId, bot.editedMessages.last().messageId)
                val current = bot.currentMessage(TelegramMessageId(recovered.messageId))
                assertNotEquals(original.messageId, current.messageId)
                assertNotEquals(
                    old.data,
                    current.inlineKeyboard
                        .single()
                        .single()
                        .callbackData,
                )
                val calls = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(old).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(calls))
                assertTrue(bot.pressInlineButtonAndCollectReplies(current, FINISH_FAILED_VIEW).acknowledged)
                assertEquals(session, storage.get(FAILURE_USER))
                assertEquals(beforeEvents, events)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    private suspend fun openError(bot: TestTelegramBot): EditedTelegramMessage {
        repository.findLatestResults[FAILURE_USER] = LOAD_FAILURE
        val replies = bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_713L)
        assertEquals(1, replies.size, "A database failure must display a registered error screen")
        val current = bot.currentMessage(TelegramMessageId(replies.single().messageId))
        assertErrorScreen(current)
        return current
    }

    private fun assertErrorScreen(message: EditedTelegramMessage) {
        assertEquals(LOAD_ERROR, message.text)
        assertEquals(listOf(RETRY_VIEW, FINISH_FAILED_VIEW), message.inlineKeyboard.flatten().map { it.text })
        assertTrue(
            message.inlineKeyboard.flatten().all {
                it.callbackData.isNotBlank() && it.callbackData.toByteArray(Charsets.UTF_8).size <= 64
            },
        )
    }

    private suspend fun startDraft(bot: TestTelegramBot): EditedTelegramMessage {
        bot.sendCommand("/training_add", messageId = 713L)
        bot.sendTextAndCollectReplies("14.10.2026", messageId = 829L)
        bot.sendTextAndCollectReplies("7:45", messageId = 947L)
        assertEquals(
            WaitingAddTechnique(TrainingDetails(LocalDate.of(2026, 10, 14), LocalTime.of(7, 45))),
            assertNotNull(storage.get(FAILURE_USER)).state,
        )
        return bot.currentMessage(assertNotNull(messages.get(FAILURE_USER)).messageId)
    }

    private fun createBot() =
        TestTelegramBot(
            TelegramBot(TelegramProperties("test-token"), storage, manager, messages, TrainingQueryService(repository)),
            chatId = FAILURE_CHAT.value,
        )

    internal enum class InvalidRetry { USER, CHAT, MESSAGE, TOKEN, CROSS_NAMESPACE }
}
