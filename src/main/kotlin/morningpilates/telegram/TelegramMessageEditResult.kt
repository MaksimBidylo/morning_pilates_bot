package morningpilates.telegram

import com.github.kotlintelegrambot.network.ResponseError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MESSAGE_NOT_MODIFIED = "Bad Request: message is not modified"
private const val MESSAGE_NOT_FOUND = "Bad Request: message to edit not found"
private const val TELEGRAM_BAD_REQUEST_CODE = 400

internal enum class TelegramMessageEditStatus { Updated, Missing, Failed }

internal fun ResponseError.messageEditStatus(httpStatus: Int?): TelegramMessageEditStatus {
    if (httpStatus != TELEGRAM_BAD_REQUEST_CODE) return TelegramMessageEditStatus.Failed
    return runCatching {
        val body = errorBody?.string() ?: return@runCatching TelegramMessageEditStatus.Failed
        val error = Json.parseToJsonElement(body).jsonObject
        if (error["ok"]?.jsonPrimitive?.booleanOrNull != false ||
            error["error_code"]?.jsonPrimitive?.intOrNull != TELEGRAM_BAD_REQUEST_CODE
        ) {
            return@runCatching TelegramMessageEditStatus.Failed
        }
        val description = error["description"]?.jsonPrimitive?.contentOrNull
        when {
            description == MESSAGE_NOT_MODIFIED || description?.startsWith("$MESSAGE_NOT_MODIFIED:") == true -> {
                TelegramMessageEditStatus.Updated
            }

            description == MESSAGE_NOT_FOUND -> {
                TelegramMessageEditStatus.Missing
            }

            else -> {
                TelegramMessageEditStatus.Failed
            }
        }
    }.getOrDefault(TelegramMessageEditStatus.Failed)
}
