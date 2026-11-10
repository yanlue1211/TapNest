package com.deskpet.engine

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Optional, versioned addition to a legacy rig.json. Older pets stay readable unchanged. */
@Serializable
data class TapNestForm(val id: String, val name: String, val rig: TapNestRig)

@Serializable
data class TapNestFormCollection(
    val version: Int = 1,
    val forms: List<TapNestForm>,
    val activeFormId: String = "default",
    /** Map of part category -> source form ID. Empty means the active form's parts. */
    val overrides: Map<String, String> = emptyMap()
)

/** Pure selection/composition logic. No image bytes or character data are mutated. */
object TapNestForms {
    val categories = listOf("ear", "tail", "wing")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun category(layer: TapNestLayer): String? {
        val id = layer.frameOwner ?: layer.id
        val lower = id.lowercase()
        return when {
            lower.startsWith("ear") || lower.contains("_ear") -> "ear"
            lower.startsWith("tail") || lower.contains("_tail") -> "tail"
            lower.startsWith("wing") || lower.contains("_wing") -> "wing"
            else -> null
        }
    }

    fun starting(rig: TapNestRig): TapNestFormCollection =
        TapNestFormCollection(forms = listOf(TapNestForm("default", "默认形态", rig)))

    fun parse(file: File): TapNestFormCollection? = runCatching {
        if (!file.isFile || file.length() > 2_000_000L) return null
        val parsed = json.decodeFromString<TapNestFormCollection>(file.readText())
        if (parsed.forms.isEmpty() || parsed.forms.size > 24 ||
            parsed.forms.map { it.id }.distinct().size != parsed.forms.size ||
            parsed.forms.any { it.rig.layers.size > 48 || it.id.length > 36 }) return null
        parsed
    }.getOrNull()

    fun encode(forms: TapNestFormCollection): String = json.encodeToString(
        TapNestFormCollection.serializer(), forms)

    fun resolve(forms: TapNestFormCollection): TapNestRig {
        val active = forms.forms.firstOrNull { it.id == forms.activeFormId }
            ?: forms.forms.first()
        val selected = active.rig
        val choices = categories.mapNotNull { cat ->
            val sourceId = forms.overrides[cat] ?: return@mapNotNull null
            val source = forms.forms.firstOrNull { it.id == sourceId } ?: return@mapNotNull null
            cat to source.rig.layers.filter { category(it) == cat }
        }.toMap()
        if (choices.isEmpty()) return selected
        val replaced = selected.layers.filterNot { layer ->
            category(layer)?.let { it in choices } == true
        }
        // Source form supplies the complete owner + frame group and its bone/motion settings.
        return selected.copy(layers = replaced + choices.values.flatten())
    }

    /** Only valid, complete forms can be used on the floating overlay. */
    fun eligible(forms: TapNestFormCollection, formId: String, assets: File): Boolean {
        val form = forms.forms.firstOrNull { it.id == formId } ?: return false
        val body = form.rig.layers.firstOrNull { it.id == "body" } ?: return false
        return body.image.isNotBlank() && TapNestFormAssets.safe(body.image) &&
            File(assets, body.image).isFile
    }

    fun choose(forms: TapNestFormCollection, formId: String): TapNestFormCollection =
        if (forms.forms.any { it.id == formId }) forms.copy(
            activeFormId = formId, overrides = emptyMap()) else forms

    fun choosePart(forms: TapNestFormCollection, category: String, sourceFormId: String?): TapNestFormCollection {
        if (category !in categories) return forms
        if (sourceFormId != null && forms.forms.none { it.id == sourceFormId }) return forms
        val without = forms.overrides - category
        return forms.copy(overrides = if (sourceFormId == null || sourceFormId == forms.activeFormId)
            without else without + (category to sourceFormId))
    }
}

/** Assets must be local, within the pet directory; shared references are never garbage-collected. */
object TapNestFormAssets {
    fun safe(fileName: String): Boolean =
        fileName.isNotBlank() && fileName.length <= 120 &&
        fileName.endsWith(".png", ignoreCase = true) &&
        !fileName.contains("..") && '/' !in fileName && '\\' !in fileName
}
