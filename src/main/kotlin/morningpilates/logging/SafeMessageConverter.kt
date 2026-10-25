package morningpilates.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import java.sql.SQLException

class SafeMessageConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String {
        val exception = event.throwableProxy
        if (exception != null) {
            val diagnostics =
                generateSequence(exception) { it.cause }.take(MAX_CAUSES).joinToString(" caused_by=") { cause ->
                    val state = ((cause as? ThrowableProxy)?.throwable as? SQLException)?.sqlState
                    val sqlState = state?.takeIf { it.matches(Regex("[A-Z0-9]{5}")) } ?: "none"
                    val location = cause.stackTraceElementProxyArray?.firstOrNull()?.stackTraceElement
                    "exception_type=${cause.className} sql_state=$sqlState at=$location"
                }
            val safeEvent = safeEvents[event.loggerName]?.takeIf { it == event.message }
            return listOfNotNull(safeEvent, diagnostics).joinToString(" ")
        }
        return if (sensitiveLoggers.any { event.loggerName.startsWith(it) }) {
            poolLifecycle[event.formattedMessage] ?: "event=dependency_message"
        } else {
            event.formattedMessage
        }
    }

    private companion object {
        const val MAX_CAUSES = 8
        val safeEvents =
            mapOf(
                "morningpilates.Application" to "event=application_failure",
                "morningpilates.telegram.TelegramBot" to
                    "event=telegram_polling_failure operation=getUpdates transport=long_polling",
            )
        val sensitiveLoggers =
            listOf(
                "org.jooq",
                "org.postgresql",
                "org.flywaydb",
                "com.zaxxer.hikari",
                "okhttp3",
                "retrofit2",
                "com.github.kotlintelegrambot",
            )
        val poolLifecycle =
            mapOf(
                "morning-pilates - Starting..." to "event=database_pool_starting",
                "morning-pilates - Start completed." to "event=database_pool_started",
                "morning-pilates - Shutdown initiated..." to "event=database_pool_stopping",
                "morning-pilates - Shutdown completed." to "event=database_pool_stopped",
            )
    }
}
