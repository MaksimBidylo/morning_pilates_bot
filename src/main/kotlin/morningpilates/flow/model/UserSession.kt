package morningpilates.flow.model

import java.time.Instant

data class UserSession(
    val state: FlowState,
    val createdAt: Instant,
    val sessionId: SessionId = SessionId.new(),
)
