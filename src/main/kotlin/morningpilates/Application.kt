package morningpilates

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.config.property
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.routing
import morningpilates.config.DatabaseProperties
import morningpilates.config.TelegramProperties
import morningpilates.health.healthRoutes
import morningpilates.infrastructure.persistence.Database
import morningpilates.infrastructure.persistence.DatabaseKey
import morningpilates.infrastructure.persistence.PostgresDatabase
import morningpilates.telegram.BotLifecycle
import morningpilates.telegram.TelegramBot
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureApplication(botFactory = ::TelegramBot)
}

internal fun Application.configureApplication(
    databaseFactory: (DatabaseProperties) -> Database = { PostgresDatabase.connect(it) },
    botFactory: (TelegramProperties) -> BotLifecycle,
) {
    val telegramProperties = property<TelegramProperties>("telegram")
    val databaseProperties = property<DatabaseProperties>("db")
    val database = databaseFactory(databaseProperties)
    attributes.put(DatabaseKey, database)

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
        telegramBot = botFactory(telegramProperties)
        monitor.subscribe(ApplicationStopping) { stopBot() }
        telegramBot.start()
        routing { healthRoutes() }
    }.onFailure { failure ->
        runCatching { stopBot() }.onFailure { failure.addSuppressed(it) }
        runCatching { closeDatabase() }.onFailure { failure.addSuppressed(it) }
    }.getOrThrow()
}
