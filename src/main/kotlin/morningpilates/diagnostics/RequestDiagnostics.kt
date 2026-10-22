package morningpilates.diagnostics

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID

private const val MAX_CAUSE_DEPTH = 8

/** Signals carry no user values. They only affect the current update's single completion record. */
object RequestDiagnostics {
    private val logger = LoggerFactory.getLogger(RequestDiagnostics::class.java)
    private val current = ThreadLocal<Processing?>()

    // This boundary observes all failures before rethrowing; it must also close the record on cancellation.
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> process(
        kind: String,
        route: String = "unspecified",
        action: suspend () -> T,
    ): T {
        val processing = Processing(UUID.randomUUID().toString(), kind, route)
        return withContext(current.asContextElement(processing)) {
            logger.info("event=telegram_received correlation_id={} kind={} route={}", processing.id, kind, route)
            try {
                action()
            } catch (failure: Throwable) {
                failure(failure)
                throw failure
            } finally {
                val elapsed = (System.nanoTime() - processing.started) / 1_000_000
                val message = "event=telegram_completed correlation_id={} kind={} route={} result={} duration_ms={}"
                val arguments = arrayOf<Any>(processing.id, kind, route, processing.result, elapsed)
                when (processing.result) {
                    "technical_failure" -> logger.error(message, *arguments)
                    "unknown" -> logger.warn(message, *arguments)
                    else -> logger.info(message, *arguments)
                }
            }
        }
    }

    fun success() {
        current.get()?.let { if (it.result == "unknown") it.result = "success" }
    }

    fun rejected() {
        current.get()?.let { if (it.result != "technical_failure") it.result = "rejected" }
    }

    fun failure(failure: Throwable) = reportFailure(failure, null)

    private fun reportFailure(
        failure: Throwable,
        httpStatus: Int?,
    ) {
        val processing = current.get() ?: return
        processing.result = "technical_failure"
        if (processing.reportedFailure) return
        processing.reportedFailure = true
        // Original messages, SQL, URLs, causes and suppressed messages can contain personal data.
        logger.error(
            "event=telegram_failure correlation_id={} kind={} route={} http_status={} sql_state={}",
            processing.id,
            processing.kind,
            processing.route,
            httpStatus ?: "none",
            generateSequence(failure) {
                it.cause
            }.take(MAX_CAUSE_DEPTH)
                .filterIsInstance<SQLException>()
                .firstOrNull()
                ?.sqlState
                ?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) }
                ?: "none",
            safeException(failure),
        )
    }

    fun apiFailure(status: Int) {
        reportFailure(IllegalStateException("telegram_api_failure"), status)
    }

    fun pollingFailure() {
        logger.error("event=telegram_polling_failure result=technical_failure")
    }

    private class Processing(
        val id: String,
        val kind: String,
        val route: String,
    ) {
        val started = System.nanoTime()
        var result = "unknown"
        var reportedFailure = false
    }
}

/** Copies only exception type and code locations; never delegates to Throwable.toString(). */
internal fun safeException(
    failure: Throwable,
    depth: Int = 0,
): Throwable =
    RuntimeException(
        failure.javaClass.name,
        failure.cause?.takeIf { depth < MAX_CAUSE_DEPTH - 1 && it !== failure }?.let { safeException(it, depth + 1) },
    ).apply {
        stackTrace = failure.stackTrace
    }
