package morningpilates.flow

import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState

interface FlowHandler {
    fun supports(state: FlowState): Boolean

    fun handle(
        state: FlowState,
        event: FlowEvent,
    ): FlowResult
}
