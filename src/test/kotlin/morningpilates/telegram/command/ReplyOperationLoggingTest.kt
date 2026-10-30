package morningpilates.telegram.command

import com.github.kotlintelegrambot.network.Response
import com.github.kotlintelegrambot.types.TelegramBotResult
import kotlinx.coroutines.runBlocking
import morningpilates.logging.renderedLogs
import morningpilates.telegram.logging.TelegramUpdateObserver
import morningpilates.telegram.support.TestTelegramBot
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val CANARY = "CANARY_144_OPERATION_NOT_A_REAL_SECRET_user_note_token"
private const val CANARY_ID = 998877665544L

class ReplyOperationLoggingTest {
    @ParameterizedTest
    @ValueSource(strings = ["start", "help"])
    fun `successful replies log exactly one completion per call with unique independent UUIDs`(command: String) {
        val output =
            renderedLogs {
                runBlocking {
                    testBot().use { bot ->
                        repeat(2) {
                            val response = bot.sendCommand("/$command $CANARY", senderId = CANARY_ID)
                            assertEquals(CANARY_ID, response.chatId)
                            val expectedPrefix = if (command == "start") "Morning Pilates Bot" else "<b>Помощь"
                            assertTrue(response.text.startsWith(expectedPrefix))
                        }
                        assertEquals(listOf("sendMessage", "sendMessage"), bot.apiCalls)
                    }
                }
            }
        val completions = assertRecords(output, command, "success", 2)
        val operationIds = completions.map { uuidField(it, "operation_id") }
        val receivedIds =
            output
                .lineSequence()
                .filter { "event=telegram_received" in it }
                .map { uuidField(it, "correlation_id") }
                .toSet()
        assertFalse("error_source=" in output || "exception_type=" in output || "stage=reply" in output)
        assertEquals(2, operationIds.toSet().size)
        assertTrue(operationIds.none { it in receivedIds })
    }

    @ParameterizedTest
    @ValueSource(strings = ["start", "help"])
    fun `Telegram API error logs technical failure without response description`(command: String) {
        val output =
            renderedLogs {
                runBlocking {
                    testBot().use { bot ->
                        bot.failNextSendMessage("https://telegram.invalid/bot$CANARY/$CANARY_ID SELECT '$CANARY'")
                        val replies = bot.sendCommandAndCollectReplies("/$command $CANARY", senderId = CANARY_ID)
                        assertTrue(replies.isEmpty())
                        assertEquals(listOf("sendMessage"), bot.apiCalls)
                    }
                }
            }
        assertRecords(output, command, "technical_failure", 1)
        assertTrue("ERROR" in output)
        assertTrue("stage=reply method=sendMessage error_source=telegram_api api_error_code=400" in output)
        assertFalse("http_status=" in output)
    }

    @ParameterizedTest
    @ValueSource(strings = ["start", "help"])
    fun `transport exception logs failure and preserves the existing error behavior`(command: String) {
        val failure = IllegalStateException("https://secret.invalid/$CANARY/$CANARY_ID")
        val output =
            renderedLogs {
                runBlocking {
                    testBot().use { bot ->
                        bot.beforeNextSendMessageResponse { throw failure }
                        val thrown = assertFailsWith<IllegalStateException> { bot.sendCommand("/$command") }
                        assertSame(failure, thrown.cause ?: thrown)
                    }
                }
            }
        assertRecords(output, command, "technical_failure", 1)
        assertTrue("error_source=sdk_unknown exception_type=java.lang.IllegalStateException" in output)
    }

    @Test
    fun `exception escaping SDK is rethrown unchanged with one safe failure record`() {
        val failure = AssertionError(CANARY).apply { addSuppressed(IllegalStateException(CANARY)) }
        val output =
            renderedLogs {
                runBlocking {
                    testBot().use { bot ->
                        bot.beforeNextSendMessageResponse { throw failure }
                        val thrown = assertFailsWith<AssertionError> { bot.sendCommand("/start") }
                        assertSame(failure, thrown.cause ?: thrown)
                    }
                }
            }
        assertRecords(output, "start", "technical_failure", 1)
        assertTrue("error_source=exception exception_type=java.lang.AssertionError" in output)
        assertFalse("Suppressed" in output)
    }

    @ParameterizedTest
    @MethodSource("sdkFailures")
    fun `typed SDK failures retain only safe metadata`(
        error: TelegramBotResult.Error,
        expected: String,
    ) {
        val output = renderedLogs { logReplyOperation(ReplyOperation.Help) { error } }
        val records = output.lineSequence().filter { "event=telegram_operation_completed" in it }.toList()
        assertEquals(1, records.size)
        assertTrue("ERROR" in records.single())
        assertTrue("operation=help result=technical_failure" in records.single())
        assertTrue("stage=reply method=sendMessage $expected" in records.single())
        assertFalse(CANARY in output || "https://" in output || "SELECT" in output || "Suppressed" in output)
    }

    companion object {
        @JvmStatic
        fun sdkFailures(): List<Array<Any>> {
            val unsafe = "https://secret.invalid/bot$CANARY SELECT '$CANARY'\n$CANARY"
            val failure =
                SocketTimeoutException(unsafe).apply {
                    initCause(IllegalStateException(unsafe))
                    addSuppressed(IllegalArgumentException(unsafe))
                }
            return listOf(
                arrayOf(TelegramBotResult.Error.HttpError(503, unsafe), "error_source=http http_status=503"),
                arrayOf(
                    TelegramBotResult.Error.TelegramApi(403, unsafe),
                    "error_source=telegram_api api_error_code=403",
                ),
                arrayOf(
                    TelegramBotResult.Error.InvalidResponse(502, unsafe, Response(unsafe, false, 400, unsafe)),
                    "error_source=invalid_response http_status=502",
                ),
                arrayOf(
                    TelegramBotResult.Error.Unknown(failure),
                    "error_source=sdk_unknown exception_type=java.net.SocketTimeoutException",
                ),
            )
        }
    }

    @Test
    fun `missing result remains unknown instead of treating normal return as success`() {
        val output = renderedLogs { logReplyOperation(ReplyOperation.Help) { null } }
        val records = output.lineSequence().filter { "event=telegram_operation_completed" in it }.toList()
        assertEquals(1, records.size)
        assertTrue("operation=help result=unknown" in records.single())
        assertFalse("result=success" in output)
        assertFalse("error_source=" in output || "stage=reply" in output)
    }
}

private fun testBot(): TestTelegramBot =
    TestTelegramBot(chatId = CANARY_ID) {
        addHandler(TelegramUpdateObserver())
        registerStartCommand()
        registerHelpCommand()
    }

private fun assertRecords(
    output: String,
    command: String,
    result: String,
    count: Int,
): List<String> {
    val records = output.lineSequence().filter { "event=telegram_operation_completed" in it }.toList()
    assertEquals(count, records.size)
    assertEquals(count, output.lineSequence().count { "event=telegram_received" in it })
    records.forEach { record ->
        assertTrue("operation=$command result=$result" in record)
        uuidField(record, "operation_id")
        assertTrue(record.substringAfter("duration_ms=").substringBefore(' ').toLong() >= 0)
        assertFalse("correlation_id=" in record)
    }
    assertFalse(CANARY in output || CANARY_ID.toString() in output || "https://" in output || "SELECT" in output)
    return records
}

private fun uuidField(
    record: String,
    field: String,
): UUID = UUID.fromString(record.substringAfter("$field=").substringBefore(' '))
