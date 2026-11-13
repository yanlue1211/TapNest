package com.deskpet.gpt

import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Only non-secret UI preferences live here. OAuth tokens remain in Android Keystore.
 * Chat and Work are two visual forms of the SAME GPT identity and history.
 */
internal class GptPetPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tapnest_gpt_pet_ui", Context.MODE_PRIVATE)

    private fun keyForSubject(subject: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(subject.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun selectedModel(subject: String): String =
        prefs.getString("model_" + keyForSubject(subject), null).orEmpty()

    fun saveModel(subject: String, slug: String) {
        if (slug.isNotBlank()) prefs.edit().putString("model_" + keyForSubject(subject), slug).apply()
    }

    /** Opt in only. No tap/feed/bath/play can trigger paid AI calls by default. */
    fun aiForPet(petId: String): Boolean =
        prefs.getBoolean("ai_interactions_" + petId, false)

    fun setAiForPet(petId: String, enabled: Boolean) {
        prefs.edit().putBoolean("ai_interactions_" + petId, enabled).apply()
    }
}

/** Avoid two overlapping model requests from Activity and overlay corrupting shared history. */
internal object GptRequestGate {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    fun tryAcquire(subject: String): Boolean = inFlight.add(subject)
    fun release(subject: String) { inFlight.remove(subject) }
}

internal object GptFriendlyErrors {
    fun text(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("subscription_sharing_usage_limit_exceeded", ignoreCase = true) ->
                "ChatGPT 共享额度到上限啦，等额度刷新后再聊～"
            raw.contains("401") || raw.contains("403") ->
                "登录授权可能已失效，请到点点栖的 GPT 聊天页面重新授权。"
            raw.contains("429") ->
                "请求有点频繁，过会再发这条消息就好。"
            else -> raw.take(180).ifBlank { "请求没有成功，稍后再试试。" }
        }
    }
}

/** Page longer model answers through the existing short non-touchable pet bubble. */
internal object GptBubbleSlices {
    fun split(text: String, maxCharacters: Int = 56, maxPages: Int = 8): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var index = 0
        val soft = setOf('。', '！', '？', '!', '?', '；', ';', '，', ',', '\n')
        while (index < clean.length && result.size < maxPages) {
            var end = minOf(index + maxCharacters, clean.length)
            if (end < clean.length) {
                val candidate = (end - 1 downTo index + maxCharacters / 3)
                    .firstOrNull { clean[it] in soft }
                if (candidate != null) end = candidate + 1
            }
            val part = clean.substring(index, end).trim()
            if (part.isNotBlank()) result.add(part)
            index = end
        }
        if (index < clean.length) {
            if (result.isEmpty()) return listOf("回复太长啦，请打开聊天记录查看完整内容。")
            result[result.lastIndex] = result.last().take((maxCharacters - 10).coerceAtLeast(12)) + "…更多见聊天记录"
        }
        return result
    }
}
