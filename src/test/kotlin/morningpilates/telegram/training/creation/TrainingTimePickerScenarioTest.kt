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
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalTime
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TrainingTimePickerScenarioTest {
    private val user = TelegramUserId(42)
    private val flows = InMemoryFlowStorage()
    private val repository = FakeTrainingRepository()
    private var messages = TrainingCreationMessageSessionStorage(timePickerEnabled = true)

    @ParameterizedTest
    @MethodSource("times")
    fun `every hour and quarter hour passes through the existing flow and saves unchanged`(
        hour: Int,
        minute: Int,
    ) = runBlocking<Unit> {
        bot().use { bot ->
            val id = openTime(bot)
            assertEquals((0..23).map { pad(it) } + "Отменить тренировку", buttons(bot, id))
            press(bot, id, pad(hour))
            assertEquals(listOf("00", "15", "30", "45", "Назад", "Отменить тренировку"), buttons(bot, id))
            press(bot, id, pad(minute))
            val time = LocalTime.of(hour, minute)
            assertEquals(time, assertIs<WaitingAddTechnique>(flows.get(user)?.state).details.time)
            assertNull(messages.snapshot(user)?.timePicker)
            press(bot, id, "Нет")
            press(bot, id, "Нет")
            press(bot, id, "Да")
            assertEquals(
                time,
                repository.saveCalls
                    .single()
                    .details.time,
            )
            assertEquals(1, bot.sendMessageRequests.size)
            assertNull(flows.get(user))
        }
    }

    @Test
    fun `back discards selected hour and keeps the domain date unchanged`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = openTime(bot)
                val state = flows.get(user)
                press(bot, id, "08")
                val stale = bot.captureCallback(bot.currentMessage(id), "15")
                press(bot, id, "Назад")
                assertEquals(state, flows.get(user))
                assertNull(messages.snapshot(user)?.timePicker?.hour)
                bot.deliverCallbackAndCollectReplies(stale)
                assertEquals(state, flows.get(user))
                press(bot, id, "23")
                press(bot, id, "45")
                assertEquals(LocalTime.of(23, 45), assertIs<WaitingAddTechnique>(flows.get(user)?.state).details.time)
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `text input and errors retain current screen and accept arbitrary minutes`(minutesScreen: Boolean) =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = openTime(bot)
                if (minutesScreen) press(bot, id, "08")
                val picker = messages.snapshot(user)?.timePicker
                val instruction = bot.currentMessage(id).text
                val keyboard = bot.currentMessage(id).inlineKeyboard
                val session = flows.get(user)
                repeat(2) {
                    bot.sendTextAndCollectReplies("25:99")
                    val text = bot.currentMessage(id).text
                    assertTrue(text.startsWith(instruction + "\n\n⚠️"))
                    assertEquals(1, Regex("⚠️").findAll(text).count())
                    assertEquals(
                        listOf(
                            instruction,
                            "⚠️ Некорректное время. Используйте формат Ч:ММ, ЧЧ:ММ или аналогичный формат с дефисом. " +
                                "Например: 8:00 или 19-30.",
                        ),
                        text.split("\n\n"),
                    )
                    assertEquals(keyboard, bot.currentMessage(id).inlineKeyboard)
                    assertEquals(picker, messages.snapshot(user)?.timePicker)
                    assertEquals(session, flows.get(user))
                }
                bot.sendTextAndCollectReplies("9-37")
                assertEquals(LocalTime.of(9, 37), assertIs<WaitingAddTechnique>(flows.get(user)?.state).details.time)
                assertFalse(bot.currentMessage(id).text.contains("⚠️"))
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `foreign forged repeated and stale callbacks cannot change the flow`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = openTime(bot)
                val hour = bot.captureCallback(bot.currentMessage(id), "08")
                val message = checkNotNull(hour.message)
                val invalid =
                    listOf(
                        hour.copy(from = hour.from.copy(id = 43)),
                        hour.copy(message = message.copy(messageId = 999)),
                        hour.copy(message = message.copy(chat = message.chat.copy(id = 999))),
                        hour.copy(message = message.copy(chat = message.chat.copy(type = "group"))),
                        hour.copy(data = hour.data.replace("hour-8", "hour-24")),
                        hour.copy(data = hour.data.replace("hour-8", "minute-15")),
                    )
                for (callback in invalid) assertTrue(bot.deliverCallbackAndCollectReplies(callback).acknowledged)
                assertNull(messages.snapshot(user)?.timePicker?.hour)
                bot.deliverCallbackAndCollectReplies(hour)
                val minute = bot.captureCallback(bot.currentMessage(id), "15")
                bot.deliverCallbackAndCollectReplies(hour)
                assertEquals(8, messages.snapshot(user)?.timePicker?.hour)
                bot.deliverCallbackAndCollectReplies(minute)
                val state = flows.get(user)
                bot.deliverCallbackAndCollectReplies(minute)
                bot.deliverCallbackAndCollectReplies(hour)
                assertEquals(state, flows.get(user))
            }
        }

    @Test
    fun `failed navigation can be retried and a missing question is replaced`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = openTime(bot)
                val hour = bot.captureCallback(bot.currentMessage(id), "08")
                val before = messages.snapshot(user)
                bot.failNextEditMessageText("Unavailable")
                bot.deliverCallbackAndCollectReplies(hour)
                assertEquals(before?.timePicker, messages.snapshot(user)?.timePicker)
                assertEquals(before?.question, messages.snapshot(user)?.question)
                assertEquals(1, bot.sendMessageRequests.size)
                bot.failNextEditMessageText("Bad Request: message to edit not found")
                val replacement = bot.deliverCallbackAndCollectReplies(hour).replies.single()
                val newId = TelegramMessageId(replacement.messageId)
                assertNotEquals(id, newId)
                bot.deliverCallbackAndCollectReplies(hour)
                press(bot, newId, "30")
                assertEquals(LocalTime.of(8, 30), assertIs<WaitingAddTechnique>(flows.get(user)?.state).details.time)
                assertEquals(2, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `repeat command restores selected hour and failed send leaves recovery possible`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = openTime(bot)
                press(bot, id, "08")
                val old = bot.captureCallback(bot.currentMessage(id), "15")
                val before = messages.snapshot(user)
                val session = flows.get(user)
                val deletions = bot.deleteMessageAttempts.size
                bot.failNextSendMessage("Unavailable")
                assertTrue(bot.sendCommandAndCollectReplies("/training_add").isEmpty())
                assertEquals(before, messages.snapshot(user))
                assertEquals(session, flows.get(user))
                assertEquals(deletions, bot.deleteMessageAttempts.size)
                val restored = bot.sendCommandAndCollectReplies("/training_add").single()
                val newId = TelegramMessageId(restored.messageId)
                assertNotEquals(id, newId)
                assertTrue(bot.deleteMessageAttempts.any { it.messageId == id && it.succeeded })
                assertEquals(8, messages.snapshot(user)?.timePicker?.hour)
                assertEquals(listOf("00", "15", "30", "45", "Назад", "Отменить тренировку"), buttons(bot, newId))
                bot.deliverCallbackAndCollectReplies(old)
                assertIs<WaitingStartTime>(flows.get(user)?.state)
                press(bot, newId, "45")
                assertEquals(LocalTime.of(8, 45), assertIs<WaitingAddTechnique>(flows.get(user)?.state).details.time)
            }
        }

    @ParameterizedTest
    @CsvSource("false, false", "true, false", "true, true")
    fun `recovery registers the new question before retiring the old one and tolerates cleanup failure`(
        deletionFails: Boolean,
        markupFails: Boolean,
    ) = runBlocking<Unit> {
        bot().use { bot ->
            val id = openTime(bot)
            press(bot, id, "08")
            val stale = bot.captureCallback(bot.currentMessage(id), "15")
            val session = flows.get(user)
            val calls = bot.apiCalls.size
            bot.beforeNextDeleteMessageResponse {
                val current = checkNotNull(messages.get(user))
                assertNotEquals(id, current.messageId)
                assertEquals(session, flows.get(user))
                assertEquals(8, messages.snapshot(user)?.timePicker?.hour)
            }
            if (deletionFails) bot.failNextDeleteMessage("Bad Request: message can't be deleted")
            if (markupFails) bot.failNextEditMessageReplyMarkup("Bad Request: message to edit not found")

            val restored = bot.sendCommandAndCollectReplies("/training_add").single()
            val newId = TelegramMessageId(restored.messageId)
            assertEquals(newId, messages.get(user)?.messageId)
            assertEquals(session, flows.get(user))
            assertEquals(
                if (deletionFails) {
                    listOf("sendMessage", "deleteMessage", "editMessageReplyMarkup", "deleteMessage")
                } else {
                    listOf("sendMessage", "deleteMessage", "deleteMessage")
                },
                bot.apiCalls.drop(calls),
            )
            assertTrue(bot.deleteMessageAttempts.none { it.messageId == newId })
            repeat(2) { bot.deliverCallbackAndCollectReplies(stale) }
            assertEquals(session, flows.get(user))
            press(bot, newId, "30")
            assertEquals(LocalTime.of(8, 30), assertIs<WaitingAddTechnique>(flows.get(user)?.state).details.time)
        }
    }

    @Test
    fun `accepted time survives failed delivery and repeated command restores next step`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = openTime(bot)
                press(bot, id, "08")
                val minute = bot.captureCallback(bot.currentMessage(id), "15")
                bot.failNextEditMessageText("Unavailable")
                bot.deliverCallbackAndCollectReplies(minute)
                val state = flows.get(user)
                assertEquals(LocalTime.of(8, 15), assertIs<WaitingAddTechnique>(state?.state).details.time)
                bot.deliverCallbackAndCollectReplies(minute)
                assertEquals(state, flows.get(user))
                val restored = bot.sendCommandAndCollectReplies("/training_add").single()
                assertTrue(restored.text.contains("Добавить технику"))
                assertNull(messages.snapshot(user)?.timePicker)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["/cancel", "/cancel_all", "button"])
    fun `cancellation rejects old time buttons`(action: String) =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = openTime(bot)
                press(bot, id, "08")
                val minute = bot.captureCallback(bot.currentMessage(id), "15")
                if (action == "button") {
                    press(bot, id, "Отменить тренировку")
                } else {
                    bot.sendCommandAndCollectReplies(action)
                }
                bot.deliverCallbackAndCollectReplies(minute)
                assertNull(flows.get(user))
                assertNull(messages.get(user))
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `disabled picker retains text time and exposes no time callback`() =
        runBlocking<Unit> {
            messages = TrainingCreationMessageSessionStorage()
            bot().use { bot ->
                val id = openTime(bot)
                assertEquals(listOf("Отменить тренировку"), buttons(bot, id))
                assertTrue(bot.currentMessage(id).text.contains("Введите время"))
                bot.sendTextAndCollectReplies("25:99")
                assertTrue(bot.currentMessage(id).text.startsWith("⚠️"))
                assertTrue(bot.currentMessage(id).text.endsWith(TIME_PROMPT))
                val restored = bot.sendCommandAndCollectReplies("/training_add").single()
                assertEquals(listOf("Отменить тренировку"), buttons(bot, TelegramMessageId(restored.messageId)))
                assertTrue(bot.deleteMessageAttempts.any { it.messageId == id && it.succeeded })
                bot.sendTextAndCollectReplies("8:37")
                assertEquals(LocalTime.of(8, 37), assertIs<WaitingAddTechnique>(flows.get(user)?.state).details.time)
            }
        }

    @Test
    fun `date calendar and time picker work together without sharing callbacks`() =
        runBlocking<Unit> {
            messages = TrainingCreationMessageSessionStorage(calendarEnabled = true, timePickerEnabled = true)
            bot().use { bot ->
                val id = TelegramMessageId(bot.sendCommandAndCollectReplies("/training_add").single().messageId)
                val date = bot.captureCallback(bot.currentMessage(id), "12")
                bot.deliverCallbackAndCollectReplies(date)
                assertEquals((0..23).map { pad(it) } + "Отменить тренировку", buttons(bot, id))
                bot.deliverCallbackAndCollectReplies(date)
                assertIs<WaitingStartTime>(flows.get(user)?.state)
                press(bot, id, "08")
                press(bot, id, "15")
                assertEquals(LocalTime.of(8, 15), assertIs<WaitingAddTechnique>(flows.get(user)?.state).details.time)
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `restart rejects former picker and a new command opens a fresh flow`() =
        runBlocking<Unit> {
            val old =
                bot().use { bot ->
                    val id = openTime(bot)
                    press(bot, id, "08")
                    bot.captureCallback(bot.currentMessage(id), "15")
                }
            flows.remove(user, checkNotNull(flows.get(user)))
            messages = TrainingCreationMessageSessionStorage(timePickerEnabled = true)
            bot().use { bot ->
                assertTrue(bot.deliverCallbackAndCollectReplies(old).acknowledged)
                assertNull(flows.get(user))
                val id = openTime(bot)
                assertEquals((0..23).map { pad(it) } + "Отменить тренировку", buttons(bot, id))
            }
        }

    @Test
    fun `failed navigation cannot restore a cancelled question`() =
        runBlocking<Unit> {
            bot().use { bot ->
                val id = openTime(bot)
                bot.beforeNextEditMessageTextResponse {
                    flows.remove(user, checkNotNull(flows.get(user)))
                    messages.remove(user, messages.get(user))
                }
                bot.failNextEditMessageText("Unavailable")
                press(bot, id, "08")
                assertNull(flows.get(user))
                assertNull(messages.snapshot(user))
            }
        }

    private suspend fun openTime(bot: TestTelegramBot): TelegramMessageId {
        val id = TelegramMessageId(bot.sendCommandAndCollectReplies("/training_add").single().messageId)
        bot.sendTextAndCollectReplies("2026-10-20")
        return id
    }

    private fun buttons(
        bot: TestTelegramBot,
        id: TelegramMessageId,
    ) = bot
        .currentMessage(id)
        .inlineKeyboard
        .flatten()
        .map { it.text }

    private suspend fun press(
        bot: TestTelegramBot,
        id: TelegramMessageId,
        text: String,
    ) {
        val result = bot.pressInlineButtonAndCollectReplies(bot.currentMessage(id), text)
        assertTrue(result.acknowledged)
        assertTrue(result.replies.isEmpty())
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

    companion object {
        @JvmStatic
        fun times(): Stream<Arguments> =
            (0..23)
                .flatMap { hour ->
                    listOf(0, 15, 30, 45).map { Arguments.of(hour, it) }
                }.stream()
    }
}

private fun pad(value: Int): String = value.toString().padStart(2, '0')
