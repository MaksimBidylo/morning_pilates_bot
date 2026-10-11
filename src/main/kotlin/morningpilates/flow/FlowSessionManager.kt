package morningpilates.flow

import morningpilates.user.TelegramUserId

class FlowSessionManager(
    private val storage: FlowStorage,
    private val handlers: List<FlowHandler>,
) {
    companion object {
        private const val NO_ACTIVE_SESSION_REASON = "Нет активной сессии"
        private const val SESSION_CHANGED_REASON = "Состояние сессии изменилось"
        private const val NO_SUPPORTING_HANDLER_REASON = "Не найден подходящий обработчик для текущего состояния"
        private const val CONFLICTING_HANDLERS_MESSAGE = "Несколько обработчиков поддерживают текущее состояние"
    }

    fun handle(input: FlowEvent.UserInput): FlowResult {
        val userId = input.telegramUserId
        val session =
            storage.get(userId)
                ?: return FlowResult.Rejected(NO_ACTIVE_SESSION_REASON)
        val result = handleEvent(session.state, input)
        val appliedResult = applyResult(userId, session, result)

        return if (appliedResult is FlowResult.Transition && appliedResult.nextState is AwaitingContinuation) {
            val updatedSession = session.copy(state = appliedResult.nextState)
            val continuationResult =
                handleEvent(updatedSession.state, FlowEvent.Continue(userId, updatedSession.sessionId))
            applyResult(userId, updatedSession, continuationResult)
        } else {
            appliedResult
        }
    }

    private fun handleEvent(
        state: FlowState,
        event: FlowEvent,
    ): FlowResult {
        val supportedHandlers = handlers.filter { it.supports(state) }
        if (supportedHandlers.isEmpty()) {
            return FlowResult.Rejected(NO_SUPPORTING_HANDLER_REASON)
        }
        check(supportedHandlers.size == 1) {
            CONFLICTING_HANDLERS_MESSAGE
        }
        return supportedHandlers.single().handle(state, event)
    }

    private fun applyResult(
        userId: TelegramUserId,
        session: UserSession,
        result: FlowResult,
    ): FlowResult =
        when (result) {
            is FlowResult.Rejected -> {
                result
            }

            is FlowResult.Transition -> {
                val updatedSession = session.copy(state = result.nextState)
                if (storage.update(userId, session, updatedSession)) {
                    result
                } else {
                    FlowResult.Rejected(SESSION_CHANGED_REASON)
                }
            }

            FlowResult.Completed, FlowResult.Cancelled -> {
                if (storage.remove(userId, session)) {
                    result
                } else {
                    FlowResult.Rejected(SESSION_CHANGED_REASON)
                }
            }
        }
}
