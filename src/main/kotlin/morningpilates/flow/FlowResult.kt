package morningpilates.flow

sealed interface FlowResult {
    data class Transition(
        val nextState: FlowState,
    ) : FlowResult

    data class Rejected(
        val reason: String,
    ) : FlowResult

    data object Completed : FlowResult

    data object Cancelled : FlowResult
}
