package morningpilates.infrastructure.persistence

import org.jooq.DSLContext

interface Database : AutoCloseable {
    val dsl: DSLContext

    override fun close()
}
