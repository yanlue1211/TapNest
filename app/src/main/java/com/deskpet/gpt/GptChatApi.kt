package com.deskpet.gpt

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class GptModel(val slug: String, val name: String)
internal data class GptMessage(val role: String, val text: String)

internal class GptHistory(private val context: Context, subject: String) {
    // Chat and Work share history; different ChatGPT accounts cannot see each other's local chats.
    private val hash = MessageDigest.getInstance("SHA-256").digest(subject.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)
    private val file = AtomicFile(File(context.filesDir, "tapnest_gpt_chat_" + hash + ".json"))
    @Synchronized fun load(): List<GptMessage> {
        if (!file.baseFile.exists()) return emptyList()
        val array = JSONArray(String(file.openRead().use { it.readBytes() }, Charsets.UTF_8))
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val role = item.optString("role")
            val content = item.optString("text")
            if (role !in listOf("user", "assistant") || content.isBlank()) null else GptMessage(role, content)
        }
    }
    @Synchronized fun append(user: String, assistant: String) {
        val rows = (load() + GptMessage("user", user) + GptMessage("assistant", assistant)).takeLast(160)
        val content = JSONArray().apply {
            rows.forEach { put(JSONObject().put("role", it.role).put("text", it.text)) }
        }.toString().toByteArray(Charsets.UTF_8)
        check(content.size < 1_000_000) { "本地聊天记录过大，请先备份" }
        val out = file.startWrite()
        try {
            out.write(content)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}

/** Form only changes the response style; it never selects another model or chat history. */
internal object GptStyle {
    fun current(context: Context, petId: String?): String {
        if (petId.isNullOrBlank() || !petId.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) return "Chat"
        val file = File(File(File(context.filesDir, "pets"), petId), "forms.json")
        val forms = runCatching { com.deskpet.engine.TapNestForms.parse(file) }.getOrNull()
        val name = forms?.forms?.firstOrNull { it.id == forms.activeFormId }?.name ?: ""
        return if (name.contains("work", true) || name.contains("工作")) "Work" else "Chat"
    }
    fun instructions(style: String): String {
        val common = "你是 GPT，正在以用户自行设计的桌面小金毛形象，在点点栖里聊天。保持自然中文口语，" +
            "不做作、不浮夸、不替用户断言情绪。允许幽默和认真，不是固定卖萌角色。你只需要聊天，" +
            "不执行编程任务或任何外部工具。用户当前的形态变化只是你换装，不代表另一个人。" +
            "不要声称能自动读取 ChatGPT 里的历史或记忆。"
        return common + if (style == "Work")
            "当前是戴帽子的 Work 形态。措辞更沉稳、条理清楚，偶尔冷幽默。现在是休息时间，不要以工作报告口吻讲话。"
        else
            "当前是带狗耳朵和尾巴的 Chat 形态。轻松、灵动、自然，可以活泼吐槽，别每句话都汪汪叫。"
    }
}

/** A ChatGPT plan model catalog includes huge nested metadata, not just display names.
 * Stream the complete JSON response and skip metadata we do not use. Never truncate success JSON.
 */
internal object GptModelCatalog {
    fun parse(reader: Reader): List<GptModel> = JsonReader(reader).use { json ->
        val result = mutableListOf<GptModel>()
        json.beginObject()
        var found = false
        while (json.hasNext()) {
            if (json.nextName() != "models") {
                json.skipValue()
                continue
            }
            found = true
            json.beginArray()
            while (json.hasNext()) {
                if (json.peek() != JsonToken.BEGIN_OBJECT) {
                    json.skipValue()
                    continue
                }
                var slug = ""
                var name = ""
                var visibility = ""
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "slug" -> slug = json.stringOrSkip()
                        "display_name" -> name = json.stringOrSkip()
                        "visibility" -> visibility = json.stringOrSkip()
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                if (visibility == "list" && slug.isNotBlank()) {
                    result.add(GptModel(slug, name.ifBlank { slug }))
                }
            }
            json.endArray()
        }
        json.endObject()
        check(found) { "模型响应缺少 models 字段" }
        result.distinctBy { it.slug }
    }

    private fun JsonReader.stringOrSkip(): String {
        if (peek() == JsonToken.STRING) return nextString()
        skipValue()
        return ""
    }
}

internal class GptChatApi(private val oauth: GptOAuth) {
    fun models(): List<GptModel> {
        val c = URL("https://api.openai.com/v1/models").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.requestMethod = "GET"
        c.instanceFollowRedirects = false
        c.setRequestProperty("Authorization", "Bearer " + oauth.accessToken())
        c.setRequestProperty("Accept", "application/json")
        try {
            val status = c.responseCode
            if (status !in 200..299) {
                // Never display raw model metadata or the body of a failed request.
                val errorCode = runCatching {
                    val detail = c.errorStream?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText().take(4000) }.orEmpty()
                    JSONObject(detail).optJSONObject("error")?.optString("code").orEmpty()
                }.getOrDefault("")
                throw IllegalStateException("模型列表请求失败 HTTP " + status +
                    if (errorCode.isNotBlank()) " · " + errorCode.take(80) else "")
            }
            return try {
                c.inputStream.bufferedReader(Charsets.UTF_8).use { GptModelCatalog.parse(it) }
            } catch (e: Exception) {
                throw IllegalStateException("模型列表读取失败（数据不完整或格式变化），请刷新重试", e)
            }
        } finally { c.disconnect() }
    }

    /** Stream is successful only after response.completed; partial output isn't saved as history. */
    fun reply(model: String, instruction: String, history: List<GptMessage>, user: String,
              onDelta: (String) -> Unit): String {
        val input = JSONArray()
        val prior = history.takeLast(24)
        for (item in prior) input.put(JSONObject().put("role", item.role).put("content", item.text))
        input.put(JSONObject().put("role", "user").put("content", user))
        val payload = JSONObject()
            .put("model", model).put("instructions", instruction)
            .put("input", input).put("store", false).put("stream", true)
        val c = URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 120000
        c.requestMethod = "POST"
        c.doOutput = true
        c.instanceFollowRedirects = false
        c.setRequestProperty("Authorization", "Bearer " + oauth.accessToken())
        c.setRequestProperty("Accept", "text/event-stream")
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val raw = c.errorStream?.bufferedReader()?.use { it.readText().take(6000) }.orEmpty()
                val detail = runCatching {
                    val error = JSONObject(raw)
                    error.optJSONObject("error")?.optString("code").orEmpty()
                        .ifBlank { error.optString("detail").take(200) }
                }.getOrDefault("")
                throw IllegalStateException("GPT 请求失败 HTTP " + code +
                    (if (detail.isBlank()) "" else " · " + detail))
            }
            val buffer = StringBuilder()
            var completed = false
            val lines = mutableListOf<String>()
            fun consumeEvent() {
                if (lines.isEmpty()) return
                val data = lines.joinToString("\n")
                lines.clear()
                if (data == "[DONE]") return
                val event = runCatching { JSONObject(data) }.getOrNull() ?: return
                when (event.optString("type")) {
                    "response.output_text.delta" -> {
                        val chunk = event.optString("delta")
                        buffer.append(chunk)
                        onDelta(buffer.toString())
                    }
                    "response.failed", "response.incomplete", "error" -> {
                        val error = event.optJSONObject("response")?.optJSONObject("error")
                            ?: event.optJSONObject("error")
                        val reason = error?.optString("code").orEmpty().ifBlank { event.optString("type") }
                        throw IllegalStateException("GPT 响应未完成：" + reason)
                    }
                    "response.completed" -> completed = true
                }
            }
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) consumeEvent()
                    else if (line.startsWith("data:")) lines.add(line.removePrefix("data:").trimStart())
                }
                consumeEvent()
            }
            check(completed && buffer.isNotBlank()) { "回复未完整结束，请重试（已保留原聊天）" }
            return buffer.toString()
        } finally { c.disconnect() }
    }
}
