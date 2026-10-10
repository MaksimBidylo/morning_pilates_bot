package morningpilates.flow

interface FlowHandler {
    fun supports(state: FlowState): Boolean
    fun handle(state: FlowState, event: FlowEvent): FlowResult
}
