package com.deskpet.gpt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class GptCompanionTest {
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
        val app = ApplicationProvider.getApplicationContext<Context>()
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
