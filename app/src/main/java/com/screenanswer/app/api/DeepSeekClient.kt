package com.screenanswer.app.api

import com.screenanswer.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val statusCode: Int, override val message: String) : IOException(message)

/**
 * Minimal DeepSeek chat-completions client with SSE streaming.
 *
 * DeepSeek's public chat API does not accept images, so the screenshot is OCR'd locally and only
 * the recognized text is sent here.
 */
class DeepSeekClient(private val prefs: Prefs) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun streamAnswer(ocrText: String, english: Boolean = false): Flow<String> = flow {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            throw ApiException(401, "尚未配置 DeepSeek API Key")
        }

        val isReasoner = prefs.model == Prefs.MODEL_REASONER
        val payload = JSONObject().apply {
            put("model", prefs.model)
            put("stream", true)
            put("max_tokens", if (isReasoner) 8192 else 2048)
            if (!isReasoner) put("temperature", 0.3)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", Prompts.system(english)))
                    put(
                        JSONObject().put("role", "user")
                            .put("content", Prompts.userPrompt(ocrText, english))
                    )
                }
            )
        }

        val request = Request.Builder()
            .url(chatCompletionsUrl())
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = httpClient.newCall(request)
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw ApiException(response.code, friendlyError(response.code, body))
                }

                val source = response.body?.source()
                    ?: throw ApiException(-1, "DeepSeek 返回了空响应")
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val delta = extractDelta(data)
                    if (delta.isNotEmpty()) emit(delta)
                }
            }
        } catch (e: IOException) {
            // If the flow was cancelled, call.cancel() unblocks the socket with an IOException.
            // Re-throw a CancellationException instead so the UI does not show it as a failure.
            currentCoroutineContext().ensureActive()
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private fun chatCompletionsUrl(): String {
        var base = prefs.baseUrl.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://$base"
        }
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun extractDelta(data: String): String {
        return try {
            val choices = JSONObject(data).optJSONArray("choices") ?: return ""
            val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return ""
            if (delta.isNull("content")) "" else delta.optString("content", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun friendlyError(code: Int, body: String): String {
        val serverMessage = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
        return when {
            code == 400 -> "请求参数有误" + serverMessage.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
            code == 401 -> "API Key 无效，请检查后重试"
            code == 402 -> "DeepSeek 账户余额不足，请充值后重试"
            code == 403 -> "当前 API Key 没有访问权限"
            code == 404 -> "接口地址不存在，请检查「高级设置」中的 Base URL"
            code == 422 -> "请求参数有误，请稍后重试"
            code == 429 -> "请求过于频繁，请稍后再试"
            code in 500..599 -> "DeepSeek 服务暂时不可用（HTTP $code），请稍后再试"
            serverMessage.isNotBlank() -> serverMessage
            else -> "请求失败（HTTP $code）"
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
