package morningpilates.flow

class FlowSessionManager(
    private val storage: FlowStorage,
    private val handlers: List<FlowHandler>,
) {
    companion object {
        private const val NO_ACTIVE_SESSION_REASON = "Нет активной сессии"
        private const val SESSION_CHANGED_REASON = "Состояние сессии изменилось"
        private const val NO_SUPPORTING_HANDLER_REASON = "Не найден подходящий обработчик для текущего состояния"
    }

    fun handle(userId: Long, input: FlowEvent.UserInput): FlowResult {
        val session = storage.get(userId)
            ?: return FlowResult.Rejected(NO_ACTIVE_SESSION_REASON)
        val supportedHandlers = handlers.filter { it.supports(session.state) }
        if (supportedHandlers.isEmpty()) {
            return FlowResult.Rejected(NO_SUPPORTING_HANDLER_REASON)
        }
        val handler = supportedHandlers.single()
        val result = handler.handle(session.state, input)

        return when (result) {
            is FlowResult.Transition -> {
                val updatedSession = session.copy(state = result.nextState)
                if (storage.update(userId, session, updatedSession)) {
                    result
                } else {
                    FlowResult.Rejected(SESSION_CHANGED_REASON)
                }
            }

            else -> TODO()
        }
    }
}
