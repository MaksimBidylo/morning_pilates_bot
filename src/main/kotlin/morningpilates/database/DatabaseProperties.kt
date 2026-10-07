package morningpilates.database

import kotlinx.serialization.Serializable

@Serializable
data class DatabaseProperties(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String,
    val sslMode: String = "prefer",
    val maximumPoolSize: Int = 5,
) {
    init {
        require(host.isNotBlank()) { "Database host must not be blank" }
        require(port in 1..65535) { "Database port must be between 1 and 65535" }
        require(database.isNotBlank()) { "Database name must not be blank" }
        require(user.isNotBlank()) { "Database user must not be blank" }
        require(maximumPoolSize > 0) { "Database pool size must be positive" }
    }

    override fun toString(): String =
        "DatabaseProperties(host=$host, port=$port, database=$database, user=$user, " +
            "password=***, sslMode=$sslMode, maximumPoolSize=$maximumPoolSize)"
}
