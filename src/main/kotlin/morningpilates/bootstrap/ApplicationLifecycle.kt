package morningpilates.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.routing.routing
import morningpilates.health.healthRoutes
import morningpilates.infrastructure.persistence.Database
import morningpilates.telegram.BotLifecycle
import java.util.concurrent.atomic.AtomicBoolean

internal fun Application.startApplicationResources(
    database: Database,
    createBot: () -> BotLifecycle,
) {
    val databaseClosed = AtomicBoolean()

    fun closeDatabase() {
        if (databaseClosed.compareAndSet(false, true)) database.close()
    }
    monitor.subscribe(ApplicationStopped) { closeDatabase() }

    var telegramBot: BotLifecycle? = null
    val botStopped = AtomicBoolean()

    fun stopBot() {
        val bot = telegramBot ?: return
        if (botStopped.compareAndSet(false, true)) bot.stop()
    }

    runCatching {
        telegramBot = createBot()
        monitor.subscribe(ApplicationStopping) { stopBot() }
        telegramBot.start()
        routing { healthRoutes() }
    }.onFailure { failure ->
        runCatching { stopBot() }.onFailure { failure.addSuppressed(it) }
        runCatching { closeDatabase() }.onFailure { failure.addSuppressed(it) }
    }.getOrThrow()
}
