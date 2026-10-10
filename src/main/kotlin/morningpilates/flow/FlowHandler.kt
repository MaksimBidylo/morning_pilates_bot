package morningpilates.flow

interface FlowHandler {
    fun handle(state: FlowState, event: FlowEvent): FlowResult
}
