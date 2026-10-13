package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val INITIAL_DATE_PROMPT =
    """
    Введите дату тренировки.

    Например: 11.10.2026 или 2026-10-11.

    Можно использовать разделители: точку, дефис или косую черту.
    """.trimIndent()
private val RECOVERED_TIME_PROMPT =
    """
    Введите время начала тренировки.

    Например: 8:00, 19:30 или 8-00.
    """.trimIndent()

internal class TrainingInitialQuestionRecoveryTest {
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val firstCommandId = TelegramMessageId(713L)
    private val repeatCommandId = TelegramMessageId(829L)
    private val dateMessageId = TelegramMessageId(947L)
    private val apiMessageId = TelegramMessageId(6_341L)
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val manager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))

    @Test
    fun `failed initial send preserves the date session without registering or deleting anything`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                failFirstSend(bot)
            }
        }

    @Test
    fun `repeated command recovers the same session registers the API message and allows date input`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val before = failFirstSend(bot)
                val callCount = bot.apiCalls.size

                val replies = bot.sendCommandAndCollectReplies("/training_add", userId.value, repeatCommandId.value)

                assertAll(
                    { assertEquals(before, storage.get(userId)) },
                    { assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls.drop(callCount)) },
                    {
                        assertEquals(
                            listOf(DeletedTelegramMessage(chatId.value, repeatCommandId.value)),
                            bot.deletedMessages,
                        )
                    },
                    { assertEquals(1, replies.size) },
                    { assertEquals(INITIAL_DATE_PROMPT, replies.single().text) },
                    { assertEquals(apiMessageId.value, replies.single().messageId) },
                    {
                        assertEquals(
                            TrainingMessageSession(before.sessionId, chatId, apiMessageId),
                            messages.get(userId),
                        )
                    },
                    {
                        val token = assertNotNull(messages.questionToken(userId))
                        assertEquals(
                            listOf(listOf(EditedTelegramInlineButton("Отменить тренировку", "training:cancel:$token"))),
                            bot.currentMessage(apiMessageId).inlineKeyboard,
                        )
                        assertEquals(
                            trainingQuestion(chatId, apiMessageId, INITIAL_DATE_PROMPT),
                            bot.currentMessage(apiMessageId).withoutQuestionToken(),
                        )
                    },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
                val registered = assertNotNull(messages.get(userId))
                val editCount = bot.editedMessages.size
                val callsBeforeDate = bot.apiCalls.size

                val dateReplies = bot.sendTextAndCollectReplies("12.10.2026", userId.value, dateMessageId.value)

                assertEquals(
                    before.copy(state = WaitingStartTime(LocalDate.of(2026, 10, 12))),
                    storage.get(userId),
                )
                assertEquals(registered, messages.get(userId))
                assertTrue(dateReplies.isEmpty())
                assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(callsBeforeDate))
                assertEquals(
                    listOf(trainingQuestion(chatId, apiMessageId, RECOVERED_TIME_PROMPT)),
                    bot.editedMessages.drop(editCount).map { it.withoutQuestionToken() },
                )
                assertEquals(
                    listOf(repeatCommandId, dateMessageId).map { DeletedTelegramMessage(chatId.value, it.value) },
                    bot.deletedMessages,
                )
                assertEquals(2, bot.apiCalls.count { it == "sendMessage" })
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `another failed send retries the date question and preserves the original unregistered session`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                val before = failFirstSend(bot)
                bot.failNextSendMessage("Bad Request: chat not found")
                val callCount = bot.apiCalls.size

                val replies = bot.sendCommandAndCollectReplies("/training_add", userId.value, repeatCommandId.value)

                assertAll(
                    { assertEquals(before, storage.get(userId)) },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertTrue(replies.isEmpty()) },
                    { assertTrue(bot.deletedMessages.isEmpty()) },
                    { assertTrue(bot.editedMessages.isEmpty()) },
                    { assertEquals(listOf("sendMessage"), bot.apiCalls.drop(callCount)) },
                    {
                        assertEquals(
                            listOf(INITIAL_DATE_PROMPT, INITIAL_DATE_PROMPT),
                            bot.sendMessageRequests.map { it.text },
                        )
                    },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
            }
        }

    private suspend fun failFirstSend(bot: TestTelegramBot): UserSession {
        bot.failNextSendMessage("Bad Request: chat not found")

        val replies = bot.sendCommandAndCollectReplies("/training_add", userId.value, firstCommandId.value)

        val session = assertNotNull(storage.get(userId))
        assertEquals(WaitingDate, session.state)
        assertNull(messages.get(userId))
        assertNull(messages.questionToken(userId))
        assertTrue(replies.isEmpty())
        assertTrue(bot.deletedMessages.isEmpty())
        assertTrue(bot.editedMessages.isEmpty())
        assertEquals(listOf("sendMessage"), bot.apiCalls)
        val request = bot.sendMessageRequests.single()
        assertEquals(chatId, request.chatId)
        assertEquals(INITIAL_DATE_PROMPT, request.text)
        val button = request.inlineKeyboard.single().single()
        assertEquals("Отменить тренировку", button.text)
        assertTrue(button.callbackData.startsWith("training:cancel:"))
        assertTrue(repository.saveCalls.isEmpty())
        return session
    }

    private fun createBot() =
        TestTelegramBot(
            TelegramBot(TelegramProperties("test-token"), storage, manager, messages),
            chatId = chatId.value,
            firstBotMessageId = apiMessageId.value,
        )
}
