package morningpilates.bootstrap

import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import morningpilates.infrastructure.security.TelegramIdProtector

internal val TelegramIdProtectorKey = AttributeKey<TelegramIdProtector>("telegramIdProtector")

val Application.telegramIdProtector: TelegramIdProtector
    get() = attributes[TelegramIdProtectorKey]
