package morningpilates.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import morningpilates.config.DatabaseProperties
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL

class PostgresDatabase private constructor(
    private val dataSource: HikariDataSource,
) : Database {
    override val dsl: DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)

    override fun close() {
        dataSource.close()
    }

    companion object {
        fun connect(
            properties: DatabaseProperties,
            migrationLocations: List<String> = listOf("classpath:db/migration"),
        ): PostgresDatabase {
            val config =
                HikariConfig().apply {
                    poolName = "morning-pilates"
                    dataSourceClassName = "org.postgresql.ds.PGSimpleDataSource"
                    username = properties.user
                    password = properties.password
                    maximumPoolSize = properties.maximumPoolSize
                    minimumIdle = 1
                    connectionTimeout = 10_000
                    initializationFailTimeout = 10_000
                    addDataSourceProperty("serverName", properties.host)
                    addDataSourceProperty("portNumber", properties.port)
                    addDataSourceProperty("databaseName", properties.database)
                    addDataSourceProperty("sslMode", properties.sslMode)
                    addDataSourceProperty("ApplicationName", "morning-pilates")
                    addDataSourceProperty("loginTimeout", 10)
                }
            val dataSource = HikariDataSource(config)
            return runCatching {
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations(*migrationLocations.toTypedArray())
                    .failOnMissingLocations(true)
                    .load()
                    .migrate()
                PostgresDatabase(dataSource)
            }.onFailure { failure ->
                runCatching { dataSource.close() }.onFailure { failure.addSuppressed(it) }
            }.getOrThrow()
        }
    }
}
