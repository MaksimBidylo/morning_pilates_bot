package morningpilates.infrastructure.persistence

import morningpilates.config.database.DatabaseProperties
import org.testcontainers.postgresql.PostgreSQLContainer

internal fun postgresTestContainer() =
    PostgreSQLContainer("postgres:18")
        .withDatabaseName("integration_test")
        .withUsername("integration_user")
        .withPassword("integration-password")

internal fun PostgreSQLContainer.databaseProperties() =
    DatabaseProperties(
        host = host,
        port = getMappedPort(5432),
        database = databaseName,
        user = username,
        password = password,
        sslMode = "disable",
    )

internal fun PostgreSQLContainer.resetPublicSchema() {
    createConnection("").use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}
