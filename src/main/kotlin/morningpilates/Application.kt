package morningpilates

import io.ktor.server.application.*
import io.ktor.server.config.property
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.TelegramProperties

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val telegramProperties = property<TelegramProperties>("telegram")

    val telegramBot = TelegramBot(
        properties = telegramProperties,
    )
    telegramBot.start()

    monitor.subscribe(ApplicationStopping) {
        telegramBot.stop()
    }

    routing {
        get("/health") {
            call.respondText("OK")
        }
    }

}
