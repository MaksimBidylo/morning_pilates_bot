package morningpilates.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.config.property
import morningpilates.config.database.DatabaseProperties
import morningpilates.config.security.TelegramIdProperties
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.infrastructure.persistence.Database
import morningpilates.infrastructure.persistence.DatabaseKey
import morningpilates.infrastructure.persistence.PostgresDatabase
import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.infrastructure.security.TelegramIdProtectorKey
import morningpilates.telegram.BotLifecycle

internal fun Application.configureApplication(
    databaseFactory: (DatabaseProperties) -> Database = { PostgresDatabase.connect(it) },
    botFactory: (TelegramProperties, FlowStorage, FlowSessionManager) -> BotLifecycle,
) {
    val telegramIdKeys = property<TelegramIdProperties>("security.telegramId").decodeKeys()
    val telegramIdProtector = TelegramIdProtector(telegramIdKeys.encryptionKey, telegramIdKeys.lookupKey)
    attributes.put(TelegramIdProtectorKey, telegramIdProtector)

    val telegramProperties = property<TelegramProperties>("telegram")
    val databaseProperties = property<DatabaseProperties>("db")
    val database = databaseFactory(databaseProperties)
    attributes.put(DatabaseKey, database)

    startApplicationResources(database) {
        val flowStorage: FlowStorage = InMemoryFlowStorage()
        val sessionManager =
            createTrainingFlowSessionManager(
                storage = flowStorage,
                dsl = database.dsl,
                protector = telegramIdProtector,
            )
        botFactory(telegramProperties, flowStorage, sessionManager)
    }
}
