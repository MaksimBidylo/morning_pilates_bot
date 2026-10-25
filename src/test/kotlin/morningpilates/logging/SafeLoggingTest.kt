package morningpilates.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.handlers.ErrorHandler
import com.github.kotlintelegrambot.errors.RetrieveUpdatesError
import com.github.kotlintelegrambot.logging.LogLevel
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.profile.FakeProfileRepository
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingQueryService
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.sql.SQLException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CANARY = "CANARY_144_NOT_A_REAL_SECRET_message_note_9988776655"

class SafeLoggingTest {
    @Test
    fun `registered SDK polling hook omits untrusted description and keeps SDK logging off`() {
        val storage = InMemoryFlowStorage()
        val repository = FakeTrainingRepository()
        val telegram =
            TelegramBot(
                FakeProfileRepository(),
                TelegramProperties(CANARY),
                storage,
                FlowSessionManager(storage, emptyList()),
                TrainingCreationMessageSessionStorage(),
                TrainingQueryService(repository),
                repository::delete,
            )
        val sdk =
            TelegramBot::class.java
                .getDeclaredField("bot")
                .apply { isAccessible = true }
                .get(telegram) as Bot
        val dispatcher =
            Bot::class.java
                .getDeclaredField("dispatcher")
                .apply { isAccessible = true }
                .get(sdk)
        val level =
            Dispatcher::class.java
                .getDeclaredField("logLevel")
                .apply { isAccessible = true }
                .get(dispatcher)
        assertEquals(LogLevel.None, level)
        val handlers =
            Dispatcher::class.java
                .getDeclaredField("errorHandlers")
                .apply { isAccessible = true }
                .get(dispatcher) as List<*>
        val error = RetrieveUpdatesError("https://telegram.invalid/bot$CANARY/getUpdates?password=$CANARY")
        val output =
            renderedLogs {
                repeat(2) { (handlers.single() as ErrorHandler).invoke(sdk, error) }
            }
        assertEquals(2, output.lineSequence().count { "event=telegram_polling_failure" in it })
        assertTrue("ERROR" in output && "operation=getUpdates transport=long_polling" in output)
        assertFalse(CANARY in output || "https://" in output || "correlation" in output)
    }

    @Test
    fun `only exact application events from their designated loggers survive exceptions`() {
        val failure =
            IllegalStateException("https://secret.invalid/$CANARY", SQLException("SELECT '$CANARY'", "08006")).apply {
                addSuppressed(IllegalArgumentException(CANARY))
            }
        val output =
            renderedLogs {
                val application = LoggerFactory.getLogger("morningpilates.Application")
                application.error("event=application_failure", failure)
                LoggerFactory.getLogger("morningpilates.telegram.TelegramBot").error(
                    "event=telegram_polling_failure operation=getUpdates transport=long_polling",
                    failure,
                )
                application.error("event=unknown_service_event", failure)
                application.error("event=application_failure $CANARY", failure)
                application.error("event={}", "application_failure", failure)
                LoggerFactory
                    .getLogger("morningpilates.ApplicationImposter")
                    .error("event=application_failure", failure)
                LoggerFactory.getLogger("org.jooq.tools.LoggerListener").error("event=application_failure", failure)
                application.error(CANARY, SQLException(CANARY, CANARY))
            }
        assertEquals(1, output.lineSequence().count { "event=application_failure" in it })
        assertEquals(1, output.lineSequence().count { "event=telegram_polling_failure" in it })
        assertEquals(2, output.lineSequence().count { "event=" in it })
        assertTrue("ERROR" in output && "morningpilates.Application" in output && "TelegramBot" in output)
        assertTrue("exception_type=java.sql.SQLException sql_state=08006" in output)
        assertTrue("sql_state=none" in output && "at=morningpilates.logging.SafeLoggingTest" in output)
        assertFalse(CANARY in output || "https://" in output || "SELECT" in output || "unknown_service_event" in output)
        assertFalse("IllegalArgumentException" in output || "Suppressed" in output)
    }

    @Test
    fun `application debug and trace do not enable dependency debug or leak warnings and errors`() {
        listOf("DEBUG", "TRACE").forEach { level ->
            val output =
                renderedLogs(level) {
                    listOf(
                        "org.jooq.tools.LoggerListener",
                        "org.postgresql",
                        "org.flywaydb",
                        "com.zaxxer.hikari",
                        "okhttp3",
                        "retrofit2",
                        "com.github.kotlintelegrambot",
                    ).forEach { name ->
                        val logger = LoggerFactory.getLogger(name)
                        assertFalse(logger.isDebugEnabled || logger.isTraceEnabled)
                        assertTrue(logger.isInfoEnabled && logger.isWarnEnabled && logger.isErrorEnabled)
                        logger.debug(CANARY)
                        logger.trace(CANARY)
                        logger.warn("SELECT '$CANARY' https://secret.invalid/$CANARY")
                        logger.error(
                            CANARY,
                            IllegalStateException(CANARY, SQLException(CANARY, "08006")).apply {
                                addSuppressed(IllegalArgumentException(CANARY))
                            },
                        )
                    }
                    val application = LoggerFactory.getLogger("morningpilates.logging.test")
                    application.debug("application_debug_enabled")
                    application.trace("application_trace_enabled")
                }
            assertFalse(CANARY in output || "SELECT" in output || "secret.invalid" in output)
            assertEquals(7, output.lineSequence().count { "WARN" in it && "event=dependency_message" in it })
            assertEquals(7, output.lineSequence().count { "ERROR" in it && "sql_state=08006" in it })
            assertTrue("exception_type=java.sql.SQLException" in output && "SafeLoggingTest" in output)
            assertTrue("application_debug_enabled" in output)
            assertEquals(level == "TRACE", "application_trace_enabled" in output)
        }
    }

    @Test
    fun `real application startup failure cannot bypass safe logging through stderr`() {
        val classpath = System.getProperty("test.runtimeClasspath", System.getProperty("java.class.path"))
        val command =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                "morningpilates.ApplicationKt",
            ).redirectErrorStream(true)
        command.environment().putAll(
            mapOf(
                "PORT" to "0",
                "LOG_LEVEL" to "INFO",
                "TELEGRAM_BOT_TOKEN" to CANARY,
                "TELEGRAM_ID_ENCRYPTION_KEY" to Base64.getEncoder().encodeToString(ByteArray(32)),
                "TELEGRAM_ID_LOOKUP_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
                "DB_HOST" to "127.0.0.1",
                "DB_PORT" to "1",
                "DB_DATABASE" to CANARY,
                "DB_USER" to CANARY,
                "DB_PASSWORD" to CANARY,
                "DB_SSL_MODE" to CANARY,
            ),
        )
        val process = command.start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Application did not fail fast")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(1, process.exitValue())
            assertFalse(CANARY in output)
            assertTrue("event=database_pool_starting" in output)
            assertTrue("event=application_failure" in output)
            assertTrue("ERROR" in output && "exception_type=org.postgresql.util.PSQLException" in output)
            assertTrue("sql_state=08001" in output)
        } finally {
            process.destroyForcibly()
        }
    }
}

internal fun renderedLogs(
    logLevel: String = "TRACE",
    action: () -> Unit,
): String {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    val stdout = System.out
    val stderr = System.err
    val bytes = ByteArrayOutputStream()
    val capture = PrintStream(bytes, true, Charsets.UTF_8)

    fun configure() {
        JoranConfigurator()
            .apply { this.context = context }
            .doConfigure(SafeLoggingTest::class.java.getResource("/logback.xml"))
    }
    try {
        System.setOut(capture)
        System.setErr(capture)
        context.reset()
        context.putProperty("LOG_LEVEL", logLevel)
        configure()
        action()
        return bytes.toString(Charsets.UTF_8)
    } finally {
        System.setOut(stdout)
        System.setErr(stderr)
        context.reset()
        configure()
        capture.close()
    }
}
