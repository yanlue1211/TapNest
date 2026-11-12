package com.deskpet.engine

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Optional, versioned addition to a legacy rig.json. Older pets stay readable unchanged. */
@Serializable
data class TapNestForm(
    val id: String, val name: String, val rig: TapNestRig,
    /** null inherits character-wide speech; an empty list deliberately stays quiet. */
    val tapDialogues: List<String>? = null
)

@Serializable
data class TapNestFormCollection(
    val version: Int = 1,
    val forms: List<TapNestForm>,
    val activeFormId: String = "default",
    /** Map of part category -> source form ID. Empty means the active form's parts. */
    val overrides: Map<String, String> = emptyMap()
)

/** Pure selection/composition logic. No image bytes or character data are mutated. */
/** One editable accessory group, derived from the pet's actual uploaded PNGs. */
data class TapNestWardrobePart(val id: String, val label: String)

/** Pure selection/composition logic. No image bytes or character data are mutated. */
object TapNestForms {
    // Kept for old code/tests that refer to the traditional default categories.
    // The actual wardrobe is NOT limited to these three.
    val categories = listOf("ear", "tail", "wing")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val categoryRegex = Regex("[A-Za-z][A-Za-z0-9_]{0,45}")

    /** Hidden, essential and expression-only layers aren't standalone wearables. */
    fun category(layer: TapNestLayer): String? {
        val root = layer.frameOwner ?: layer.id
        if (root == "body" || root == "pickup_pose" ||
            root == "head_blank" || root.matches(Regex("head_[0-9]+")) ||
            root.matches(Regex("face_[0-9]+"))) return null

        val configured = layer.wardrobeCategory.trim()
        if (configured.isNotEmpty() && categoryRegex.matches(configured)) return configured
        val id = root.lowercase()
        return when {
            id.startsWith("ear") || id.contains("_ear") -> "ear"
            id.startsWith("tail") || id.contains("_tail") -> "tail"
            id.startsWith("wing") || id.contains("_wing") -> "wing"
            id.startsWith("bow") || id.contains("_bow") -> "bow"
            id.startsWith("hat") || id.contains("_hat") -> "hat"
            id.startsWith("part_") -> "custom_$root"
            else -> null
        }
    }

    /** Never show empty defaults. Show only categories with an uploaded owner PNG. */
    fun availableParts(forms: TapNestFormCollection): List<TapNestWardrobePart> {
        val active = forms.forms.firstOrNull { it.id == forms.activeFormId }
        val preferred = listOfNotNull(active) + forms.forms.filter { it.id != active?.id }
        return preferred.flatMap { it.rig.layers }
            .asSequence().filter { it.frameOwner == null && it.image.isNotBlank() }
            .mapNotNull { layer ->
                val id = category(layer) ?: return@mapNotNull null
                val label = when (id) {
                    "ear" -> "耳朵"
                    "tail" -> "尾巴"
                    "wing" -> "翅膀"
                    "bow" -> "蝴蝶结"
                    "hat" -> "帽子"
                    else -> layer.displayName.trim()
                        .removePrefix("左").removePrefix("右")
                        .removeSuffix("（镜像）")
                        .removeSuffix(" · 默认帧")
                        .ifBlank { "自定义配件" }
                }
                TapNestWardrobePart(id, label)
            }.distinctBy { it.id }.toList()
    }

    fun dialogueLines(forms: TapNestFormCollection, common: List<String>): List<String> {
        val active = forms.forms.firstOrNull { it.id == forms.activeFormId } ?: forms.forms.firstOrNull()
        return com.deskpet.data.model.TapDialogue.sanitize(active?.tapDialogues ?: common)
    }

    fun starting(rig: TapNestRig): TapNestFormCollection =
        TapNestFormCollection(forms = listOf(TapNestForm("default", "默认形态", rig)))

    fun parse(file: File): TapNestFormCollection? = runCatching {
        if (!file.isFile || file.length() > 2_000_000L) return null
        val parsed = json.decodeFromString<TapNestFormCollection>(file.readText())
        if (parsed.forms.isEmpty() || parsed.forms.size > 24 ||
            parsed.forms.map { it.id }.distinct().size != parsed.forms.size ||
            parsed.forms.any { it.rig.layers.size > TapNestFrames.MAX_LAYERS ||
                !it.id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,35}")) }) return null
        parsed
    }.getOrNull()

    fun encode(forms: TapNestFormCollection): String = json.encodeToString(
        TapNestFormCollection.serializer(), forms)

    fun resolve(forms: TapNestFormCollection): TapNestRig {
        val active = forms.forms.firstOrNull { it.id == forms.activeFormId }
            ?: forms.forms.first()
        val selected = active.rig
        val known = forms.forms.flatMap { it.rig.layers }
            .mapNotNull(::category).toSet()
        val choices = forms.overrides.mapNotNull { (cat, sourceId) ->
            if (cat !in known) return@mapNotNull null
            val source = forms.forms.firstOrNull { it.id == sourceId }
            if (sourceId != "none" && source == null) return@mapNotNull null
            cat to (source?.rig?.layers?.filter { category(it) == cat }
                ?: emptyList<TapNestLayer>())
        }.toMap()
        if (choices.isEmpty()) return selected
        val replaced = selected.layers.filterNot { layer ->
            category(layer)?.let { it in choices } == true
        }
        // Source form supplies owner + all its action frames, anchors and bones.
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
        val known = forms.forms.flatMap { it.rig.layers }
            .mapNotNull(::category).toSet()
        if (category !in known) return forms
        if (sourceFormId != null && sourceFormId != "none" &&
            forms.forms.none { it.id == sourceFormId &&
                it.rig.layers.any { layer -> layer.image.isNotBlank() && category(layer) == category } }
        ) return forms
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
