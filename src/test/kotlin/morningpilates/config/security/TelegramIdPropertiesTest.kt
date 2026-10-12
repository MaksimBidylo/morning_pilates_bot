package morningpilates.config.security

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.config.property
import io.ktor.server.testing.testApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import morningpilates.infrastructure.security.TelegramIdProtector
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramIdPropertiesTest {
    companion object {
        private const val CONFIG_PATH = "security.telegramId"
        private const val TELEGRAM_ID = 7_000_000_123L
    }

    private val encryptionKey = ByteArray(32) { it.toByte() }
    private val lookupKey = ByteArray(32) { (it + 32).toByte() }
    private val encoder = Base64.getEncoder()
    private val validValues =
        mapOf(
            "encryptionKey" to encoder.encodeToString(encryptionKey),
            "lookupKey" to encoder.encodeToString(lookupKey),
        )

    @Test
    fun `decodes both Base64 keys from security telegramId section`() {
        val keys = loadProperties().decodeKeys()

        assertContentEquals(encryptionKey, keys.encryptionKey)
        assertContentEquals(lookupKey, keys.lookupKey)
    }

    @ParameterizedTest
    @ValueSource(strings = ["encryptionKey", "lookupKey"])
    fun `rejects invalid Base64 without revealing secrets`(parameterName: String) {
        val values = validValues + (parameterName to "test-secret:not-base64!")

        val failure = assertInvalidKeys(values)

        assertTrue(failure.message.orEmpty().contains(parameterName.removeSuffix("Key"), ignoreCase = true))
        assertTrue(failure.message.orEmpty().contains("Base64", ignoreCase = true))
    }

    @ParameterizedTest
    @CsvSource("encryptionKey, 31", "encryptionKey, 33", "lookupKey, 31", "lookupKey, 33")
    fun `rejects decoded keys with invalid length without revealing secrets`(
        parameterName: String,
        keySize: Int,
    ) {
        val wrongKey = ByteArray(keySize) { (it + 64).toByte() }
        val values = validValues + (parameterName to encoder.encodeToString(wrongKey))

        val failure = assertInvalidKeys(values, listOf(encryptionKey, lookupKey, wrongKey))

        assertTrue(failure.message.orEmpty().contains(parameterName.removeSuffix("Key"), ignoreCase = true))
        assertTrue("32" in failure.message.orEmpty())
    }

    @Test
    fun `rejects equal decoded keys without revealing secrets`() {
        val values = validValues + ("lookupKey" to validValues.getValue("encryptionKey"))

        val failure = assertInvalidKeys(values)

        assertTrue(failure.message.orEmpty().contains("different", ignoreCase = true))
    }

    @ParameterizedTest
    @ValueSource(strings = ["encryptionKey", "lookupKey"])
    @OptIn(ExperimentalSerializationApi::class)
    fun `rejects missing required parameter without revealing the other key`(parameterName: String) {
        val values = validValues - parameterName

        val failure =
            assertFailsWith<MissingFieldException> {
                loadProperties(values)
            }

        assertTrue(parameterName in failure.missingFields)
        assertNoSecrets(failure.stackTraceToString(), values)
    }

    @Test
    fun `properties and decoded keys do not reveal secrets in toString`() {
        val properties = loadProperties()
        val keys = properties.decodeKeys()

        assertNoSecrets(properties.toString())
        assertNoSecrets(keys.toString())
    }

    @Test
    fun `decoded configuration creates a working TelegramIdProtector`() {
        val keys = loadProperties().decodeKeys()
        val protector = TelegramIdProtector(encryptionKey = keys.encryptionKey, lookupKey = keys.lookupKey)
        val referenceProtector = TelegramIdProtector(encryptionKey = encryptionKey, lookupKey = lookupKey)

        val encrypted = protector.encrypt(TELEGRAM_ID)

        assertEquals(TELEGRAM_ID, protector.decrypt(encrypted))
        assertContentEquals(referenceProtector.lookup(TELEGRAM_ID), protector.lookup(TELEGRAM_ID))
    }

    private fun loadProperties(values: Map<String, String> = validValues): TelegramIdProperties {
        lateinit var properties: TelegramIdProperties
        testApplication {
            environment {
                config =
                    HoconApplicationConfig(
                        ConfigFactory.parseMap(mapOf("security" to mapOf("telegramId" to values))),
                    )
            }
            application {
                properties = property<TelegramIdProperties>(CONFIG_PATH)
            }
            startApplication()
        }
        return properties
    }

    private fun assertInvalidKeys(
        values: Map<String, String>,
        decodedKeys: List<ByteArray> = listOf(encryptionKey, lookupKey),
    ): IllegalArgumentException {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                loadProperties(values).decodeKeys()
            }
        assertNoSecrets(failure.stackTraceToString(), values, decodedKeys)
        return failure
    }

    private fun assertNoSecrets(
        text: String,
        values: Map<String, String> = validValues,
        decodedKeys: List<ByteArray> = listOf(encryptionKey, lookupKey),
    ) {
        for (secret in validValues.values + values.values) {
            assertFalse(secret in text)
        }
        for (key in decodedKeys) {
            assertFalse(key.contentToString() in text)
            val hex = HexFormat.of().formatHex(key)
            assertFalse(hex in text)
            assertFalse(hex.uppercase() in text)
        }
    }
}
