package morningpilates.telegram.command

import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.types.TelegramBotResult
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID
import java.util.concurrent.TimeUnit

internal enum class ReplyOperation(
    val logName: String,
) {
    Start("start"),
    Help("help"),
}

@Suppress("TooGenericExceptionCaught")
internal fun logReplyOperation(
    operation: ReplyOperation,
    send: () -> TelegramBotResult<Message>?,
) {
    val operationId = UUID.randomUUID()
    val started = System.nanoTime()
    var result = "unknown"
    var diagnostics = ""
    try {
        val response = send()
        diagnostics = (response as? TelegramBotResult.Error)?.safeDiagnostics().orEmpty()
        result =
            when (response) {
                is TelegramBotResult.Success -> "success"
                is TelegramBotResult.Error -> "technical_failure"
                null -> "unknown"
            }
    } catch (failure: Throwable) {
        result = "technical_failure"
        diagnostics = "error_source=exception exception_type=${failure.javaClass.name}"
        throw failure
    } finally {
        val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0)
        runCatching {
            LoggerFactory
                .getLogger("morningpilates.telegram.command.ReplyOperation")
                .atLevel(if (result == "technical_failure") Level.ERROR else Level.INFO)
                .log(
                    "event=telegram_operation_completed operation_id={} operation={} result={} duration_ms={}{}",
                    operationId,
                    operation.logName,
                    result,
                    duration,
                    if (diagnostics.isEmpty()) "" else " stage=reply method=sendMessage $diagnostics",
                )
        }
    }
}

private fun TelegramBotResult.Error.safeDiagnostics(): String =
    when (this) {
        is TelegramBotResult.Error.HttpError -> "error_source=http http_status=$httpCode"
        is TelegramBotResult.Error.TelegramApi -> "error_source=telegram_api api_error_code=$errorCode"
        is TelegramBotResult.Error.InvalidResponse -> "error_source=invalid_response http_status=$httpCode"
        is TelegramBotResult.Error.Unknown -> "error_source=sdk_unknown exception_type=${exception.javaClass.name}"
    }
