package morningpilates.flow

import java.time.Instant

data class UserSession(
    val state: FlowState,
    val createdAt: Instant,
)
