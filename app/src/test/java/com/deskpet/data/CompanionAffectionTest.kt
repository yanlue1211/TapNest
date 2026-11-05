package com.deskpet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class CompanionAffectionTest {
    private val day = 86_400_000L
    private val start = 1_000_000_000L

    @Test fun gentlerCompanionsLoseAffectionSlower() {
        val gentle = CompanionProfile(affection = 70, updatedAtMillis = start,
            temperament = CompanionTemperament.GENTLE)
        val normal = gentle.copy(temperament = CompanionTemperament.NORMAL)
        val grumpy = gentle.copy(temperament = CompanionTemperament.GRUMPY)
        assertEquals(66, CompanionAffection.at(gentle, start + day * 2))
        assertEquals(60, CompanionAffection.at(normal, start + day * 2))
        assertEquals(52, CompanionAffection.at(grumpy, start + day * 2))
    }

    @Test fun actionsRaiseAffectionWithoutExceedingMaximum() {
        val profile = CompanionProfile(affection = 98, updatedAtMillis = start)
        assertEquals(100, CompanionAffection.interact(profile, CompanionAction.FEED, start).affection)
        assertEquals(100, CompanionAffection.interact(profile, CompanionAction.PLAY, start).affection)
        assertEquals(100, CompanionAffection.interact(profile, CompanionAction.BATH, start).affection)
    }

    @Test fun oldDatesCannotDropAffectionBelowZero() {
        val p = CompanionProfile(affection = 20, updatedAtMillis = start,
            temperament = CompanionTemperament.GRUMPY)
        assertEquals(0, CompanionAffection.at(p, start + 365 * day))
    }

    @Test fun profileWithImagesAndRemindersRoundTripsLocally() {
        val p = CompanionProfile(
            items = listOf(CompanionItem(action = CompanionAction.PLAY,
                name = "小球", emoji = "⚽", icon = "icon_test.png", reply = "来玩{item}！")),
            reminders = listOf(CompanionReminder(message = "记得喝水", atMillis = start + day))
        )
        val restored = Json.decodeFromString<CompanionProfile>(Json.encodeToString(CompanionProfile.serializer(), p))
        assertEquals(p, restored)
        assertTrue(restored.reminders.none { it.delivered })
    }
}
