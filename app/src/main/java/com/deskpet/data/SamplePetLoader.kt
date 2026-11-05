package com.deskpet.data

import android.content.Context
import kotlinx.coroutines.flow.first

/** TapNest includes no bundled character artwork. */
object SamplePetLoader {
    suspend fun loadSamplePets(context: Context, petRepository: PetRepository) {
        if (!petRepository.samplesLoaded().first()) petRepository.setSamplesLoaded()
    }
}
