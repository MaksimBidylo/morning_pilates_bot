package morningpilates.telegram.logging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import morningpilates.diagnostics.RequestDiagnostics
import okhttp3.Interceptor
import okhttp3.Response

/** Observes synchronous SDK calls, including results ignored by business handlers. Never logs bodies or URLs. */
internal class TelegramApiLogging : Interceptor {
    // SDK converts transport and decoding exceptions into result values; record before that conversion.
    @Suppress("TooGenericExceptionCaught")
    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            val response = chain.proceed(chain.request())
            if (!response.isSuccessful) {
                RequestDiagnostics.apiFailure(response.code())
            } else if (chain
                    .request()
                    .url()
                    .pathSegments()
                    .last() != "getUpdates"
            ) {
                val ok =
                    runCatching {
                        Json
                            .parseToJsonElement(response.peekBody(MAX_INSPECTED_BYTES).string())
                            .jsonObject["ok"]
                            ?.jsonPrimitive
                            ?.booleanOrNull
                    }.getOrNull()
                if (ok != true) RequestDiagnostics.apiFailure(response.code())
            }
            return response
        } catch (failure: Exception) {
            RequestDiagnostics.failure(failure)
            throw failure
        }
    }

    private companion object {
        const val MAX_INSPECTED_BYTES = 1_048_576L
    }
}
