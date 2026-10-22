package morningpilates.diagnostics

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.util.Collections

internal class LogCapture : AutoCloseable {
    private val logger = LoggerFactory.getLogger(RequestDiagnostics::class.java) as Logger
    private val appender =
        ListAppender<ILoggingEvent>().apply {
            list = Collections.synchronizedList(mutableListOf())
            start()
        }

    init {
        logger.addAppender(appender)
    }

    val events: List<ILoggingEvent> get() = synchronized(appender.list) { appender.list.toList() }
    val messages: List<String> get() = events.map { it.formattedMessage }
    val completions: List<String> get() = messages.filter { it.startsWith("event=telegram_completed ") }
    val rendered: String get() =
        events.joinToString("\n") {
            it.formattedMessage + (it.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "")
        }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
    }
}
