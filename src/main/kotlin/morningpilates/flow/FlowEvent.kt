package morningpilates.flow

sealed interface FlowEvent {
    data class UserInput(val text: String) : FlowEvent

    data object Continue : FlowEvent
}
