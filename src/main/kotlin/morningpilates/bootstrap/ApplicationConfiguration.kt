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
import morningpilates.infrastructure.persistence.PostgresDatabase
import morningpilates.infrastructure.persistence.PostgresProfileRepository
import morningpilates.infrastructure.persistence.PostgresTrainingRepository
import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.profile.ProfileRepository
import morningpilates.telegram.BotLifecycle
import morningpilates.training.TrainingId
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId

internal fun Application.configureApplication(
    databaseFactory: (DatabaseProperties) -> Database = { PostgresDatabase.connect(it) },
    botFactory: (
        TelegramProperties,
        FlowStorage,
        FlowSessionManager,
        TrainingQueryService,
        ProfileRepository,
        (TelegramUserId, TrainingId) -> Result<Boolean>,
    ) -> BotLifecycle,
) {
    val telegramIdKeys = property<TelegramIdProperties>("security.telegramId").decodeKeys()
    val telegramIdProtector = TelegramIdProtector(telegramIdKeys.encryptionKey, telegramIdKeys.lookupKey)
    attributes.put(TelegramIdProtectorKey, telegramIdProtector)

    val telegramProperties = property<TelegramProperties>("telegram")
    val databaseProperties = property<DatabaseProperties>("db")
    val database = databaseFactory(databaseProperties)

    startApplicationResources(database) {
        val flowStorage: FlowStorage = InMemoryFlowStorage()
        val trainingRepository = PostgresTrainingRepository(database.dsl, telegramIdProtector)
        val sessionManager =
            createTrainingFlowSessionManager(
                storage = flowStorage,
                trainingRepository = trainingRepository,
            )
        botFactory(
            telegramProperties,
            flowStorage,
            sessionManager,
            TrainingQueryService(trainingRepository),
            PostgresProfileRepository(database.dsl, telegramIdProtector),
            trainingRepository::delete,
        )
    }
}
