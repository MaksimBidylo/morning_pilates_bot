package morningpilates.flow.model

import java.util.UUID

@JvmInline
value class SessionId(
    val value: UUID,
) {
    companion object {
        fun new(): SessionId = SessionId(UUID.randomUUID())
    }
}
