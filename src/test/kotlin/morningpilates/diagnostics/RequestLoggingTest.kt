package morningpilates.diagnostics

import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.entities.CallbackQuery
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.entities.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.profile.FakeProfileRepository
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.logging.TelegramHandlers
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingQueryService
import morningpilates.training.TrainingRepository
import morningpilates.user.TelegramUserId
import org.slf4j.LoggerFactory
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val PRIVATE_TEXT = "secret-note-technique-token-password"
private const val USER_ID = 987654321L

class RequestLoggingTest {
    @Test
    fun `commands text and callback have one correlated pair even through several handlers`() =
        runBlocking {
            LogCapture().use { logs ->
                loggingFixture().use { bot ->
                    bot.sendCommand("/start $PRIVATE_TEXT", senderId = USER_ID)
                    bot.sendCommand("/training_add", senderId = USER_ID)
                    bot.sendTextAndCollectReplies(PRIVATE_TEXT, senderId = USER_ID)
                    bot.deliverCallbackAndCollectReplies(
                        CallbackQuery(
                            id = PRIVATE_TEXT,
                            from = User(USER_ID, false, PRIVATE_TEXT),
                            chatInstance = PRIVATE_TEXT,
                            data = "profile:$PRIVATE_TEXT",
                        ),
                    )
                    assertEquals(4, logs.completions.size)
                    val received = logs.messages.filter { it.startsWith("event=telegram_received ") }
                    assertEquals(4, received.size)
                    received.zip(logs.completions).forEach { (start, finish) ->
                        assertEquals(start.field("correlation_id"), finish.field("correlation_id"))
                        assertTrue(finish.field("duration_ms").toLong() >= 0)
                    }
                    assertEquals(4, received.map { it.field("correlation_id") }.toSet().size)
                    assertEquals(
                        listOf("command", "command", "text", "callback"),
                        logs.completions.map { it.field("kind") },
                    )
                    assertEquals("success", logs.completions[0].field("result"))
                    assertEquals("rejected", logs.completions[2].field("result"))
                    assertEquals("unknown", logs.completions[3].field("result"))
                    assertPrivate(logs)
                }
            }
        }

    @Test
    fun `ignored Telegram error overrides success and does not expose API description`() =
        runBlocking {
            LogCapture().use { logs ->
                loggingFixture().use { bot ->
                    bot.failNextSendMessage("$PRIVATE_TEXT $USER_ID")
                    bot.sendCommandAndCollectReplies("/start", senderId = USER_ID)
                    assertEquals("technical_failure", logs.completions.single().field("result"))
                    assertTrue(logs.rendered.contains("http_status=400"))
                    assertPrivate(logs)
                    bot.sendCommand("/start", senderId = USER_ID)
                    assertEquals("success", logs.completions.last().field("result"))
                    assertNotEquals(
                        logs.completions[0].field("correlation_id"),
                        logs.completions[1].field("correlation_id"),
                    )
                }
            }
        }

    @Test
    fun `returned PostgreSQL error stays technical despite a successfully delivered error reply`() =
        runBlocking {
            val repository =
                FakeTrainingRepository().apply {
                    findLatestResults[TelegramUserId(USER_ID)] =
                        Result.failure(
                            IllegalStateException(PRIVATE_TEXT, SQLException("$PRIVATE_TEXT $USER_ID", "08006")),
                        )
                }
            LogCapture().use { logs ->
                loggingFixture(repository).use { bot ->
                    assertEquals(1, bot.sendCommandAndCollectReplies("/training_get_last", senderId = USER_ID).size)
                    assertEquals("technical_failure", logs.completions.single().field("result"))
                    assertTrue(logs.rendered.contains("sql_state=08006"))
                    assertTrue(logs.rendered.contains("java.sql.SQLException"))
                    assertTrue(logs.rendered.contains("route=training_get_last"))
                    assertPrivate(logs)
                }
            }
        }

    @Test
    fun `handler and predicate failures are recorded once and subsequent handlers still run`() =
        runBlocking {
            val handlers =
                TelegramHandlers().apply {
                    addHandler(
                        object : Handler {
                            override fun checkUpdate(update: Update): Boolean = error(PRIVATE_TEXT)

                            override suspend fun handleUpdate(
                                bot: com.github.kotlintelegrambot.Bot,
                                update: Update,
                            ) = Unit
                        },
                    )
                    command("start") { throw IllegalArgumentException("$PRIVATE_TEXT $USER_ID") }
                    command("start") { RequestDiagnostics.success() }
                }
            LogCapture().use { logs ->
                TestTelegramBot(USER_ID) { addHandler(handlers.build()) }.use { bot ->
                    bot.sendCommandAndCollectReplies("/start $PRIVATE_TEXT", senderId = USER_ID)
                    assertEquals("technical_failure", logs.completions.single().field("result"))
                    assertEquals(1, logs.messages.count { it.startsWith("event=telegram_failure ") })
                    assertPrivate(logs)
                }
            }
        }

    @Test
    fun `correlation survives suspension thread switch concurrency and scope cleanup`() =
        runBlocking {
            LogCapture().use { logs ->
                coroutineScope {
                    listOf("text", "callback")
                        .map { kind ->
                            async {
                                RequestDiagnostics.process(kind) {
                                    delay(5)
                                    withContext(Dispatchers.IO) {
                                        RequestDiagnostics.failure(SQLException(PRIVATE_TEXT, "23505"))
                                    }
                                    RequestDiagnostics.success()
                                }
                            }
                        }.forEach { it.await() }
                }
                assertEquals(2, logs.completions.size)
                logs.completions.forEach { assertTrue(it.field("duration_ms").toLong() >= 5) }
                val failures = logs.messages.filter { it.startsWith("event=telegram_failure ") }
                assertEquals(2, failures.size)
                failures.forEach { failure ->
                    val completion = logs.completions.single { it.field("kind") == failure.field("kind") }
                    assertEquals(completion.field("correlation_id"), failure.field("correlation_id"))
                    assertEquals("technical_failure", completion.field("result"))
                }
                RequestDiagnostics.failure(IllegalStateException(PRIVATE_TEXT))
                assertEquals(2, logs.messages.count { it.startsWith("event=telegram_failure ") })
                assertPrivate(logs)
            }
        }

    @Test
    fun `consumed update still completes once and no signal means unknown`() =
        runBlocking {
            val handlers =
                TelegramHandlers().apply {
                    addHandler(
                        object : Handler {
                            override fun checkUpdate(update: Update): Boolean = true

                            override suspend fun handleUpdate(
                                bot: com.github.kotlintelegrambot.Bot,
                                update: Update,
                            ) {
                                update.consume()
                            }
                        },
                    )
                    addHandler(
                        object : Handler {
                            override fun checkUpdate(update: Update): Boolean = error(PRIVATE_TEXT)

                            override suspend fun handleUpdate(
                                bot: com.github.kotlintelegrambot.Bot,
                                update: Update,
                            ) = Unit
                        },
                    )
                }
            LogCapture().use { logs ->
                handlers.build().handleUpdate(com.github.kotlintelegrambot.bot { token = "test-token" }, Update(1))
                assertEquals("unknown", logs.completions.single().field("result"))
                assertEquals(2, logs.messages.size)
            }
        }

    @Test
    fun `sensitive dependency logging stays disabled when root logging is debug`() {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val previous = root.level
        try {
            root.level = ch.qos.logback.classic.Level.DEBUG
            listOf(
                "org.jooq.tools.LoggerListener",
                "org.postgresql",
                "com.zaxxer.hikari",
                "org.flywaydb",
                "okhttp3",
                "retrofit2",
            ).forEach { assertFalse(LoggerFactory.getLogger(it).isErrorEnabled, it) }
        } finally {
            root.level = previous
        }
    }
}

internal fun loggingFixture(repository: TrainingRepository = FakeTrainingRepository()): TestTelegramBot {
    val storage = InMemoryFlowStorage()
    return TestTelegramBot(
        TelegramBot(
            FakeProfileRepository(),
            TelegramProperties("secret-bot-token"),
            storage,
            FlowSessionManager(storage, listOf(TrainingFlowService(repository))),
            TrainingCreationMessageSessionStorage(),
            TrainingQueryService(repository),
            repository::delete,
        ),
        USER_ID,
    )
}

private fun String.field(name: String): String = split(' ').single { it.startsWith("$name=") }.substringAfter('=')

private fun assertPrivate(logs: LogCapture) {
    listOf(PRIVATE_TEXT, USER_ID.toString(), "secret-bot-token", "test-token", "profile:")
        .forEach { assertFalse(logs.rendered.contains(it), "Forbidden value in logs") }
}
