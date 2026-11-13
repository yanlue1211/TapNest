package com.deskpet.gpt

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GptPetOverlayTest {
    @Test fun noApiInteractionUntilExplicitlyEnabledAndPersistent() {
        val app = RuntimeEnvironment.getApplication() as Context
        val store = GptPetPreferences(app)
        val id = "test_pet_" + System.nanoTime()
        assertFalse(store.aiForPet(id))
        store.setAiForPet(id, true)
        assertTrue(GptPetPreferences(app).aiForPet(id))
        store.setAiForPet(id, false)
        assertFalse(GptPetPreferences(app).aiForPet(id))
    }

    @Test fun modelChoiceIsPerAccountAndSharedAcrossSurfaces() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = GptPetPreferences(context)
        val first = "user_" + System.nanoTime()
        val second = first + "_second"
        assertTrue(prefs.selectedModel(first).isBlank())
        prefs.saveModel(first, "gpt-6.1-sol")
        assertEquals("gpt-6.1-sol", GptPetPreferences(context).selectedModel(first))
        assertTrue(prefs.selectedModel(second).isBlank())
    }

    @Test fun requestGateRejectsDuplicateAndAllowsNextTurn() {
        val account = "test-account-" + System.nanoTime()
        assertTrue(GptRequestGate.tryAcquire(account))
        assertFalse(GptRequestGate.tryAcquire(account))
        GptRequestGate.release(account)
        assertTrue(GptRequestGate.tryAcquire(account))
        GptRequestGate.release(account)
    }

    @Test fun bubbleSegmentsAreSmallAndDoNotForceNewApiCalls() {
        val message = "哈哈！".repeat(55) + "最后一句。"
        val parts = GptBubbleSlices.split(message)
        assertTrue(parts.isNotEmpty())
        assertTrue(parts.size <= 8)
        assertTrue(parts.all { it.length <= 56 })
        assertEquals("哈哈！", GptBubbleSlices.split("哈哈！").single())
        assertTrue(GptBubbleSlices.split(" ").isEmpty())
    }

    @Test fun quotaErrorIsFriendlyAndNeverTriggersPaidFallback() {
        val msg = GptFriendlyErrors.text(IllegalStateException(
            "GPT 响应未完成：subscription_sharing_usage_limit_exceeded"))
        assertTrue(msg.contains("额度"))
        assertFalse(msg.contains("subscription_sharing"))
    }
}
