package morningpilates.telegram.training.command

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.DATE_VIEW_FINISH
import morningpilates.telegram.support.DATE_VIEW_NEXT
import morningpilates.telegram.support.DATE_VIEW_QUESTION
import morningpilates.telegram.support.SEARCH_DATE
import morningpilates.telegram.support.TelegramDeleteAttempt
import morningpilates.telegram.support.TrainingDateViewTestFixture
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class TrainingGetDateCommandTest {
    private val fixture = TrainingDateViewTestFixture()

    @Test
    fun `command opens a registered date question without reading data or starting a domain flow`() =
        runBlocking<Unit> {
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)

                assertEquals(fixture.chat, question.chatId)
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(fixture.repository.findByDateCalls.isEmpty())
                assertTrue(fixture.repository.findLatestCalls.isEmpty())
                fixture.assertNoCreation()
                // A working finish callback proves registration without depending on future view state types.
                assertTrue(bot.pressInlineButtonAndCollectReplies(question, DATE_VIEW_FINISH).acknowledged)
                assertTrue(TelegramDeleteAttempt(fixture.chat, question.messageId, true) in bot.deleteMessageAttempts)
            }
        }

    @Test
    fun `repeated command reuses the question and resets results back to date input`() =
        runBlocking<Unit> {
            fixture.configure(fixture.trainings)
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)
                assertTrue(bot.sendCommandAndCollectReplies("/training_get_date").isEmpty())
                fixture.assertQuestion(bot.currentMessage(question.messageId))
                assertTrue(fixture.repository.findByDateCalls.isEmpty())
                val result = fixture.enterDate(bot, question)
                fixture.assertCard(result, fixture.trainings, 0)
                val old = bot.captureCallback(result, DATE_VIEW_NEXT)
                val calls = fixture.repository.findByDateCalls.toList()

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_date").isEmpty())

                val reset = bot.currentMessage(question.messageId)
                fixture.assertQuestion(reset)
                val before = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(old).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
                assertEquals(reset, bot.currentMessage(question.messageId))
                assertEquals(calls, fixture.repository.findByDateCalls)
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.assertNoCreation()
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["group", "supergroup"])
    fun `group command neither opens a view nor reads trainings`(chatType: String) =
        runBlocking<Unit> {
            fixture.bot(chatId = -100L, chatType = chatType).use { bot ->
                assertTrue(bot.sendCommandAndCollectReplies("/training_get_date").isEmpty())
                assertTrue(bot.apiCalls.isEmpty())
                assertTrue(fixture.repository.findByDateCalls.isEmpty())
                assertTrue(fixture.repository.findLatestCalls.isEmpty())
                fixture.assertNoCreation()
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["12.10.2026", " 2026/10/12 ", "12-10-2026"])
    fun `supported creation date formats route once to date search and delete input after display`(input: String) =
        runBlocking<Unit> {
            fixture.configure(emptyList())
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)
                val inputId = TelegramMessageId(9_000L)
                val before = bot.apiCalls.size
                bot.beforeNextEditMessageTextResponse {
                    assertTrue(bot.deleteMessageAttempts.none { it.messageId == inputId })
                }

                val result = fixture.enterDate(bot, question, input, inputId.value)

                assertEquals(listOf(FindByDateCall(fixture.user, SEARCH_DATE)), fixture.repository.findByDateCalls)
                assertEquals("За 12.10.2026 тренировок не найдено.", result.text)
                assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(before))
                assertTrue(TelegramDeleteAttempt(fixture.chat, inputId, true) in bot.deleteMessageAttempts)
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(fixture.repository.findLatestCalls.isEmpty())
                fixture.assertNoCreation()
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["31.02.2026", "2026-02-29"])
    fun `invalid calendar date keeps the full question and correction clears the warning`(input: String) =
        runBlocking<Unit> {
            fixture.configure(emptyList())
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)

                val error = fixture.enterDate(bot, question, input)

                assertTrue(error.text.endsWith(DATE_VIEW_QUESTION))
                assertTrue(error.text.substringBefore(DATE_VIEW_QUESTION).isNotBlank())
                assertEquals(2, error.text.split(DATE_VIEW_QUESTION).size)
                fixture.assertButtons(error, setOf(DATE_VIEW_FINISH))
                assertTrue(fixture.repository.findByDateCalls.isEmpty())
                fixture.assertNoCreation()
                val corrected = fixture.enterDate(bot, question, messageId = 9_001L)
                assertEquals("За 12.10.2026 тренировок не найдено.", corrected.text)
                assertEquals(listOf(FindByDateCall(fixture.user, SEARCH_DATE)), fixture.repository.findByDateCalls)
                assertEquals(1, bot.sendMessageRequests.size)
                fixture.assertNoCreation()
            }
        }

    @Test
    fun `commands and slash text are not interpreted as search dates`() =
        runBlocking<Unit> {
            fixture.bot().use { bot ->
                val question = fixture.openQuestion(bot)
                val before = bot.apiCalls.size

                assertTrue(bot.sendCommandAndCollectReplies("/not_a_date").isEmpty())
                assertTrue(bot.sendTextAndCollectReplies("/not_a_date").isEmpty())

                assertEquals(before, bot.apiCalls.size)
                assertEquals(question, bot.currentMessage(question.messageId))
                assertTrue(fixture.repository.findByDateCalls.isEmpty())
                fixture.assertNoCreation()
            }
        }

    @Test
    fun `active creation blocks date search while preserving data question and working buttons`() =
        runBlocking<Unit> {
            fixture.bot().use { bot ->
                bot.sendCommand("/training_add")
                bot.sendTextAndCollectReplies("12.10.2026")
                bot.sendTextAndCollectReplies("7:45")
                val session = assertNotNull(fixture.storage.get(fixture.user))
                val registration = assertNotNull(fixture.messages.get(fixture.user))
                val token = fixture.messages.questionToken(fixture.user)
                val question = bot.currentMessage(registration.messageId)
                val events = fixture.events.toList()
                val sends = bot.sendMessageRequests.size

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_date").isEmpty())

                val blocked = bot.currentMessage(question.messageId)
                val warning =
                    "У вас есть незавершённая тренировка. Продолжите или отмените её, прежде чем начать поиск."
                assertAll(
                    { assertTrue(blocked.text.contains(warning), "Search must warn in the active creation question") },
                    { assertTrue(blocked.text.endsWith(question.text)) },
                    { assertEquals(question.inlineKeyboard, blocked.inlineKeyboard) },
                    { assertEquals(session, fixture.storage.get(fixture.user)) },
                    { assertEquals(registration, fixture.messages.get(fixture.user)) },
                    { assertEquals(token, fixture.messages.questionToken(fixture.user)) },
                    { assertEquals(events, fixture.events) },
                    { assertTrue(fixture.repository.findByDateCalls.isEmpty()) },
                    { assertTrue(fixture.repository.findLatestCalls.isEmpty()) },
                    { assertEquals(sends, bot.sendMessageRequests.size) },
                )
                assertTrue(bot.pressInlineButtonAndCollectReplies(blocked, "Нет").acknowledged)
                val next = bot.currentMessage(question.messageId)
                assertTrue(!next.text.contains(warning))
                assertNotEquals(session.state, assertNotNull(fixture.storage.get(fixture.user)).state)
                assertTrue(fixture.repository.saveCalls.isEmpty())
            }
        }
}
