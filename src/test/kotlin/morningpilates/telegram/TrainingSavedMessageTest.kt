package morningpilates.telegram

import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SAVED_DATE = LocalDate.of(2026, 10, 13)
private val SAVED_TIME = LocalTime.of(6, 30)
private const val DATE_AND_TIME = "Дата: 13.10.2026\nВремя: 06:30"
private const val DESCRIPTION = "Рычаг локтя из маунта"
private const val NOTE = "Отрабатывали выход из сайд-контроля"
private val FULL_DETAILS =
    TrainingDetails(
        SAVED_DATE,
        SAVED_TIME,
        listOf(TechniqueDraft("Armbar", DESCRIPTION), TechniqueDraft("Triangle")),
        NOTE,
    )
private const val FULL_SUMMARY =
    "$DATE_AND_TIME\n\nТехники:\n1. Armbar\n   Описание: $DESCRIPTION\n2. Triangle\n\nЗаметка: $NOTE"

internal class TrainingSavedMessageTest {
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val manager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))

    @Test
    fun `saving two techniques and a note replaces the registered question with an HTML card`() =
        runBlocking<Unit> {
            realBot().use { bot ->
                bot.sendCommand("/training_add", userId.value, 713L)
                val initial = assertNotNull(storage.get(userId))
                val registered = assertNotNull(messages.get(userId))
                for (input in listOf(
                    "13.10.2026",
                    "6:30",
                    "Да",
                    "Armbar",
                    "Да",
                    DESCRIPTION,
                    "Да",
                    "Triangle",
                    "Нет",
                    "Нет",
                    "Да",
                    NOTE,
                )) {
                    if (input == "Да" || input == "Нет") {
                        val result = bot.pressInlineButtonAndCollectReplies(currentQuestion(bot), input, userId.value)
                        assertTrue(result.acknowledged)
                        assertTrue(result.replies.isEmpty())
                    } else {
                        assertTrue(bot.sendTextAndCollectReplies(input, userId.value).isEmpty())
                    }
                    assertEquals(initial.sessionId, storage.get(userId)?.sessionId)
                    assertEquals(initial.createdAt, storage.get(userId)?.createdAt)
                    assertEquals(registered, messages.get(userId))
                }
                assertEquals(initial.copy(state = WaitingConfirmation(FULL_DETAILS)), storage.get(userId))
                assertTrue(repository.saveCalls.isEmpty())

                assertSavedCard(bot, registered, FULL_DETAILS, FULL_SUMMARY)
            }
        }

    @ParameterizedTest(name = "saved card: {0}")
    @MethodSource("formattingCases")
    fun `saved card includes only present data and escapes user HTML`(
        caseName: String,
        details: TrainingDetails,
        summary: String,
    ) = runBlocking<Unit> {
        confirmationBot(details).use { bot ->
            assertEquals(WaitingConfirmation(details), storage.get(userId)?.state, caseName)
            assertSavedCard(bot, assertNotNull(messages.get(userId)), details, summary)
        }
    }

    @Test
    fun `duplicate confirmation is acknowledged without another save or Telegram mutation`() =
        runBlocking<Unit> {
            confirmationBot(FULL_DETAILS).use { bot ->
                val registered = assertNotNull(messages.get(userId))
                val callback = bot.captureCallback(currentQuestion(bot), "Да", userId)
                val first = bot.deliverCallbackAndCollectReplies(callback)
                assertTrue(first.acknowledged)
                assertEquals(1, repository.saveCalls.size)
                assertNull(storage.get(userId))
                val displayed = bot.currentMessage(registered.messageId)
                val edits = bot.editedMessages
                val deletions = bot.deletedMessages
                val registration = messages.get(userId)
                val token = messages.questionToken(userId)
                val calls = bot.apiCalls.size

                val duplicate = bot.deliverCallbackAndCollectReplies(callback)

                assertAll(
                    { assertTrue(duplicate.acknowledged) },
                    { assertTrue(duplicate.replies.isEmpty()) },
                    { assertEquals(1, repository.saveCalls.size) },
                    { assertNull(storage.get(userId)) },
                    { assertEquals(displayed, bot.currentMessage(registered.messageId)) },
                    { assertEquals(edits, bot.editedMessages) },
                    { assertEquals(deletions, bot.deletedMessages) },
                    { assertEquals(registration, messages.get(userId)) },
                    { assertEquals(token, messages.questionToken(userId)) },
                    { assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(calls)) },
                )
            }
        }

    private suspend fun assertSavedCard(
        bot: TestTelegramBot,
        registered: TrainingMessageSession,
        details: TrainingDetails,
        summary: String,
    ) {
        val editCount = bot.editedMessages.size
        val calls = bot.apiCalls.size
        val deletions = bot.deletedMessages
        val result = bot.pressInlineButtonAndCollectReplies(currentQuestion(bot), "Да", userId.value)
        val expected =
            EditedTelegramMessage(
                chatId,
                registered.messageId,
                "Тренировка сохранена.\n\n<blockquote expandable>$summary</blockquote>",
                parseMode = "HTML",
            )
        // Saving completes synchronously and removes the domain session before rendering the card.
        assertEquals(1, repository.saveCalls.size)
        assertEquals(userId, repository.saveCalls.single().telegramUserId)
        assertEquals(details, repository.saveCalls.single().details)
        assertNull(storage.get(userId))
        assertAll(
            { assertTrue(result.acknowledged) },
            { assertTrue(result.replies.isEmpty()) },
            { assertEquals(listOf(expected), bot.editedMessages.drop(editCount)) },
            { assertEquals(expected, bot.currentMessage(registered.messageId)) },
            {
                assertEquals(
                    listOf(Json.parseToJsonElement("""{"inline_keyboard":[]}""")),
                    bot.editedReplyMarkups.drop(editCount).map {
                        Json.parseToJsonElement(assertNotNull(it))
                    },
                )
            },
            { assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(calls)) },
            { assertEquals(1, bot.apiCalls.count { it == "sendMessage" }) },
            { assertEquals(deletions, bot.deletedMessages) },
            { assertNull(messages.get(userId)) },
            { assertNull(messages.questionToken(userId)) },
        )
    }

    private fun currentQuestion(bot: TestTelegramBot): EditedTelegramMessage {
        val registered = assertNotNull(messages.get(userId))
        return bot.currentMessage(registered.messageId)
    }

    private fun realBot() =
        TestTelegramBot(
            TelegramBot(TelegramProperties("test-token"), storage, manager, messages),
            chatId = chatId.value,
            firstBotMessageId = 6_341L,
        )

    private suspend fun confirmationBot(details: TrainingDetails): TestTelegramBot {
        val session = UserSession(WaitingConfirmation(details), Instant.parse("2026-10-13T03:30:00Z"))
        check(storage.start(userId, session))
        val bot =
            TestTelegramBot(chatId = chatId.value, firstBotMessageId = 6_341L) {
                command("confirmation_fixture") {
                    val reply = checkNotNull(promptFor(session.state)).withQuestionToken()
                    val sent =
                        checkNotNull(
                            bot
                                .sendMessage(ChatId.fromId(chatId.value), reply.text, replyMarkup = reply.replyMarkup)
                                .getOrNull(),
                        )
                    val registered =
                        TrainingMessageSession(
                            session.sessionId,
                            TelegramChatId(sent.chat.id),
                            TelegramMessageId(sent.messageId),
                        )
                    messages.put(userId, registered)
                    messages.registerQuestion(userId, registered, reply.callbackData())
                }
                registerTrainingChoiceCallbacks(storage, manager, messages)
            }
        bot.sendCommand("/confirmation_fixture", userId.value, 713L)
        return bot
    }

    companion object {
        @JvmStatic
        fun formattingCases(): List<Arguments> =
            listOf(
                Arguments.of("mixed descriptions", FULL_DETAILS, FULL_SUMMARY),
                Arguments.of("no techniques or note", TrainingDetails(SAVED_DATE, SAVED_TIME), DATE_AND_TIME),
                Arguments.of(
                    "note without techniques",
                    TrainingDetails(SAVED_DATE, SAVED_TIME, note = NOTE),
                    "$DATE_AND_TIME\n\nЗаметка: $NOTE",
                ),
                Arguments.of(
                    "user HTML",
                    TrainingDetails(
                        SAVED_DATE,
                        SAVED_TIME,
                        listOf(TechniqueDraft("Armbar <b>&</b>", "Описание <>& &lt;")),
                        "Заметка </blockquote><b>&",
                    ),
                    "$DATE_AND_TIME\n\nТехники:\n1. Armbar &lt;b&gt;&amp;&lt;/b&gt;\n" +
                        "   Описание: Описание &lt;&gt;&amp; &amp;lt;\n\n" +
                        "Заметка: Заметка &lt;/blockquote&gt;&lt;b&gt;&amp;",
                ),
            )
    }
}
