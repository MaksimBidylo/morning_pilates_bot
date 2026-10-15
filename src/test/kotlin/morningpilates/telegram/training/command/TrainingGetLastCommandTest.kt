package morningpilates.telegram.training.command

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.EditedTelegramInlineButton
import morningpilates.telegram.support.SentTelegramMessage
import morningpilates.telegram.support.TelegramDeleteAttempt
import morningpilates.telegram.support.TelegramSendMessageRequest
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.SavedTraining
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingId
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
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

private val LAST_DETAILS =
    TrainingDetails(
        LocalDate.of(2026, 10, 12),
        LocalTime.of(6, 30),
        listOf(TechniqueDraft("<b>Armbar & контроль</b>", "Рычаг <локтя> & маунт"), TechniqueDraft("Triangle")),
        "<script>заметка</script> & детали",
    )
private val LAST_TRAINING =
    SavedTraining(TrainingId(UUID.fromString("00000000-0000-0000-0000-000000000095")), LAST_DETAILS)
private val LAST_CARD_HTML =
    """
    Последняя тренировка

    <blockquote expandable>Дата: 12.10.2026
    Время: 06:30

    Техники:
    1. &lt;b&gt;Armbar &amp; контроль&lt;/b&gt;
       Описание: Рычаг &lt;локтя&gt; &amp; маунт
    2. Triangle

    Заметка: &lt;script&gt;заметка&lt;/script&gt; &amp; детали</blockquote>
    """.trimIndent()
private val EMPTY_DIARY_TEXT =
    """
    Тренировок пока нет.

    Запиши свою первую тренировку с помощью команды /training_add.
    """.trimIndent()

internal class TrainingGetLastCommandTest {
    private val userId = TelegramUserId(42L)
    private val otherUserId = TelegramUserId(84L)
    private val chatId = TelegramChatId(4_200L)
    private val commandId = TelegramMessageId(2_713L)
    private val repository = FakeTrainingRepository()
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()
    private val manager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))

    @Test
    fun `latest training is sent as one HTML card with escaped user data and a finish button`() =
        runBlocking<Unit> {
            repository.findLatestResults[userId] = Result.success(LAST_TRAINING)
            createBot().use { bot ->
                val replies = bot.sendCommandAndCollectReplies("/training_get_last", userId.value, commandId.value)

                assertAll(
                    {
                        assertEquals(
                            listOf(
                                SentTelegramMessage(
                                    chatId.value,
                                    LAST_CARD_HTML,
                                    listOf("Удалить тренировку", "Завершить просмотр"),
                                ),
                            ),
                            replies,
                        )
                    },
                    {
                        assertEquals(
                            listOf(
                                TelegramSendMessageRequest(
                                    chatId,
                                    LAST_CARD_HTML,
                                    finishKeyboard(bot.sendMessageRequests.last()),
                                    parseMode = "HTML",
                                ),
                            ),
                            bot.sendMessageRequests,
                        )
                    },
                    { assertEquals(listOf(userId), repository.findLatestCalls) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                    { assertNull(storage.get(userId)) },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertTrue(bot.editedMessages.isEmpty()) },
                )
            }
        }

    @Test
    fun `empty diary sends the agreed message without buttons or a domain session`() =
        runBlocking<Unit> {
            repository.findLatestResults[userId] = Result.success(null)
            createBot().use { bot ->
                val replies = bot.sendCommandAndCollectReplies("/training_get_last", userId.value, commandId.value)

                assertAll(
                    { assertEquals(listOf(SentTelegramMessage(chatId.value, EMPTY_DIARY_TEXT)), replies) },
                    {
                        assertEquals(
                            listOf(TelegramSendMessageRequest(chatId, EMPTY_DIARY_TEXT, emptyList())),
                            bot.sendMessageRequests,
                        )
                    },
                    { assertEquals(listOf(userId), repository.findLatestCalls) },
                    { assertNull(storage.get(userId)) },
                    { assertNull(messages.get(userId)) },
                    { assertNull(messages.questionToken(userId)) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                    { assertTrue(bot.editedMessages.isEmpty()) },
                )
                val cancelReplies = bot.sendCommandAndCollectReplies("/cancel", userId.value, 2_714L)
                assertTrue(cancelReplies.isEmpty())
                val emptyDiaryMessageId = TelegramMessageId(replies.single().messageId)
                assertEquals(
                    listOf(
                        TelegramDeleteAttempt(chatId, commandId, true),
                        TelegramDeleteAttempt(chatId, emptyDiaryMessageId, true),
                        TelegramDeleteAttempt(chatId, TelegramMessageId(2_714L), true),
                    ),
                    bot.deleteMessageAttempts,
                )
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(bot.replyMarkupEdits.isEmpty())
            }
        }

    @Test
    fun `viewing latest training preserves the active draft question keyboard and usable callback`() =
        runBlocking<Unit> {
            repository.findLatestResults[userId] = Result.success(LAST_TRAINING)
            createBot().use { bot ->
                bot.sendCommand("/training_add", userId.value, 713L)
                bot.sendTextAndCollectReplies("14.10.2026", userId.value, 829L)
                bot.sendTextAndCollectReplies("7:45", userId.value, 947L)
                val session = assertNotNull(storage.get(userId))
                val draftDetails = TrainingDetails(LocalDate.of(2026, 10, 14), LocalTime.of(7, 45))
                assertEquals(WaitingAddTechnique(draftDetails), session.state)
                val registered = assertNotNull(messages.get(userId))
                val question = bot.currentMessage(registered.messageId)
                val token = assertNotNull(messages.questionToken(userId))
                val beforeSends = bot.sendMessageRequests.size
                val beforeEdits = bot.editedMessages

                val replies = bot.sendCommandAndCollectReplies("/training_get_last", userId.value, commandId.value)

                assertAll(
                    {
                        assertEquals(
                            listOf(
                                SentTelegramMessage(
                                    chatId.value,
                                    LAST_CARD_HTML,
                                    listOf("Удалить тренировку", "Завершить просмотр"),
                                ),
                            ),
                            replies,
                        )
                    },
                    { assertEquals(listOf(userId), repository.findLatestCalls) },
                    {
                        assertEquals(
                            listOf(
                                TelegramSendMessageRequest(
                                    chatId,
                                    LAST_CARD_HTML,
                                    finishKeyboard(bot.sendMessageRequests.last()),
                                    parseMode = "HTML",
                                ),
                            ),
                            bot.sendMessageRequests.drop(beforeSends),
                        )
                    },
                    { assertEquals(session, storage.get(userId)) },
                    { assertEquals(registered, messages.get(userId)) },
                    { assertEquals(token, messages.questionToken(userId)) },
                    { assertEquals(question, bot.currentMessage(registered.messageId)) },
                    { assertEquals(beforeEdits, bot.editedMessages) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
                assertNotEquals(registered.messageId, TelegramMessageId(replies.single().messageId))
                val continued = bot.pressInlineButtonAndCollectReplies(question, "Да", userId.value)
                assertTrue(continued.acknowledged)
                assertTrue(continued.replies.isEmpty())
                assertEquals(session.copy(state = WaitingTechniqueName(draftDetails)), storage.get(userId))
                assertEquals(registered, messages.get(userId))
                assertEquals("🏷️ Введите название техники.", bot.currentMessage(registered.messageId).text)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @ParameterizedTest(name = "latest training lookup uses caller {0}")
    @ValueSource(longs = [42L, 84L])
    fun `lookup uses the command sender and never displays another users training`(senderId: Long) =
        runBlocking<Unit> {
            val caller = TelegramUserId(senderId)
            val otherTraining =
                SavedTraining(
                    TrainingId(UUID.fromString("00000000-0000-0000-0000-000000000096")),
                    TrainingDetails(LocalDate.of(2026, 10, 10), LocalTime.of(8, 0), note = "Данные пользователя B"),
                )
            repository.findLatestResults[userId] = Result.success(LAST_TRAINING)
            repository.findLatestResults[otherUserId] = Result.success(otherTraining)
            val expectedText =
                if (caller == userId) {
                    LAST_CARD_HTML
                } else {
                    "Последняя тренировка\n\n<blockquote expandable>Дата: 10.10.2026\n" +
                        "Время: 08:00\n\nЗаметка: Данные пользователя B</blockquote>"
                }
            val callerChat = TelegramChatId(senderId * 100)
            createBot(callerChat).use { bot ->
                val replies = bot.sendCommandAndCollectReplies("/training_get_last", caller.value, commandId.value)

                assertAll(
                    { assertEquals(listOf(caller), repository.findLatestCalls) },
                    {
                        assertEquals(
                            listOf(
                                SentTelegramMessage(
                                    callerChat.value,
                                    expectedText,
                                    listOf("Удалить тренировку", "Завершить просмотр"),
                                ),
                            ),
                            replies,
                        )
                    },
                    {
                        assertEquals(
                            listOf(
                                TelegramSendMessageRequest(
                                    callerChat,
                                    expectedText,
                                    finishKeyboard(bot.sendMessageRequests.single()),
                                    parseMode = "HTML",
                                ),
                            ),
                            bot.sendMessageRequests,
                        )
                    },
                    { assertNull(storage.get(userId)) },
                    { assertNull(storage.get(otherUserId)) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
            }
        }

    @ParameterizedTest(name = "group command is ignored with active training = {0}")
    @ValueSource(booleans = [false, true])
    fun `group command never reads training or creates or changes a session`(activeTraining: Boolean) =
        runBlocking<Unit> {
            repository.findLatestResults[userId] = Result.success(LAST_TRAINING)
            if (activeTraining) {
                createBot().use { bot ->
                    bot.sendCommand("/training_add", userId.value, 713L)
                    bot.sendTextAndCollectReplies("14.10.2026", userId.value, 829L)
                    bot.sendTextAndCollectReplies("7:45", userId.value, 947L)
                }
            }
            val session = storage.get(userId)
            val registered = messages.get(userId)
            val token = messages.questionToken(userId)
            createBot(TelegramChatId(-8_400L), chatType = "group").use { bot ->
                val replies = bot.sendCommandAndCollectReplies("/training_get_last", userId.value, commandId.value)

                assertAll(
                    { assertTrue(repository.findLatestCalls.isEmpty()) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                    { assertTrue(replies.isEmpty()) },
                    { assertTrue(bot.sendMessageRequests.isEmpty()) },
                    { assertTrue(bot.editedMessages.isEmpty()) },
                    { assertEquals(session, storage.get(userId)) },
                    { assertEquals(registered, messages.get(userId)) },
                    { assertEquals(token, messages.questionToken(userId)) },
                )
            }
        }

    private fun finishKeyboard(request: TelegramSendMessageRequest): List<List<EditedTelegramInlineButton>> {
        val callbackData =
            request.inlineKeyboard
                .single()
                .single { it.text == "Завершить просмотр" }
                .callbackData
        assertTrue(callbackData.startsWith("view:finish:"))
        val token = callbackData.removePrefix("view:finish:")
        assertTrue(token.substringBefore('.').matches(Regex("[0-9a-f]{8}")))
        UUID.fromString(token.substringAfter('.'))
        assertTrue(callbackData.toByteArray(Charsets.UTF_8).size <= 64)
        val deleteData =
            request.inlineKeyboard
                .single()
                .single { it.text == "Удалить тренировку" }
                .callbackData
        assertEquals("view:delete:$token", deleteData)
        return listOf(
            listOf(
                EditedTelegramInlineButton("Удалить тренировку", deleteData),
                EditedTelegramInlineButton("Завершить просмотр", callbackData),
            ),
        )
    }

    private fun createBot(
        targetChat: TelegramChatId = chatId,
        chatType: String = "private",
    ) = TestTelegramBot(
        TelegramBot(
            TelegramProperties("test-token"),
            storage,
            manager,
            messages,
            queryService = TrainingQueryService(repository),
            deleteTraining = repository::delete,
        ),
        chatId = targetChat.value,
        chatType = chatType,
    )
}
