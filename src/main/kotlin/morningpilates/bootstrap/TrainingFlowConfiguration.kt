package morningpilates.bootstrap

import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.infrastructure.persistence.PostgresTrainingRepository
import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.training.TrainingFlowService
import org.jooq.DSLContext

internal fun createTrainingFlowSessionManager(
    storage: FlowStorage,
    dsl: DSLContext,
    protector: TelegramIdProtector,
): FlowSessionManager {
    val trainingRepository = PostgresTrainingRepository(dsl, protector)
    val trainingFlowService = TrainingFlowService(trainingRepository)
    return FlowSessionManager(storage, listOf(trainingFlowService))
}
