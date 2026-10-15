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
                TrainingViewContent(Result.success(active.latestTraining), active.dateSearch, selected),
            )
        }

        TrainingViewAction.CancelDelete -> {
            showTrainingViewContent(
                userId,
                active.chatId,
                viewStorage,
                active,
                TrainingViewContent(Result.success(active.latestTraining), active.dateSearch),
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
                val remaining = search.trainings.filterNot { it.id == target.id }
                val position = minOf(search.index, remaining.lastIndex).coerceAtLeast(0)
                TrainingViewContent(
                    dateSearch = search.copy(trainings = remaining, index = position),
                    notice = TRAINING_DELETED,
                )
            }
        showTrainingViewContent(userId, active.chatId, viewStorage, consumed, content)
    }
}
