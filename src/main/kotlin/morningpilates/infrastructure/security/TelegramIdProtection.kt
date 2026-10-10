package morningpilates.infrastructure.security

import io.ktor.server.application.Application
import io.ktor.util.AttributeKey

internal val TelegramIdProtectorKey = AttributeKey<TelegramIdProtector>("telegramIdProtector")

val Application.telegramIdProtector: TelegramIdProtector
    get() = attributes[TelegramIdProtectorKey]
