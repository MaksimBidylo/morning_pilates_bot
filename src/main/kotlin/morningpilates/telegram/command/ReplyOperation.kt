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
    try {
        result =
            when (send()) {
                is TelegramBotResult.Success -> "success"
                is TelegramBotResult.Error -> "technical_failure"
                null -> "unknown"
            }
    } catch (failure: Throwable) {
        result = "technical_failure"
        throw failure
    } finally {
        val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceAtLeast(0)
        runCatching {
            LoggerFactory
                .getLogger("morningpilates.telegram.command.ReplyOperation")
                .atLevel(if (result == "technical_failure") Level.ERROR else Level.INFO)
                .log(
                    "event=telegram_operation_completed operation_id={} operation={} result={} duration_ms={}",
                    operationId,
                    operation.logName,
                    result,
                    duration,
                )
        }
    }
}
