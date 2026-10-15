package morningpilates.telegram.training.view

import morningpilates.training.SavedTraining
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import java.time.LocalDate

data class TrainingDateSearch(
    val date: LocalDate? = null,
    val trainings: List<SavedTraining> = emptyList(),
    val index: Int = 0,
    val warning: String? = null,
    val loadFailed: Boolean = false,
) {
    val isWaitingForDate: Boolean get() = date == null
}

internal fun TrainingQueryService.loadDateSearch(
    userId: TelegramUserId,
    date: LocalDate,
): TrainingDateSearch =
    findByDate(userId, date).fold(
        onSuccess = { TrainingDateSearch(date = date, trainings = it.toList()) },
        onFailure = { TrainingDateSearch(date = date, loadFailed = true) },
    )
