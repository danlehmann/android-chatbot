package net.daniellehmann.localchat.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(val role: String, val content: String)

/** One streamed increment. Fields may be empty; usage arrives once at the end if the server supports it. */
data class Delta(val content: String = "", val reasoning: String = "", val usage: Usage? = null)

data class Usage(val promptTokens: Int, val completionTokens: Int)

data class ModelInfo(val id: String, val contextLength: Int? = null)

class ApiException(message: String) : IOException(message)

/**
 * Minimal client for the OpenAI chat completions API as implemented by
 * llama.cpp server, vLLM, Ollama, LM Studio, DeepSeek and friends.
 */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Long generations: no read timeout on the stream, we rely on cancellation.
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    /** For metadata requests, which must not hang the UI if the server stalls. */
    private val quick = http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    private fun Request.Builder.auth() = apply {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
    }

    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/models")).auth().get().build()
        val models = quick.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}: ${body.take(300)}")
            val root = json.parseToJsonElement(body).jsonObject
            root["data"]?.jsonArray
                ?.mapNotNull { e ->
                    val o = e.jsonObject
                    val id = o["id"]?.asStringOrNull() ?: return@mapNotNull null
                    ModelInfo(id, contextLengthOf(o))
                }
                ?.sortedBy { it.id }
                ?: emptyList()
        }
        // llama.cpp server does not put n_ctx in /v1/models but exposes it on /props.
        if (models.any { it.contextLength == null }) {
            val ctx = llamaCppContext()
            if (ctx != null) return@withContext models.map { if (it.contextLength == null) it.copy(contextLength = ctx) else it }
        }
        models
    }

    /** vLLM: max_model_len; OpenRouter-style: context_length; llama.cpp: meta.n_ctx_train; others: context_window. */
    private fun contextLengthOf(o: JsonObject): Int? {
        val direct = listOf("max_model_len", "context_length", "context_window", "max_context_length")
            .firstNotNullOfOrNull { o[it]?.asIntOrNull() }
        if (direct != null) return direct
        return o["meta"]?.let { it as? JsonObject }?.get("n_ctx_train")?.asIntOrNull()
    }

    private fun llamaCppContext(): Int? = runCatching {
        val root = baseUrl.trimEnd('/').removeSuffix("/v1")
        val req = Request.Builder().url("$root/props").auth().get().build()
        quick.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            o["default_generation_settings"]?.let { it as? JsonObject }?.get("n_ctx")?.asIntOrNull()
        }
    }.getOrNull()

    private fun JsonElement.asIntOrNull(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()

    /**
     * Streams a chat completion. Emits deltas as they arrive; completes when the
     * server sends [DONE] or closes the stream. Cancelling the collector aborts the
     * HTTP call.
     */
    fun streamChat(
        model: String,
        messages: List<ChatMessage>,
        temperature: Float,
    ): Flow<Delta> = flow {
        val payload = buildJsonObject(model, messages, temperature)
        val req = Request.Builder()
            .url(url("/chat/completions"))
            .auth()
            .header("Accept", "text/event-stream")
            .post(json.encodeToString(payload).toRequestBody(jsonMedia))
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                throw ApiException("HTTP ${resp.code}: ${extractError(body)}")
            }
            val source = resp.body?.source() ?: throw ApiException("Empty response")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val delta = parseDelta(data) ?: continue
                if (delta.content.isNotEmpty() || delta.reasoning.isNotEmpty() || delta.usage != null) emit(delta)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildJsonObject(model: String, messages: List<ChatMessage>, temperature: Float): JsonObject =
        JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "stream" to JsonPrimitive(true),
                "temperature" to JsonPrimitive(temperature),
                // Ask for a final usage chunk (OpenAI, vLLM, llama.cpp honour this; others ignore it).
                "stream_options" to JsonObject(mapOf("include_usage" to JsonPrimitive(true))),
                "messages" to json.encodeToJsonElement(messages),
            ),
        )

    private fun Json.encodeToJsonElement(messages: List<ChatMessage>): JsonElement =
        encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), messages)

    private fun parseDelta(data: String): Delta? {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        root["error"]?.let { throw ApiException(extractError(it.toString())) }
        val usage = root["usage"]?.let { it as? JsonObject }?.let { u ->
            val p = u["prompt_tokens"]?.asIntOrNull()
            val c = u["completion_tokens"]?.asIntOrNull()
            if (p != null) Usage(p, c ?: 0) else null
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val delta = choice?.get("delta")?.let { it as? JsonObject }
        val content = delta?.get("content")?.asStringOrNull().orEmpty()
        // DeepSeek uses reasoning_content; some servers use reasoning.
        val reasoning = (delta?.get("reasoning_content") ?: delta?.get("reasoning"))?.asStringOrNull().orEmpty()
        return Delta(content = content, reasoning = reasoning, usage = usage)
    }

    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun extractError(body: String): String {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val err = parsed?.get("error")
        val msg = when (err) {
            is JsonObject -> err["message"]?.asStringOrNull()
            is JsonPrimitive -> err.content
            else -> null
        }
        return msg ?: body.take(300).ifBlank { "unknown error" }
    }
}
