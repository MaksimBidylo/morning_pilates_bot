package morningpilates.telegram.training.creation

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.profile.FakeProfileRepository
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrainingDateCalendarScenarioTest {
    private val user = TelegramUserId(42)
    private val flows = InMemoryFlowStorage()
    private val repository = FakeTrainingRepository()
    private val clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)
    private var messages = TrainingCreationMessageSessionStorage(calendarEnabled = true, clock = clock)

    @Test
    fun `current month navigation and future leap date reuse question and consume callback once`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val first = bot.sendCommandAndCollectReplies("/training_add").single()
                val id = TelegramMessageId(first.messageId)
                assertTrue(first.inlineButtonTexts.containsAll(listOf("Октябрь", "2026", "Пн", "Вс")))
                press(bot, id, "›")
                assertTrue(current(bot, id).inlineKeyboard.flatten().any { it.text == "Ноябрь" })
                press(bot, id, "‹")
                press(bot, id, "2026")
                press(bot, id, "Годы ›")
                press(bot, id, "2028")
                press(bot, id, "Февраль")
                val day = bot.captureCallback(current(bot, id), "29")
                assertTrue(bot.deliverCallbackAndCollectReplies(day.copy(from = day.from.copy(id = 43))).acknowledged)
                assertEquals(WaitingDate, flows.get(user)?.state)
                assertTrue(bot.deliverCallbackAndCollectReplies(day).acknowledged)
                assertEquals(LocalDate.of(2028, 2, 29), assertIs<WaitingStartTime>(flows.get(user)?.state).date)
                val after = flows.get(user)
                bot.deliverCallbackAndCollectReplies(day)
                assertEquals(after, flows.get(user))
                assertTrue(current(bot, id).text.contains("Введите время"))
                assertEquals(listOf("Отменить тренировку"), current(bot, id).inlineKeyboard.flatten().map { it.text })
                bot.sendTextAndCollectReplies("8:00")
                press(bot, id, "Нет")
                press(bot, id, "Нет")
                press(bot, id, "Да")
                assertEquals(
                    LocalDate.of(2028, 2, 29),
                    repository.saveCalls
                        .single()
                        .details.date,
                )
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @ParameterizedTest
    @EnumSource(CalendarLevel::class)
    fun `manual input works on every level and validation retains month and level`(level: CalendarLevel) =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = TelegramMessageId(bot.sendCommandAndCollectReplies("/training_add").single().messageId)
                press(bot, id, "›")
                if (level == CalendarLevel.Months) press(bot, id, "Ноябрь")
                if (level == CalendarLevel.Years) press(bot, id, "2026")
                val before = current(bot, id).inlineKeyboard
                bot.sendTextAndCollectReplies("31.02.2026")
                assertEquals(before, current(bot, id).inlineKeyboard)
                assertTrue(current(bot, id).text.contains("⚠️"))
                bot.sendTextAndCollectReplies("2027/11/04")
                assertEquals(LocalDate.of(2027, 11, 4), assertIs<WaitingStartTime>(flows.get(user)?.state).date)
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `back and repeated command preserve displayed month but invalidate old buttons`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = TelegramMessageId(bot.sendCommandAndCollectReplies("/training_add").single().messageId)
                val old = bot.captureCallback(current(bot, id), "›")
                press(bot, id, "›")
                bot.deliverCallbackAndCollectReplies(old)
                assertEquals(YearMonth.of(2026, 11), messages.snapshot(user)?.calendar?.month)
                press(bot, id, "Ноябрь")
                press(bot, id, "2026")
                press(bot, id, "Назад")
                press(bot, id, "Назад")
                assertEquals(WaitingDate, flows.get(user)?.state)
                val repeated = bot.sendCommandAndCollectReplies("/training_add").single()
                assertTrue("Ноябрь" in repeated.inlineButtonTexts)
                bot.deliverCallbackAndCollectReplies(old)
                assertEquals(YearMonth.of(2026, 11), messages.snapshot(user)?.calendar?.month)
            }
        }

    @Test
    fun `failed navigation is retryable and missing question is replaced without losing month`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = TelegramMessageId(bot.sendCommandAndCollectReplies("/training_add").single().messageId)
                val next = bot.captureCallback(current(bot, id), "›")
                bot.failNextEditMessageText("Unavailable")
                bot.deliverCallbackAndCollectReplies(next)
                assertEquals(YearMonth.of(2026, 10), messages.snapshot(user)?.calendar?.month)
                bot.deliverCallbackAndCollectReplies(next)
                assertEquals(YearMonth.of(2026, 11), messages.snapshot(user)?.calendar?.month)
                bot.failNextEditMessageText("Bad Request: message to edit not found")
                val result = bot.pressInlineButtonAndCollectReplies(current(bot, id), "›")
                val replacement = result.replies.single()
                assertTrue("Декабрь" in replacement.inlineButtonTexts)
                assertEquals(2, bot.sendMessageRequests.size)
                bot.deliverCallbackAndCollectReplies(next)
                assertEquals(YearMonth.of(2026, 12), messages.snapshot(user)?.calendar?.month)
            }
        }

    @Test
    fun `cancel commands remove calendar and stale buttons cannot revive it`() =
        runBlocking<Unit> {
            bot().use { bot ->
                for (command in listOf("/cancel", "/cancel_all")) {
                    val first = bot.sendCommandAndCollectReplies("/training_add").single()
                    val callback = bot.captureCallback(bot.currentMessage(TelegramMessageId(first.messageId)), "15")
                    bot.sendCommandAndCollectReplies(command)
                    bot.deliverCallbackAndCollectReplies(callback)
                    assertNull(flows.get(user))
                    assertNull(messages.snapshot(user))
                }
                val first = bot.sendCommandAndCollectReplies("/training_add").single()
                bot.pressInlineButtonAndCollectReplies(first, "Отменить тренировку")
                assertNull(flows.get(user))
                assertNull(messages.snapshot(user))
            }
        }

    @Test
    fun `disabled flag preserves original question and ignores forged calendar callback`() =
        runBlocking<Unit> {
            messages = TrainingCreationMessageSessionStorage()
            bot().use { bot ->
                val first = bot.sendCommandAndCollectReplies("/training_add").single()
                assertEquals(listOf("Отменить тренировку"), first.inlineButtonTexts)
                val question = bot.currentMessage(TelegramMessageId(first.messageId))
                val callback = bot.captureCallback(question, "Отменить тренировку")
                bot.deliverCallbackAndCollectReplies(
                    callback.copy(
                        data =
                            "training-calendar:day-15:" + callback.data.substringAfterLast(':'),
                    ),
                )
                assertEquals(WaitingDate, flows.get(user)?.state)
                assertNull(messages.snapshot(user)?.calendar)
                bot.sendTextAndCollectReplies("15.10.2026")
                assertIs<WaitingStartTime>(flows.get(user)?.state)
            }
        }

    @Test
    fun `forged day wrong message and wrong chat are ignored`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = TelegramMessageId(bot.sendCommandAndCollectReplies("/training_add").single().messageId)
                val day = bot.captureCallback(current(bot, id), "15")
                val message = checkNotNull(day.message)
                val invalid =
                    listOf(
                        day.copy(data = day.data.replace("day-15", "day-32")),
                        day.copy(message = message.copy(messageId = message.messageId + 1)),
                        day.copy(message = message.copy(chat = message.chat.copy(id = 43))),
                    )
                for (callback in invalid) {
                    bot.deliverCallbackAndCollectReplies(callback)
                    assertEquals(WaitingDate, flows.get(user)?.state)
                }
                bot.deliverCallbackAndCollectReplies(day)
                assertIs<WaitingStartTime>(flows.get(user)?.state)
            }
        }

    @Test
    fun `accepted date survives failed time display and repeated entry restores time step`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = TelegramMessageId(bot.sendCommandAndCollectReplies("/training_add").single().messageId)
                val day = bot.captureCallback(current(bot, id), "15")
                bot.failNextEditMessageText("Unavailable")
                bot.deliverCallbackAndCollectReplies(day)
                val state = flows.get(user)
                assertEquals(LocalDate.of(2026, 10, 15), assertIs<WaitingStartTime>(state?.state).date)
                bot.deliverCallbackAndCollectReplies(day)
                assertEquals(state, flows.get(user))
                val restored = bot.sendCommandAndCollectReplies("/training_add").single()
                assertTrue(restored.text.contains("Введите время"))
                assertEquals(listOf("Отменить тренировку"), restored.inlineButtonTexts)
            }
        }

    @Test
    fun `restart rejects previous launch calendar and new session uses current month`() =
        runBlocking<Unit> {
            val old =
                bot().use { bot ->
                    val id = TelegramMessageId(bot.sendCommandAndCollectReplies("/training_add").single().messageId)
                    press(bot, id, "›")
                    bot.captureCallback(current(bot, id), "15")
                }
            flows.remove(user, checkNotNull(flows.get(user)))
            messages = TrainingCreationMessageSessionStorage(calendarEnabled = true, clock = clock)
            bot().use { bot ->
                assertTrue(bot.deliverCallbackAndCollectReplies(old).acknowledged)
                assertNull(flows.get(user))
                val fresh = bot.sendCommandAndCollectReplies("/training_add").single()
                assertTrue("Октябрь" in fresh.inlineButtonTexts)
            }
        }

    @Test
    fun `navigation cannot restore a question cancelled during Telegram delivery`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = TelegramMessageId(bot.sendCommandAndCollectReplies("/training_add").single().messageId)
                bot.beforeNextEditMessageTextResponse {
                    val session = checkNotNull(flows.get(user))
                    flows.remove(user, session)
                    messages.remove(user, messages.get(user))
                }
                press(bot, id, "›")
                assertNull(flows.get(user))
                assertNull(messages.snapshot(user))
            }
        }

    private fun current(
        bot: TestTelegramBot,
        id: TelegramMessageId,
    ) = bot.currentMessage(id)

    private suspend fun press(
        bot: TestTelegramBot,
        id: TelegramMessageId,
        text: String,
    ) {
        assertTrue(bot.pressInlineButtonAndCollectReplies(current(bot, id), text).acknowledged)
    }

    private fun bot() =
        TestTelegramBot(
            TelegramBot(
                FakeProfileRepository(),
                TelegramProperties("test-token"),
                flows,
                FlowSessionManager(flows, listOf(TrainingFlowService(repository))),
                messages,
                TrainingQueryService(repository),
                repository::delete,
            ),
            chatId = user.value,
        )
}
