package morningpilates.telegram

import morningpilates.flow.FlowStorage
import morningpilates.flow.UserSession
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.populatedTrainingDetails
import morningpilates.training.snapshot
import morningpilates.user.TelegramUserId
import java.time.Instant

internal fun startSessionAwaitingConfirmation(
    storage: FlowStorage,
    telegramUserId: TelegramUserId,
): UserSession {
    val details = populatedTrainingDetails()
    val session =
        UserSession(
            state = WaitingConfirmation(details),
            createdAt = Instant.EPOCH,
        )
    check(storage.start(telegramUserId, session))
    return session.copy(state = WaitingConfirmation(details.snapshot()))
}
