package com.hjtk.app.android.carrier

import com.hjtk.app.android.prefs.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Optional-key REST carriers. If the user never enters a key, status() says so
 * and the router simply does not offer them — nothing else in the app breaks.
 */
class DeepSeekApiCarrier(private val settings: Settings) : AiCarrier {

    override val id = "deepseek"
    override val label = "DeepSeek API"
    override val requiresKey = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun status(): CarrierStatus =
        if (settings.deepSeekKey.isNullOrBlank()) CarrierStatus(false, "API 키가 없습니다(설정에서 입력).")
        else CarrierStatus(true, "키가 저장되어 있습니다.")

    override fun stream(prompt: String): Flow<CarrierDelta> = flow {
        val key = settings.deepSeekKey?.takeIf { it.isNotBlank() }
            ?: run { emit(CarrierDelta("", true, "DeepSeek API 키가 없습니다.")); return@flow }
        val body = buildJsonObject {
            put("model", "deepseek-chat")
            put("stream", false)
            put("temperature", 0.3)
            put("messages", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject {
                    put("role", "system")
                    put("content", "You are a concise assistant running behind a deterministic Android agent. Answer in Korean unless asked otherwise.")
                },
                buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            )))
        }
        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    }.getOrNull()
                    emit(CarrierDelta("", true, "DeepSeek 오류 ${response.code}: ${message ?: "응답을 읽을 수 없습니다"}"))
                    return@use
                }
                val content = runCatching {
                    json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray?.first()?.jsonObject
                        ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                }.getOrNull()
                if (content.isNullOrBlank()) emit(CarrierDelta("", true, "DeepSeek 응답이 비어 있습니다."))
                else emit(CarrierDelta(content, true))
            }
        }.onFailure { emit(CarrierDelta("", true, "네트워크 오류: ${it.message?.take(120)}")) }
    }.flowOn(Dispatchers.IO)
}

class GeminiApiCarrier(private val settings: Settings) : AiCarrier {

    override val id = "gemini"
    override val label = "Gemini API"
    override val requiresKey = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val MODEL = "gemini-2.0-flash"
    }

    override suspend fun status(): CarrierStatus =
        if (settings.geminiKey.isNullOrBlank()) CarrierStatus(false, "API 키가 없습니다(설정에서 입력).")
        else CarrierStatus(true, "키가 저장되어 있습니다.")

    override fun stream(prompt: String): Flow<CarrierDelta> = flow {
        val key = settings.geminiKey?.takeIf { it.isNotBlank() }
            ?: run { emit(CarrierDelta("", true, "Gemini API 키가 없습니다.")); return@flow }
        val body = buildJsonObject {
            put("contents", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject {
                    put("role", "user")
                    put("parts", kotlinx.serialization.json.JsonArray(listOf(
                        buildJsonObject { put("text", prompt) }
                    )))
                }
            )))
            put("generationConfig", buildJsonObject { put("temperature", 0.3) })
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$key"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    }.getOrNull()
                    emit(CarrierDelta("", true, "Gemini 오류 ${response.code}: ${message ?: "응답을 읽을 수 없습니다"}"))
                    return@use
                }
                val content = runCatching {
                    json.parseToJsonElement(text).jsonObject["candidates"]?.jsonArray?.first()?.jsonObject
                        ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.first()?.jsonObject
                        ?.get("text")?.jsonPrimitive?.content
                }.getOrNull()
                if (content.isNullOrBlank()) emit(CarrierDelta("", true, "Gemini 응답이 비어 있습니다."))
                else emit(CarrierDelta(content, true))
            }
        }.onFailure { emit(CarrierDelta("", true, "네트워크 오류: ${it.message?.take(120)}")) }
    }.flowOn(Dispatchers.IO)
}
