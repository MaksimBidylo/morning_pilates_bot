package morningpilates

import io.ktor.server.application.*
import io.ktor.server.config.property
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import morningpilates.health.healthRoutes
import morningpilates.telegram.BotLifecycle
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.TelegramProperties

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureApplication(::TelegramBot)
}

internal fun Application.configureApplication(botFactory: (TelegramProperties) -> BotLifecycle) {
    val telegramProperties = property<TelegramProperties>("telegram")

    val telegramBot = botFactory(telegramProperties)
    telegramBot.start()

    monitor.subscribe(ApplicationStopping) {
        telegramBot.stop()
    }

    routing {
        healthRoutes()
    }

}
