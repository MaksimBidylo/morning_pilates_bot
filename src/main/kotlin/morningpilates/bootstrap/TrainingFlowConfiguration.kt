package morningpilates.bootstrap

import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingRepository

internal fun createTrainingFlowSessionManager(
    storage: FlowStorage,
    trainingRepository: TrainingRepository,
): FlowSessionManager {
    val trainingFlowService = TrainingFlowService(trainingRepository)
    return FlowSessionManager(storage, listOf(trainingFlowService))
}
