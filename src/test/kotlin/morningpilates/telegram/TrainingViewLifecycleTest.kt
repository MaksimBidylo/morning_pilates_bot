package morningpilates.telegram

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.SavedTraining
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingDate
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

private const val FINISH_VIEW = "Завершить просмотр"
private val VIEW_USER = TelegramUserId(42L)
private val VIEW_CHAT = TelegramChatId(4_200L)
private val VIEW_TRAINING =
    SavedTraining(
        TrainingId(UUID.fromString("00000000-0000-0000-0000-000000000097")),
        TrainingDetails(
            LocalDate.of(2026, 10, 12),
            LocalTime.of(6, 30),
            listOf(TechniqueDraft("Armbar", "Рычаг локтя")),
            note = "Из маунта",
        ),
    )

internal class TrainingViewLifecycleTest {
    private val repository =
        FakeTrainingRepository().apply {
            findLatestResults[VIEW_USER] = Result.success(VIEW_TRAINING)
        }
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingMessageSessionStorage()
    private val manager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))
    private val telegramBot =
        TelegramBot(TelegramProperties("test-token"), storage, manager, messages, TrainingQueryService(repository))

    @Test
    fun `first view has an HTML card and a working finish button without a domain flow`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val card = view(bot)
                assertEquals(listOf(listOf(FINISH_VIEW)), card.inlineKeyboard.map { row -> row.map { it.text } })
                assertEquals("HTML", card.parseMode)
                assertTrue(card.text.startsWith("Последняя тренировка\n\n<blockquote expandable>"))
                assertTrue(card.text.contains("1. Armbar\n   Описание: Рычаг локтя"))
                assertTrue(card.text.endsWith("Заметка: Из маунта</blockquote>"))
                val data =
                    card.inlineKeyboard
                        .single()
                        .single()
                        .callbackData
                assertTrue(data.isNotBlank())
                assertTrue(data.toByteArray(Charsets.UTF_8).size <= 64)
                assertNull(storage.get(VIEW_USER))
                assertNull(messages.get(VIEW_USER))
                val result = finishView(bot, card)
                assertTrue(result.acknowledged)
                assertTrue(result.replies.isEmpty())
                assertEquals(
                    listOf(
                        TelegramDeleteAttempt(VIEW_CHAT, TelegramMessageId(2_713L), true),
                        TelegramDeleteAttempt(VIEW_CHAT, card.messageId, true),
                    ),
                    bot.deleteMessageAttempts,
                )
                assertEquals(1, bot.answerCallbackQueryRequests.size)
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `repeated view edits the existing message with fresh repository data`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val first = view(bot)
                repository.findLatestResults[VIEW_USER] =
                    Result.success(
                        VIEW_TRAINING.copy(details = VIEW_TRAINING.details.copy(note = "Актуальная заметка <&>")),
                    )
                val replies = bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_714L)
                assertTrue(replies.isEmpty(), "Repeated viewing must edit the existing card instead of sending another")
                val updated = bot.editedMessages.single()
                assertEquals(first.messageId, updated.messageId)
                assertEquals(first.chatId, updated.chatId)
                assertEquals("HTML", updated.parseMode)
                assertTrue(updated.text.contains("Актуальная заметка &lt;&amp;&gt;"))
                assertEquals(listOf(FINISH_VIEW), updated.inlineKeyboard.flatten().map { it.text })
                assertEquals(1, bot.sendMessageRequests.size)
                assertEquals(listOf(VIEW_USER, VIEW_USER), repository.findLatestCalls)
                assertNull(storage.get(VIEW_USER))
            }
        }

    @Test
    fun `cancel closes the view and deletes the command without creating a domain session`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val card = view(bot)
                val before = bot.apiCalls.size
                val replies = bot.sendCommandAndCollectReplies("/cancel", messageId = 3_101L)
                assertTrue(replies.isEmpty(), "Closing a view must not send an extra message")
                assertEquals(listOf("deleteMessage", "deleteMessage"), bot.apiCalls.drop(before))
                assertEquals(
                    listOf(
                        DeletedTelegramMessage(VIEW_CHAT.value, 2_713L),
                        DeletedTelegramMessage(VIEW_CHAT.value, card.messageId.value),
                        DeletedTelegramMessage(VIEW_CHAT.value, 3_101L),
                    ),
                    bot.deletedMessages,
                )
                assertNull(storage.get(VIEW_USER))
                assertTrue(repository.saveCalls.isEmpty())
                val next = view(bot)
                assertNotEquals(card.messageId, next.messageId)
            }
        }

    @Test
    fun `cancel closes viewing before cancelling the unchanged active draft`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val question = startDraft(bot)
                val session = assertNotNull(storage.get(VIEW_USER))
                val registration = assertNotNull(messages.get(VIEW_USER))
                val token = messages.questionToken(VIEW_USER)
                val card = view(bot)
                assertNotEquals(question.messageId, card.messageId)
                val sends = bot.sendMessageRequests.size
                bot.sendCommandAndCollectReplies("/cancel", messageId = 3_101L)
                assertEquals(session, storage.get(VIEW_USER), "First cancel must only close viewing")
                assertEquals(registration, messages.get(VIEW_USER))
                assertEquals(token, messages.questionToken(VIEW_USER))
                assertEquals(question, bot.currentMessage(question.messageId))
                assertTrue(DeletedTelegramMessage(VIEW_CHAT.value, card.messageId.value) in bot.deletedMessages)
                bot.sendCommandAndCollectReplies("/cancel", messageId = 3_102L)
                assertNull(storage.get(VIEW_USER))
                assertNull(messages.get(VIEW_USER))
                assertNull(messages.questionToken(VIEW_USER))
                val cancelled = bot.currentMessage(question.messageId)
                assertEquals("Создание тренировки отменено.", cancelled.text)
                assertTrue(cancelled.inlineKeyboard.isEmpty())
                assertEquals(sends, bot.sendMessageRequests.size)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `inline training cancellation leaves the view and its finish button usable`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val question = startDraft(bot)
                val card = view(bot)
                val result = bot.pressInlineButtonAndCollectReplies(question, "Отменить тренировку")
                assertTrue(result.acknowledged)
                assertNull(storage.get(VIEW_USER))
                assertNull(messages.get(VIEW_USER))
                assertEquals(card, bot.currentMessage(card.messageId))
                assertTrue(bot.deleteMessageAttempts.none { it.messageId == card.messageId })
                val finished = finishView(bot, card)
                assertTrue(finished.acknowledged)
                assertEquals(TelegramDeleteAttempt(VIEW_CHAT, card.messageId, true), bot.deleteMessageAttempts.last())
                assertEquals(2, bot.answerCallbackQueryRequests.size)
                assertEquals(2, bot.sendMessageRequests.size)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @ParameterizedTest(name = "training add closes viewing with existing draft = {0}")
    @ValueSource(booleans = [false, true])
    fun `training add closes viewing and preserves any existing draft`(activeDraft: Boolean) =
        runBlocking<Unit> {
            createBot().use { bot ->
                if (activeDraft) startDraft(bot)
                val session = storage.get(VIEW_USER)
                val registration = messages.get(VIEW_USER)
                val token = messages.questionToken(VIEW_USER)
                val card = view(bot)
                val before = bot.apiCalls.size
                bot.sendCommandAndCollectReplies("/training_add", messageId = 3_201L)
                assertEquals(
                    "deleteMessage",
                    bot.apiCalls.drop(before).first(),
                    "Close viewing before starting creation",
                )
                assertTrue(DeletedTelegramMessage(VIEW_CHAT.value, card.messageId.value) in bot.deletedMessages)
                if (activeDraft) {
                    assertEquals(session, storage.get(VIEW_USER))
                    assertEquals(registration, messages.get(VIEW_USER))
                    assertEquals(token, messages.questionToken(VIEW_USER))
                } else {
                    assertEquals(WaitingDate, assertNotNull(storage.get(VIEW_USER)).state)
                    assertNotEquals(card.messageId, assertNotNull(messages.get(VIEW_USER)).messageId)
                }
                val callback = finishCallback(bot, card)
                val snapshot = storage.get(VIEW_USER)
                val operations = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(callback).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(operations))
                assertEquals(snapshot, storage.get(VIEW_USER))
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @ParameterizedTest(name = "removing keyboard also fails = {0}")
    @ValueSource(booleans = [false, true])
    fun `failed view deletion removes buttons when possible and always invalidates the callback`(markupFails: Boolean) =
        runBlocking<Unit> {
            createBot().use { bot ->
                val card = view(bot)
                val callback = finishCallback(bot, card)
                bot.failNextDeleteMessage("Bad Request: message can't be deleted")
                if (markupFails) bot.failNextEditMessageReplyMarkup("Bad Request: message can't be edited")
                val before = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(callback).acknowledged)
                assertEquals(
                    listOf("deleteMessage", "editMessageReplyMarkup", "answerCallbackQuery"),
                    bot.apiCalls.drop(before),
                )
                assertEquals(
                    listOf(
                        TelegramDeleteAttempt(VIEW_CHAT, TelegramMessageId(2_713L), true),
                        TelegramDeleteAttempt(VIEW_CHAT, card.messageId, false),
                    ),
                    bot.deleteMessageAttempts,
                )
                assertEquals(listOf(DeletedTelegramMessage(VIEW_CHAT.value, 2_713L)), bot.deletedMessages)
                assertEquals(
                    TelegramReplyMarkupEdit(VIEW_CHAT, card.messageId, emptyList(), !markupFails),
                    bot.replyMarkupEdits.single(),
                )
                val remaining = bot.currentMessage(card.messageId)
                assertEquals(card.text, remaining.text)
                assertEquals("HTML", remaining.parseMode)
                assertEquals(if (markupFails) card.inlineKeyboard else emptyList(), remaining.inlineKeyboard)
                val after = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(callback).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(after))
                val next = view(bot)
                assertNotEquals(card.messageId, next.messageId, "Closed viewing must not reuse its old registration")
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `repeated finish and old callback cannot close the next view`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val old = view(bot)
                val callback = finishCallback(bot, old)
                assertTrue(bot.deliverCallbackAndCollectReplies(callback).acknowledged)
                val before = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(callback).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
                val current = view(bot)
                assertNotEquals(old.messageId, current.messageId)
                val after = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(callback).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(after))
                assertEquals(current, bot.currentMessage(current.messageId))
                assertTrue(finishView(bot, current).acknowledged)
                assertEquals(
                    listOf(
                        TelegramDeleteAttempt(VIEW_CHAT, TelegramMessageId(2_713L), true),
                        TelegramDeleteAttempt(VIEW_CHAT, old.messageId, true),
                        TelegramDeleteAttempt(VIEW_CHAT, TelegramMessageId(2_713L), true),
                        TelegramDeleteAttempt(VIEW_CHAT, current.messageId, true),
                    ),
                    bot.deleteMessageAttempts,
                )
                assertEquals(4, bot.answerCallbackQueryRequests.size)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @ParameterizedTest
    @EnumSource(ForeignViewCallback::class)
    fun `foreign or malformed callback cannot close the current view`(kind: ForeignViewCallback) =
        runBlocking<Unit> {
            createBot().use { bot ->
                val card = view(bot)
                val original = finishCallback(bot, card)
                val message = assertNotNull(original.message)
                val foreign =
                    when (kind) {
                        ForeignViewCallback.USER -> {
                            original.copy(from = original.from.copy(id = 84L))
                        }

                        ForeignViewCallback.CHAT -> {
                            original.copy(message = message.copy(chat = message.chat.copy(id = 8_400L)))
                        }

                        ForeignViewCallback.MESSAGE -> {
                            original.copy(message = message.copy(messageId = card.messageId.value + 1))
                        }

                        ForeignViewCallback.TOKEN -> {
                            original.copy(data = original.data + "invalid")
                        }
                    }
                val before = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(foreign).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
                assertEquals(card, bot.currentMessage(card.messageId))
                assertEquals(
                    listOf(TelegramDeleteAttempt(VIEW_CHAT, TelegramMessageId(2_713L), true)),
                    bot.deleteMessageAttempts,
                )
                assertTrue(bot.deliverCallbackAndCollectReplies(original).acknowledged)
                assertEquals(
                    listOf(
                        TelegramDeleteAttempt(VIEW_CHAT, TelegramMessageId(2_713L), true),
                        TelegramDeleteAttempt(VIEW_CHAT, card.messageId, true),
                    ),
                    bot.deleteMessageAttempts,
                )
                assertEquals(2, bot.answerCallbackQueryRequests.size)
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `users have independent views and can each close their own card`() =
        runBlocking<Unit> {
            val other = TelegramUserId(84L)
            val otherChat = TelegramChatId(8_400L)
            repository.findLatestResults[other] =
                Result.success(
                    VIEW_TRAINING.copy(details = VIEW_TRAINING.details.copy(note = "Другой пользователь")),
                )
            createBot().use { firstBot ->
                createBot(otherChat, firstMessageId = 2_000L).use { secondBot ->
                    val first = view(firstBot)
                    val second = view(secondBot, other)
                    assertTrue(finishView(firstBot, first).acknowledged)
                    assertEquals(
                        listOf(TelegramDeleteAttempt(otherChat, TelegramMessageId(2_713L), true)),
                        secondBot.deleteMessageAttempts,
                    )
                    assertEquals(second, secondBot.currentMessage(second.messageId))
                    val closed = finishView(secondBot, second, other)
                    assertTrue(closed.acknowledged)
                    assertEquals(
                        listOf(
                            TelegramDeleteAttempt(otherChat, TelegramMessageId(2_713L), true),
                            TelegramDeleteAttempt(otherChat, second.messageId, true),
                        ),
                        secondBot.deleteMessageAttempts,
                    )
                    assertEquals(listOf(VIEW_USER, other), repository.findLatestCalls)
                    assertNull(storage.get(VIEW_USER))
                    assertNull(storage.get(other))
                    assertTrue(repository.saveCalls.isEmpty())
                }
            }
        }

    private fun finishCallback(
        bot: TestTelegramBot,
        card: EditedTelegramMessage,
    ): CallbackQuery {
        assertEquals(listOf(FINISH_VIEW), card.inlineKeyboard.flatten().map { it.text }, "Finish button is missing")
        return bot.captureCallback(card, FINISH_VIEW)
    }

    private suspend fun finishView(
        bot: TestTelegramBot,
        card: EditedTelegramMessage,
        user: TelegramUserId = VIEW_USER,
    ): CallbackInteraction {
        assertEquals(listOf(FINISH_VIEW), card.inlineKeyboard.flatten().map { it.text }, "Finish button is missing")
        return bot.pressInlineButtonAndCollectReplies(card, FINISH_VIEW, user.value)
    }

    private suspend fun view(
        bot: TestTelegramBot,
        user: TelegramUserId = VIEW_USER,
    ): EditedTelegramMessage {
        val sent = bot.sendCommand("/training_get_last", user.value, 2_713L)
        return bot.currentMessage(TelegramMessageId(sent.messageId))
    }

    private suspend fun startDraft(bot: TestTelegramBot): EditedTelegramMessage {
        bot.sendCommand("/training_add", VIEW_USER.value, 713L)
        bot.sendTextAndCollectReplies("14.10.2026", VIEW_USER.value, 829L)
        bot.sendTextAndCollectReplies("7:45", VIEW_USER.value, 947L)
        val session = assertNotNull(storage.get(VIEW_USER))
        assertEquals(
            WaitingAddTechnique(TrainingDetails(LocalDate.of(2026, 10, 14), LocalTime.of(7, 45))),
            session.state,
        )
        return bot.currentMessage(assertNotNull(messages.get(VIEW_USER)).messageId)
    }

    private fun createBot(
        chat: TelegramChatId = VIEW_CHAT,
        firstMessageId: Long = 1_000L,
    ) = TestTelegramBot(
        telegramBot,
        chatId = chat.value,
        firstBotMessageId = firstMessageId,
    )

    internal enum class ForeignViewCallback { USER, CHAT, MESSAGE, TOKEN }
}
