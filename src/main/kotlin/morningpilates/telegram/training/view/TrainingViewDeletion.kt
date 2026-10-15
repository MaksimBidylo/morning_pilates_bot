package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.Bot
import morningpilates.training.TrainingId
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId

private const val TRAINING_DELETED = "Тренировка удалена."

internal fun Bot.handleTrainingDeletion(
    userId: TelegramUserId,
    active: ActiveTrainingView,
    action: TrainingViewAction,
    viewStorage: TrainingViewStorage,
    queryService: TrainingQueryService,
    deleteTraining: (TelegramUserId, TrainingId) -> Result<Boolean>,
) {
    when (action) {
        TrainingViewAction.Delete -> {
            val selected =
                active.dateSearch?.let { it.trainings.getOrNull(it.index) } ?: active.latestTraining ?: return
            showTrainingViewContent(
                userId,
                active.chatId,
                viewStorage,
                active,
                TrainingViewContent(
                    training = Result.success(active.latestTraining),
                    dateSearch = active.dateSearch,
                    deletionTarget = selected,
                ),
            )
        }

        TrainingViewAction.CancelDelete -> {
            showTrainingViewContent(
                userId,
                active.chatId,
                viewStorage,
                active,
                TrainingViewContent(
                    training = Result.success(active.latestTraining),
                    dateSearch = active.dateSearch,
                ),
            )
        }

        TrainingViewAction.ConfirmDelete -> {
            confirmTrainingDeletion(userId, active, viewStorage, queryService, deleteTraining)
        }

        else -> {
            return
        }
    }
}

private fun Bot.confirmTrainingDeletion(
    userId: TelegramUserId,
    active: ActiveTrainingView,
    viewStorage: TrainingViewStorage,
    queryService: TrainingQueryService,
    deleteTraining: (TelegramUserId, TrainingId) -> Result<Boolean>,
) {
    val target = active.deletionTarget ?: return
    val consumed = active.copy(token = viewStorage.newToken(), actions = emptySet())
    if (!viewStorage.putIfCurrent(userId, active, consumed)) return
    if (deleteTraining(userId, target.id).getOrNull() != true) {
        viewStorage.putIfCurrent(userId, consumed, active)
    } else {
        val search = active.dateSearch
        val content =
            if (search == null) {
                TrainingViewContent(training = queryService.findLatest(userId), notice = TRAINING_DELETED)
            } else {
                TrainingViewContent(
                    dateSearch = search.withoutTraining(target.id),
                    notice = TRAINING_DELETED,
                )
            }
        showTrainingViewContent(userId, active.chatId, viewStorage, consumed, content)
    }
}

private fun TrainingDateSearch.withoutTraining(trainingId: TrainingId): TrainingDateSearch {
    val remaining = trainings.filterNot { it.id == trainingId }
    val position = minOf(index, remaining.lastIndex).coerceAtLeast(0)
    return copy(trainings = remaining, index = position)
}
