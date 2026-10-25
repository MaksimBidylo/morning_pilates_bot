package morningpilates.telegram.logging

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.entities.CallbackQuery
import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.entities.User
import com.github.kotlintelegrambot.webhook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.logging.renderedLogs
import morningpilates.profile.FakeProfileRepository
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingQueryService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val CANARY = "CANARY_144_RECEIVED_NOT_A_SECRET_note_token_url"
private const val CANARY_ID = 998877665544L

class TelegramUpdateObserverTest {
    @Test
    fun `registered observer preserves command and callback responses`() {
        val storage = InMemoryFlowStorage()
        val repository = FakeTrainingRepository()
        val telegram =
            TelegramBot(
                FakeProfileRepository(),
                TelegramProperties(CANARY),
                storage,
                FlowSessionManager(storage, listOf(TrainingFlowService(repository))),
                TrainingCreationMessageSessionStorage(),
                TrainingQueryService(repository),
                repository::delete,
            )
        val output =
            renderedLogs {
                runBlocking {
                    TestTelegramBot(telegram, chatId = CANARY_ID).use { bot ->
                        assertTrue(bot.sendCommand("/start").text.startsWith("Morning Pilates Bot"))
                        val question = bot.sendCommand("/training_add")
                        val cancelled = bot.pressInlineButtonAndCollectReplies(question, "Отменить тренировку")
                        assertTrue(cancelled.acknowledged)
                        assertEquals("Создание тренировки отменено.", bot.editedMessages.last().text)
                    }
                }
            }
        val records = output.lineSequence().filter { "event=telegram_received" in it }.toList()
        assertEquals(3, records.size)
        assertTrue(records[0].endsWith("kind=command operation=start"))
        assertTrue(records[1].endsWith("kind=command operation=training_add"))
        assertTrue(records[2].endsWith("kind=callback operation=training_add"))
    }

    @Test
    fun `one safe record per delivery covers every update kind including unknown input`() {
        val cases =
            listOf(
                message("/training_add@$CANARY $CANARY") to "command operation=training_add",
                message("/help $CANARY") to "command operation=help",
                message("/$CANARY") to "command operation=unknown",
                message(CANARY) to "text operation=unknown",
                callback("training:confirmation:yes:$CANARY") to "callback operation=training_add",
                callback("profile:edit:$CANARY") to "callback operation=profile",
                callback("view:delete:$CANARY") to "callback operation=training_view",
                callback("training:$CANARY:$CANARY") to "callback operation=unknown",
                callback("$CANARY:$CANARY") to "callback operation=unknown",
                callback("") to "callback operation=unknown",
                Update(CANARY_ID, editedMessage = message(CANARY).message) to "other operation=unknown",
                Update(CANARY_ID, channelPost = message(CANARY).message) to "other operation=unknown",
                Update(CANARY_ID) to "other operation=unknown",
            )
        val output = renderedLogs { runBlocking { dispatchUpdates(cases.map { it.first }) } }
        val records = output.lineSequence().filter { "event=telegram_received" in it }.toList()
        assertEquals(cases.size, records.size)
        cases.zip(records).forEach { (case, record) -> assertTrue(record.endsWith("kind=${case.second}")) }
        assertEquals(records.size, records.map(::correlationId).toSet().size)
        assertFalse(CANARY in output || CANARY_ID.toString() in output || "https://" in output)
        assertFalse("telegram_completed" in output || "duration" in output)
    }

    @Test
    fun `SDK does not deduplicate repeated ids and observer leaves consumed unchanged`() {
        val update = message(CANARY)
        var handled = 0
        val output =
            renderedLogs {
                runBlocking {
                    dispatchUpdates(listOf(update, update.copy())) {
                        assertFalse(it.consumed)
                        handled++
                    }
                }
            }
        val records = output.lineSequence().filter { "event=telegram_received" in it }.toList()
        assertEquals(2, handled)
        assertEquals(2, records.size)
        assertNotEquals(correlationId(records[0]), correlationId(records[1]))
        assertFalse(update.consumed)
    }

    @Test
    fun `SDK continues to later handlers if observer logging throws without leaking the error`() {
        val logger =
            object : Logger by LoggerFactory.getLogger(TelegramUpdateObserver::class.java) {
                override fun info(
                    format: String?,
                    vararg arguments: Any?,
                ): Unit = throw IllegalStateException(CANARY)
            }
        var handled = false
        val output =
            renderedLogs {
                runBlocking {
                    dispatchUpdates(listOf(message(CANARY)), TelegramUpdateObserver(logger)) { handled = true }
                }
            }
        assertTrue(handled)
        assertFalse(CANARY in output)
    }
}

private fun message(text: String): Update =
    Update(
        CANARY_ID,
        message =
            Message(
                CANARY_ID,
                from = User(CANARY_ID, false, CANARY),
                date = 0,
                chat = Chat(CANARY_ID, "private"),
                text = text,
            ),
    )

private fun callback(data: String): Update =
    Update(
        CANARY_ID,
        callbackQuery =
            CallbackQuery(
                CANARY,
                User(CANARY_ID, false, CANARY),
                message = message(CANARY).message,
                data = data,
                chatInstance = CANARY,
            ),
    )

private fun correlationId(record: String): UUID {
    val value = record.substringAfter("correlation_id=").substringBefore(' ')
    return UUID.fromString(value)
}

private suspend fun dispatchUpdates(
    updates: List<Update>,
    observer: TelegramUpdateObserver = TelegramUpdateObserver(),
    after: (Update) -> Unit = {},
) {
    val processed = Channel<Unit>(Channel.UNLIMITED)
    val sdk =
        bot {
            token = CANARY
            coroutineDispatcher = Dispatchers.Unconfined
            webhook {
                url = "https://bot.invalid/webhook"
                createOnStart = false
            }
            dispatch {
                addHandler(observer)
                addHandler(
                    object : Handler {
                        override fun checkUpdate(update: Update): Boolean = true

                        override suspend fun handleUpdate(
                            bot: Bot,
                            update: Update,
                        ) {
                            after(update)
                            processed.send(Unit)
                        }
                    },
                )
            }
        }
    check(sdk.startWebhook())
    try {
        withTimeout(5_000) {
            updates.forEach {
                sdk.processUpdate(it)
                processed.receive()
            }
        }
    } finally {
        sdk.stopPolling()
        processed.cancel()
    }
}
