package morningpilates.database

import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import org.jooq.DSLContext

interface Database : AutoCloseable {
    val dsl: DSLContext
    override fun close()
}

internal val DatabaseKey = AttributeKey<Database>("database")

val Application.database: Database
    get() = attributes[DatabaseKey]
