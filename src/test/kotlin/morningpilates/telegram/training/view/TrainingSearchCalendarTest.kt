package morningpilates.telegram.training.view

import kotlinx.coroutines.runBlocking
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.command.registerCancelAllCommand
import morningpilates.telegram.command.registerCancelCommand
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.training.calendar.CalendarLevel
import morningpilates.telegram.training.command.registerTrainingGetDateCommand
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import morningpilates.training.INVALID_DATE_REASON
import morningpilates.training.SavedTraining
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingId
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrainingSearchCalendarTest {
    private val user = TelegramUserId(42)
    private val clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)
    private var views = TrainingViewStorage(dateCalendarEnabled = true, clock = clock)
    private val repository = FakeTrainingRepository()
    private val flows = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()

    @Test
    fun `current month year navigation leap day and results use existing message`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = open(bot)
                assertTrue(
                    current(bot, id)
                        .inlineKeyboard
                        .flatten()
                        .map { it.text }
                        .containsAll(listOf("Октябрь", "2026")),
                )
                press(bot, id, "›")
                assertEquals(
                    YearMonth.of(2026, 11),
                    views
                        .get(user)
                        ?.dateSearch
                        ?.calendar
                        ?.month,
                )
                press(bot, id, "‹")
                press(bot, id, "2026")
                press(bot, id, "Годы ›")
                press(bot, id, "2028")
                press(bot, id, "Февраль")
                val date = LocalDate.of(2028, 2, 29)
                val training = SavedTraining(TrainingId(UUID(0, 1)), TrainingDetails(date, LocalTime.NOON))
                repository.findByDateResults[FindByDateCall(user, date)] = Result.success(listOf(training, training))
                val callback = bot.captureCallback(current(bot, id), "29")
                bot.deliverCallbackAndCollectReplies(callback)
                assertTrue(current(bot, id).text.contains("Тренировка 1 из 2"))
                press(bot, id, "Следующая")
                assertTrue(current(bot, id).text.contains("Тренировка 2 из 2"))
                bot.deliverCallbackAndCollectReplies(callback)
                assertEquals(listOf(FindByDateCall(user, date)), repository.findByDateCalls)
                press(bot, id, "Выбрать другую дату")
                assertEquals(
                    YearMonth.of(2026, 10),
                    views
                        .get(user)
                        ?.dateSearch
                        ?.calendar
                        ?.month,
                )
                assertEquals(1, bot.sendMessageRequests.size)
                assertFalse(current(bot, id).inlineKeyboard.flatten().any { it.text in listOf("Сегодня", "Вчера") })
            }
        }

    @ParameterizedTest
    @EnumSource(CalendarLevel::class)
    fun `invalid and valid text input work without resetting calendar navigation`(level: CalendarLevel) =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = open(bot)
                press(bot, id, "›")
                if (level == CalendarLevel.Months) press(bot, id, "Ноябрь")
                if (level == CalendarLevel.Years) press(bot, id, "2026")
                val calendar = views.get(user)?.dateSearch?.calendar
                val instruction = current(bot, id).text
                bot.sendTextAndCollectReplies("31.02.2026", messageId = 9000)
                bot.sendTextAndCollectReplies("31.02.2026", messageId = 9002)
                assertEquals(calendar, views.get(user)?.dateSearch?.calendar)
                assertEquals("$instruction\n\n⚠️ $INVALID_DATE_REASON", current(bot, id).text)
                assertEquals(1, Regex("⚠️").findAll(current(bot, id).text).count())
                repository.findByDateResults[FindByDateCall(user, LocalDate.of(2027, 1, 3))] =
                    Result.success(emptyList())
                bot.sendTextAndCollectReplies("2027-01-03", messageId = 9001)
                assertEquals(LocalDate.of(2027, 1, 3), views.get(user)?.dateSearch?.date)
                assertTrue(current(bot, id).text.contains("тренировок не найдено"))
                assertFalse(current(bot, id).text.contains("⚠️"))
                assertEquals(listOf(FindByDateCall(user, LocalDate.of(2027, 1, 3))), repository.findByDateCalls)
                assertTrue(bot.deletedMessages.any { it.messageId == 9001L })
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `back and repeated command return to current month and invalidate previous callbacks`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = open(bot)
                press(bot, id, "›")
                press(bot, id, "2026")
                press(bot, id, "Назад")
                assertEquals(
                    CalendarLevel.Months,
                    views
                        .get(user)
                        ?.dateSearch
                        ?.calendar
                        ?.level,
                )
                press(bot, id, "Назад")
                val old = bot.captureCallback(current(bot, id), "3")
                val edits = bot.editedMessages.size
                val replacement = bot.sendCommandAndCollectReplies("/training_get_date").single()
                assertTrue(TelegramMessageId(replacement.messageId) != id)
                assertEquals(edits, bot.editedMessages.size)
                assertEquals(
                    YearMonth.of(2026, 10),
                    views
                        .get(user)
                        ?.dateSearch
                        ?.calendar
                        ?.month,
                )
                bot.deliverCallbackAndCollectReplies(old)
                assertTrue(repository.findByDateCalls.isEmpty())
                assertEquals(2, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `foreign hidden and forged callbacks cannot search or navigate`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = open(bot)
                val callback = bot.captureCallback(current(bot, id), "12")
                val message = checkNotNull(callback.message)
                val before = views.get(user)
                val invalid =
                    listOf(
                        callback.copy(from = callback.from.copy(id = 43)),
                        callback.copy(message = message.copy(messageId = 999)),
                        callback.copy(message = message.copy(chat = message.chat.copy(id = 999))),
                        callback.copy(message = message.copy(chat = message.chat.copy(type = "group"))),
                        callback.copy(data = callback.data.replace("day-12", "day-32")),
                        callback.copy(data = callback.data.replace("day-12", "year-2028")),
                    )
                for (value in invalid) assertTrue(bot.deliverCallbackAndCollectReplies(value).acknowledged)
                assertEquals(before, views.get(user))
                assertTrue(repository.findByDateCalls.isEmpty())
                assertTrue(current(bot, id).inlineKeyboard.flatten().all { it.callbackData.toByteArray().size <= 64 })
            }
        }

    @Test
    fun `transient navigation error keeps old screen usable and missing message recovers once`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = open(bot)
                val callback = bot.captureCallback(current(bot, id), "›")
                val before = views.get(user)
                bot.failNextEditMessageText("Unavailable")
                bot.deliverCallbackAndCollectReplies(callback)
                assertEquals(before, views.get(user))
                assertEquals(1, bot.sendMessageRequests.size)
                bot.failNextEditMessageText("Bad Request: message to edit not found")
                val replacement = bot.deliverCallbackAndCollectReplies(callback).replies.single()
                assertEquals(
                    YearMonth.of(2026, 11),
                    views
                        .get(user)
                        ?.dateSearch
                        ?.calendar
                        ?.month,
                )
                bot.deliverCallbackAndCollectReplies(callback)
                assertEquals(2, bot.sendMessageRequests.size)
                assertEquals(TelegramMessageId(replacement.messageId), views.get(user)?.messageId)
            }
        }

    @Test
    fun `selected date survives failed display and cannot be selected twice`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = open(bot)
                val callback = bot.captureCallback(current(bot, id), "12")
                bot.failNextEditMessageText("Unavailable")
                bot.deliverCallbackAndCollectReplies(callback)
                assertEquals(LocalDate.of(2026, 10, 12), views.get(user)?.dateSearch?.date)
                bot.deliverCallbackAndCollectReplies(callback)
                assertEquals(1, repository.findByDateCalls.size)
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `search failure retries and deletion retains date view behaviour`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = open(bot)
                val date = LocalDate.of(2026, 10, 12)
                val call = FindByDateCall(user, date)
                repository.findByDateResults[call] = Result.failure(IllegalStateException("offline"))
                press(bot, id, "12")
                assertTrue(views.get(user)?.dateSearch?.loadFailed == true)
                val training = SavedTraining(TrainingId(UUID(0, 1)), TrainingDetails(date, LocalTime.NOON))
                repository.findByDateResults[call] = Result.success(listOf(training))
                press(bot, id, "Повторить попытку")
                press(bot, id, "Удалить тренировку")
                press(bot, id, "Отмена")
                assertTrue(current(bot, id).text.contains("Тренировка 1 из 1"))
                assertTrue(current(bot, id).inlineKeyboard.flatten().any { it.text == "Выбрать другую дату" })
            }
        }

    @Test
    fun `cancel cancel all and restart invalidate calendar callbacks`() =
        runBlocking<Unit> {
            for (command in listOf("/cancel", "/cancel_all")) {
                bot().use { bot ->
                    val id = open(bot)
                    val callback = bot.captureCallback(current(bot, id), "12")
                    bot.sendCommandAndCollectReplies(command)
                    bot.deliverCallbackAndCollectReplies(callback)
                    assertNull(views.get(user))
                    assertTrue(repository.findByDateCalls.isEmpty())
                }
            }
            bot().use { first ->
                val id = open(first)
                val callback = first.captureCallback(current(first, id), "12")
                views = TrainingViewStorage(true, clock)
                bot().use { restarted ->
                    assertTrue(restarted.deliverCallbackAndCollectReplies(callback).acknowledged)
                    assertNull(views.get(user))
                    assertTrue(repository.findByDateCalls.isEmpty())
                }
            }
        }

    @Test
    fun `disabled flag preserves text question and manual search`() =
        runBlocking<Unit> {
            views = TrainingViewStorage(clock = clock)
            bot().use { bot ->
                val id = open(bot)
                assertEquals(DATE_VIEW_QUESTION, current(bot, id).text)
                assertEquals(listOf("Завершить просмотр"), current(bot, id).inlineKeyboard.flatten().map { it.text })
                bot.sendTextAndCollectReplies("12.10.2026")
                assertEquals(LocalDate.of(2026, 10, 12), views.get(user)?.dateSearch?.date)
            }
        }

    @Test
    fun `selected empty date restores missing result message and invalidates old day`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = open(bot)
                val date = LocalDate.of(2026, 10, 12)
                repository.findByDateResults[FindByDateCall(user, date)] = Result.success(emptyList())
                val day = bot.captureCallback(current(bot, id), "12")
                bot.failNextEditMessageText("Bad Request: message to edit not found")
                val replacement = bot.deliverCallbackAndCollectReplies(day).replies.single()
                assertTrue(replacement.text.contains("тренировок не найдено"))
                assertEquals(date, views.get(user)?.dateSearch?.date)
                bot.deliverCallbackAndCollectReplies(day)
                assertEquals(1, repository.findByDateCalls.size)
                assertEquals(2, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `navigation failure cannot resurrect a closed view`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = open(bot)
                bot.beforeNextEditMessageTextResponse {
                    val active = checkNotNull(views.get(user))
                    views.remove(user, active)
                }
                bot.failNextEditMessageText("Unavailable")
                press(bot, id, "›")
                assertNull(views.get(user))
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    private fun bot() =
        TestTelegramBot(chatId = 4200) {
            val query = TrainingQueryService(repository)
            registerTrainingGetDateCommand(flows, messages, views)
            registerTrainingDateTextHandler(query, views)
            registerTrainingViewCallbacks(views, query, repository::delete)
            registerCancelCommand(flows, FlowSessionManager(flows, emptyList()), messages, views)
            registerCancelAllCommand(flows, messages, views)
        }

    private suspend fun open(bot: TestTelegramBot): TelegramMessageId =
        TelegramMessageId(bot.sendCommandAndCollectReplies("/training_get_date").single().messageId)

    private fun current(
        bot: TestTelegramBot,
        id: TelegramMessageId,
    ): EditedTelegramMessage = bot.currentMessage(id)

    private suspend fun press(
        bot: TestTelegramBot,
        id: TelegramMessageId,
        button: String,
    ) {
        val result = bot.pressInlineButtonAndCollectReplies(current(bot, id), button)
        assertTrue(result.acknowledged)
        assertTrue(result.replies.isEmpty())
    }
}
