package com.deskpet.gpt

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class GptCompanionTest {
    @Test fun largeModelCatalogDoesNotGetCutOffAndKeepsServerOrder() {
        val largeMetadata = "long metadata ".repeat(3000)
        val catalog = JSONObject().put("models", JSONArray()
            .put(JSONObject().put("slug", "gpt-6.1-sol")
                .put("metadata", JSONObject().put("notes", largeMetadata)
                    .put("nested", JSONArray().put(JSONObject().put("some", "other"))))
                .put("visibility", "list").put("display_name", "GPT-6.1 Sol"))
            .put(JSONObject().put("visibility", "hidden")
                .put("slug", "internal").put("display_name", "Not for display"))
            .put(JSONObject().put("visibility", "list")
                .put("slug", "gpt-6.1").put("display_name", "GPT-6.1")))
            .put("extra", "ignored")
        val body = catalog.toString()
        assertTrue("Ensure fixture exceeds original 16k limit", body.length > 16000)
        val models = GptModelCatalog.parse(StringReader(body))
        assertEquals(listOf("gpt-6.1-sol", "gpt-6.1"), models.map { it.slug })
        assertEquals(listOf("GPT-6.1 Sol", "GPT-6.1"), models.map { it.name })
    }

    @Test fun catalogWithMissingModelsFailsWithoutRevealingRawJson() {
        try {
            GptModelCatalog.parse(StringReader("""{"status":"ok"}"""))
            fail("must reject a missing catalog")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("models"))
        }
    }

    @Test fun chatAndWorkAreTwoStylesForOneGptIdentity() {
        val chat = GptStyle.instructions("Chat")
        val work = GptStyle.instructions("Work")
        assertTrue(chat.contains("小金毛"))
        assertTrue(work.contains("小金毛"))
        assertTrue(chat.contains("尾巴"))
        assertTrue(work.contains("戴帽子"))
        assertTrue(work.contains("休息时间"))
        assertFalse(chat == work)
    }

    @Test fun historyPersistsAndIsIsolatedByChatGptAccount() {
        val app = RuntimeEnvironment.getApplication() as Context
        val name = UUID.randomUUID().toString()
        val primary = GptHistory(app, name)
        val other = GptHistory(app, name + "-other")
        assertTrue(primary.load().isEmpty())
        primary.append("你在吗", "在呀")
        assertEquals(listOf("user", "assistant"), primary.load().map { it.role })
        assertEquals(listOf("你在吗", "在呀"), primary.load().map { it.text })
        assertTrue(other.load().isEmpty())
        primary.append("帽子戴上了吗", "戴上了")
        assertEquals(4, primary.load().size)
    }

    @Test fun oauthScopeDoesNotAllowPlanUsageOnIdentityAlone() {
        val sample = GptSession("oaiapp_example", "subject", "",
            "access", "refresh", "id", "openid profile email", Long.MAX_VALUE)
        assertFalse(sample.allowed)
        assertTrue(sample.copy(scopes = sample.scopes + " chatgpt.tokens.use.direct").allowed)
    }
}
