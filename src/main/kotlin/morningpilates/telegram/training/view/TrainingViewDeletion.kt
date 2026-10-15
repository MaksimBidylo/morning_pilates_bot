package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.Bot
import morningpilates.training.TrainingId
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId

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
            showCommittedTrainingViewContent(
                userId,
                viewStorage,
                active,
                TrainingViewContent(
                    training = Result.success(active.latestTraining),
                    dateSearch = active.dateSearch,
                ),
            )
        }

        TrainingViewAction.ConfirmDelete, TrainingViewAction.RetryDelete -> {
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
    val result = deleteTraining(userId, target.id)
    when {
        result.isFailure -> {
            showCommittedTrainingViewContent(
                userId,
                viewStorage,
                consumed,
                TrainingViewContent(
                    training = Result.success(active.latestTraining),
                    dateSearch = active.dateSearch,
                    deletionTarget = target,
                    deletionOutcome = TrainingDeletionOutcome.Failed,
                ),
            )
        }

        result.getOrNull() == true -> {
            completeTrainingDeletion(userId, consumed, viewStorage, queryService, target.id)
        }

        else -> {
            showCommittedTrainingViewContent(
                userId,
                viewStorage,
                consumed,
                TrainingViewContent(deletionOutcome = TrainingDeletionOutcome.Unavailable),
            )
        }
    }
}

private fun Bot.completeTrainingDeletion(
    userId: TelegramUserId,
    consumed: ActiveTrainingView,
    viewStorage: TrainingViewStorage,
    queryService: TrainingQueryService,
    trainingId: TrainingId,
) {
    val committed =
        consumed.copy(
            dateSearch = consumed.dateSearch?.withoutTraining(trainingId),
            latestTraining = null,
            deletionTarget = null,
            deletionOutcome = TrainingDeletionOutcome.Deleted,
        )
    if (viewStorage.putIfCurrent(userId, consumed, committed)) {
        val content =
            TrainingViewContent(
                training = if (committed.dateSearch == null) queryService.findLatest(userId) else Result.success(null),
                dateSearch = committed.dateSearch,
                deletionOutcome = TrainingDeletionOutcome.Deleted,
            )
        showTrainingViewContent(userId, committed.chatId, viewStorage, committed, content)
    }
}

private fun TrainingDateSearch.withoutTraining(trainingId: TrainingId): TrainingDateSearch {
    val remaining = trainings.filterNot { it.id == trainingId }
    val position = minOf(index, remaining.lastIndex).coerceAtLeast(0)
    return copy(trainings = remaining, index = position)
}
