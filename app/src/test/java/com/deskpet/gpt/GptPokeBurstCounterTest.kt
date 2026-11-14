package com.deskpet.gpt

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GptPokeBurstCounterTest {
    @Test fun stopForThreeSecondsProducesOneBurstInsteadOfMidwayRequests() {
        val c = GptPokeBurstCounter()
        assertEquals(1, c.tap(1000))
        assertNull(c.finishIfQuiet(3900, 1))
        assertEquals(2, c.tap(3900))
        assertNull(c.finishIfQuiet(6800, 1))
        assertEquals(2, c.finishIfQuiet(6900, 1))
        assertNull(c.finishIfQuiet(10000, 1))
    }

    @Test fun thresholdIsInclusiveAndDefaultsToOne() {
        val c = GptPokeBurstCounter()
        c.tap(2000)
        assertNull(c.finishIfQuiet(5000, 3))
        assertEquals(1, c.tap(7000))
        c.tap(7200)
        c.tap(7300)
        assertEquals(3, c.finishIfQuiet(10300, 3))
        assertEquals(1, c.tap(12000))
        assertEquals(1, c.finishIfQuiet(15000, 1))
    }

    @Test fun aGapOfExactlyThreeSecondsStartsAnotherBurst() {
        val c = GptPokeBurstCounter()
        assertEquals(1, c.tap(1000))
        assertEquals(1, c.tap(4000))
        assertEquals(1, c.finishIfQuiet(7000, 1))
    }

    @Test fun resetNeverResendsOldSegments() {
        val c = GptPokeBurstCounter()
        c.tap(100)
        c.tap(150)
        c.reset()
        assertNull(c.finishIfQuiet(10000, 1))
        assertEquals(1, c.tap(11000))
    }

    @Test fun pokeMinimumIsSavedOnlyForThisGptPet() {
        val app = RuntimeEnvironment.getApplication() as Context
        val prefs = GptPetPreferences(app)
        val pet = "gpt-edition-" + System.nanoTime()
        assertEquals(1, prefs.pokeMinimum(pet))
        prefs.setPokeMinimum(pet, 3)
        assertEquals(3, GptPetPreferences(app).pokeMinimum(pet))
        assertEquals(1, prefs.pokeMinimum(pet + "different"))
        prefs.setPokeMinimum(pet, 100)
        assertEquals(50, prefs.pokeMinimum(pet))
    }
}
