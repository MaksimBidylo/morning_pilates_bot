package morningpilates.telegram.command

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.SentTelegramMessage
import morningpilates.telegram.support.TelegramDeleteAttempt
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.SavedTraining
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingId
import morningpilates.training.TrainingQueryService
import morningpilates.training.populatedTrainingDetails
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val RESET_USER = TelegramUserId(42L)
private val RESET_CHAT = TelegramChatId(4_200L)
private const val RESET_COMMAND_ID = 9_000L
private const val FINISH_RESET_VIEW = "Завершить просмотр"

internal class CancelAllCommandTest {
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()
    private val saved = SavedTraining(TrainingId(UUID.randomUUID()), populatedTrainingDetails())
    private val repository =
        FakeTrainingRepository().apply {
            findLatestResults[RESET_USER] = Result.success(saved)
        }
    private val manager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))
    private val telegramBot =
        TelegramBot(
            TelegramProperties("test-token"),
            storage,
            manager,
            messages,
            TrainingQueryService(repository),
            deleteTraining = repository::delete,
        )

    @ParameterizedTest
    @EnumSource(Context::class)
    fun `reset clears every active context and sends a fresh confirmation`(context: Context) =
        runBlocking<Unit> {
            createBot().use { bot ->
                val active = open(bot, context)
                val edits = bot.editedMessages.size
                val replies = bot.sendCommandAndCollectReplies("/cancel_all", messageId = RESET_COMMAND_ID)

                assertAll(
                    { assertCleared() },
                    { assertConfirmation(replies, active) },
                    { assertEquals(edits, bot.editedMessages.size, "Confirmation must not edit the old question") },
                    {
                        active.forEach {
                            assertTrue(
                                TelegramDeleteAttempt(RESET_CHAT, it.messageId, true) in bot.deleteMessageAttempts,
                            )
                        }
                    },
                    { assertSavedUnchanged() },
                )
                assertOldButtonsInert(bot, active)
                assertFreshView(bot, active)
            }
        }

    @ParameterizedTest
    @EnumSource(ActiveContext::class)
    fun `failed deletion falls back to removing buttons`(context: ActiveContext) =
        runBlocking<Unit> {
            createBot().use { bot ->
                val active = open(bot, context.context).single()
                val before = bot.replyMarkupEdits.size
                bot.failNextDeleteMessage("Bad Request: message can't be deleted")

                val replies = bot.sendCommandAndCollectReplies("/cancel_all", messageId = RESET_COMMAND_ID)

                assertAll(
                    { assertCleared() },
                    { assertConfirmation(replies, listOf(active)) },
                    {
                        assertTrue(
                            TelegramDeleteAttempt(RESET_CHAT, active.messageId, false) in bot.deleteMessageAttempts,
                        )
                        assertTrue(bot.replyMarkupEdits.drop(before).any { it.messageId == active.messageId })
                        assertTrue(bot.currentMessage(active.messageId).inlineKeyboard.isEmpty())
                    },
                )
                assertFreshView(bot, listOf(active))
            }
        }

    @ParameterizedTest
    @EnumSource(ActiveContext::class)
    fun `state and authorization clear even when both Telegram cleanup operations fail`(context: ActiveContext) =
        runBlocking<Unit> {
            createBot().use { bot ->
                val active = open(bot, context.context).single()
                val old = captureButtons(bot, listOf(active))
                bot.failNextDeleteMessage("Bad Request: message can't be deleted")
                bot.failNextEditMessageReplyMarkup("Bad Request: message can't be edited")

                val replies = bot.sendCommandAndCollectReplies("/cancel_all", messageId = RESET_COMMAND_ID)

                assertAll(
                    { assertCleared() },
                    { assertConfirmation(replies, listOf(active)) },
                    {
                        assertTrue(
                            TelegramDeleteAttempt(RESET_CHAT, active.messageId, false) in bot.deleteMessageAttempts,
                        )
                        assertTrue(bot.replyMarkupEdits.any { it.messageId == active.messageId })
                    },
                    { runBlocking { assertCallbacksInert(bot, old) } },
                    { assertSavedUnchanged() },
                )
                assertFreshView(bot, listOf(active))
            }
        }

    @Test
    fun `cleanup failure in one context does not prevent cleanup of the other`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val active = open(bot, Context.BOTH)
                val old = captureButtons(bot, active)
                val before = bot.deleteMessageAttempts.size
                bot.failNextDeleteMessage("Bad Request: message can't be deleted")
                bot.failNextEditMessageReplyMarkup("Bad Request: message can't be edited")

                val replies = bot.sendCommandAndCollectReplies("/cancel_all", messageId = RESET_COMMAND_ID)

                assertAll(
                    { assertCleared() },
                    { assertConfirmation(replies, active) },
                    {
                        val activeIds = active.map { it.messageId }
                        val attempts = bot.deleteMessageAttempts.drop(before).filter { it.messageId in activeIds }
                        assertEquals(active.map { it.messageId }.toSet(), attempts.map { it.messageId }.toSet())
                        assertEquals(1, attempts.count { !it.succeeded })
                        assertEquals(1, attempts.count { it.succeeded })
                    },
                    { runBlocking { assertCallbacksInert(bot, old) } },
                )
                assertFreshView(bot, active)
            }
        }

    @Test
    fun `repeated reset permits new actions and old callbacks cannot affect them`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val active = open(bot, Context.BOTH)
                val previous = assertNotNull(storage.get(RESET_USER))
                val old = captureButtons(bot, active)
                val first = bot.sendCommandAndCollectReplies("/cancel_all")
                val second = bot.sendCommandAndCollectReplies("/cancel_all")
                // Check stale callback protection independently of confirmation.
                bot.sendCommand("/training_add")
                val next = assertNotNull(storage.get(RESET_USER))
                val registration = assertNotNull(messages.get(RESET_USER))
                val token = messages.questionToken(RESET_USER)
                val card = bot.sendCommandAndCollectReplies("/training_get_last")

                assertAll(
                    { assertConfirmation(first, active) },
                    { assertConfirmation(second, active) },
                    { assertNotEquals(first.map { it.messageId }, second.map { it.messageId }) },
                    { assertNotEquals(previous.sessionId, next.sessionId) },
                    { assertEquals(WaitingDate, next.state) },
                    { assertNotEquals(active.first().messageId, registration.messageId) },
                    { assertConfirmation(card, active) },
                    {
                        runBlocking {
                            assertCallbacksInert(bot, old)
                            assertEquals(next, storage.get(RESET_USER))
                            assertEquals(registration, messages.get(RESET_USER))
                            assertEquals(token, messages.questionToken(RESET_USER))
                            val view = bot.currentMessage(TelegramMessageId(card.single().messageId))
                            assertTrue(bot.pressInlineButtonAndCollectReplies(view, FINISH_RESET_VIEW).acknowledged)
                            assertTrue(
                                TelegramDeleteAttempt(RESET_CHAT, view.messageId, true) in bot.deleteMessageAttempts,
                            )
                        }
                    },
                    { assertSavedUnchanged() },
                )
            }
        }

    @Test
    fun `reset affects only its sender`() =
        runBlocking<Unit> {
            val other = TelegramUserId(84L)
            repository.findLatestResults[other] = Result.success(saved)
            createBot().use { bot ->
                open(bot, Context.BOTH)
                val foreign = open(bot, Context.BOTH, other)
                val session = storage.get(other)
                val registration = messages.get(other)
                val token = messages.questionToken(other)
                bot.sendCommandAndCollectReplies("/cancel_all")

                assertAll(
                    { assertCleared() },
                    { assertEquals(session, storage.get(other)) },
                    { assertEquals(registration, messages.get(other)) },
                    { assertEquals(token, messages.questionToken(other)) },
                    {
                        foreign.forEach { message ->
                            assertFalse(bot.deleteMessageAttempts.any { it.messageId == message.messageId })
                            assertEquals(message, bot.currentMessage(message.messageId))
                        }
                        runBlocking {
                            val finished =
                                bot.pressInlineButtonAndCollectReplies(
                                    foreign.last(),
                                    FINISH_RESET_VIEW,
                                    other.value,
                                )
                            assertTrue(finished.acknowledged)
                        }
                        val deletion = TelegramDeleteAttempt(RESET_CHAT, foreign.last().messageId, true)
                        assertTrue(deletion in bot.deleteMessageAttempts)
                    },
                    { assertSavedUnchanged() },
                )
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["group", "supergroup"])
    fun `group command is ignored and personal actions remain usable`(chatType: String) =
        runBlocking<Unit> {
            createBot().use { personal ->
                val active = open(personal, Context.BOTH)
                val session = storage.get(RESET_USER)
                val registration = messages.get(RESET_USER)
                val token = messages.questionToken(RESET_USER)
                TestTelegramBot(telegramBot, chatId = -100L, chatType = chatType).use { group ->
                    assertTrue(group.sendCommandAndCollectReplies("/cancel_all").isEmpty())
                    assertTrue(group.apiCalls.isEmpty())
                }
                assertEquals(session, storage.get(RESET_USER))
                assertEquals(registration, messages.get(RESET_USER))
                assertEquals(token, messages.questionToken(RESET_USER))
                assertTrue(personal.pressInlineButtonAndCollectReplies(active.last(), FINISH_RESET_VIEW).acknowledged)
                val cancelled = personal.pressInlineButtonAndCollectReplies(active.first(), "Отменить тренировку")
                assertTrue(cancelled.acknowledged)
                assertCleared()
                assertSavedUnchanged()
            }
        }

    private suspend fun open(
        bot: TestTelegramBot,
        context: Context,
        user: TelegramUserId = RESET_USER,
    ): List<EditedTelegramMessage> =
        buildList {
            if (context == Context.CREATION || context == Context.BOTH) {
                val sent = bot.sendCommand("/training_add", user.value)
                add(bot.currentMessage(TelegramMessageId(sent.messageId)))
            }
            if (context == Context.VIEW || context == Context.BOTH) {
                val sent = bot.sendCommand("/training_get_last", user.value)
                add(bot.currentMessage(TelegramMessageId(sent.messageId)))
            }
        }

    private fun assertCleared() {
        assertAll(
            { assertNull(storage.get(RESET_USER), "Domain session must be cleared") },
            { assertNull(messages.get(RESET_USER), "Creation message registration must be cleared") },
            { assertNull(messages.questionToken(RESET_USER), "Creation callback authorization must be cleared") },
        )
    }

    private fun assertConfirmation(
        replies: List<SentTelegramMessage>,
        old: List<EditedTelegramMessage>,
    ) {
        assertEquals(1, replies.size, "A new confirmation must be sent even if the previous question is hidden")
        val confirmation = replies.single()
        assertEquals(RESET_CHAT.value, confirmation.chatId)
        assertTrue(confirmation.text.isNotBlank())
        assertTrue(old.none { it.messageId.value == confirmation.messageId })
    }

    private fun captureButtons(
        bot: TestTelegramBot,
        active: List<EditedTelegramMessage>,
    ): List<CallbackQuery> =
        active.map { message ->
            bot.captureCallback(
                message,
                message.inlineKeyboard
                    .flatten()
                    .first()
                    .text,
            )
        }

    private suspend fun assertOldButtonsInert(
        bot: TestTelegramBot,
        active: List<EditedTelegramMessage>,
    ) = assertCallbacksInert(bot, captureButtons(bot, active))

    private suspend fun assertCallbacksInert(
        bot: TestTelegramBot,
        callbacks: List<CallbackQuery>,
    ) {
        val reads = repository.findLatestCalls.toList()
        callbacks.forEach { callback ->
            val before = bot.apiCalls.size
            val result = bot.deliverCallbackAndCollectReplies(callback)
            assertTrue(result.acknowledged)
            assertTrue(result.replies.isEmpty())
            assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
        }
        assertEquals(reads, repository.findLatestCalls)
        assertSavedUnchanged()
    }

    private suspend fun assertFreshView(
        bot: TestTelegramBot,
        old: List<EditedTelegramMessage>,
    ) {
        val replies = bot.sendCommandAndCollectReplies("/training_get_last")
        assertConfirmation(replies, old)
        assertTrue(replies.single().text.contains("Armbar"))
    }

    private fun assertSavedUnchanged() {
        assertEquals(Result.success(saved), repository.findLatestResults[RESET_USER])
        assertTrue(repository.saveCalls.isEmpty())
    }

    private fun createBot() = TestTelegramBot(telegramBot, chatId = RESET_CHAT.value)

    internal enum class Context { CREATION, VIEW, BOTH, NONE }

    internal enum class ActiveContext(
        val context: Context,
    ) {
        CREATION(Context.CREATION),
        VIEW(Context.VIEW),
    }
}
