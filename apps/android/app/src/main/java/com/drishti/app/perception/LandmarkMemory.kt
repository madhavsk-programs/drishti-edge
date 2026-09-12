package com.drishti.app.perception

/**
 * Kotlin port of `app/perception/landmark_memory.py` (BUILD_PLAN.md task A5).
 *
 * Session-scoped memory of what the detector has seen, fed from
 * [DetectionSet.all] — the full native COCO view — so that "find the bottle"
 * is answered from the same detector pass the walk loop already ran, at no
 * extra inference (ARCHITECTURE.md §14.1 step 3).
 *
 * Bounded by construction: TTL, entry cap, confidence floor, sighting floor,
 * and `person` excluded by default. Lives in memory only and is cleared with
 * the session — nothing here touches disk (docs/SAFETY_RULES.md).
 *
 * Not thread-safe by design; [com.drishti.app.walk.LocalWalkPipeline] owns it
 * and is itself serialised by the walk controller.
 */
data class Landmark(
    val label: String,
    /** Absolute bearing when a device heading was available at sighting. */
    val worldBearingDeg: Double?,
    val lastCenterX: Double,
    val lastBoxH: Double,
    val lastBoxBottom: Double,
    val lastBox: NormalizedBox,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val sightings: Int,
)

data class NormalizedBox(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val centerX: Double get() = (x1 + x2) / 2.0
    val centerY: Double get() = (y1 + y2) / 2.0
    val height: Double get() = y2 - y1
}

class LandmarkMemory(
    ttlSeconds: Int = 45,
    private val maxEntries: Int = 40,
    private val cameraHfovDegrees: Double = 67.0,
    private val allowPerson: Boolean = false,
    private val bearingGateDegrees: Double = 25.0,
    private val minConfidence: Double = 0.45,
    private val minSightings: Int = 2,
) {
    private val ttlMs = ttlSeconds * 1000L
    private val entries = ArrayList<Landmark>()

    /** Forget everything. Called when a walk session ends. */
    fun clear() = entries.clear()

    /**
     * Fold one frame of detector candidates into memory. Only label,
     * confidence and the box are read, so this takes raw candidates rather
     * than risk-assessed results: the full COCO stream never passes through
     * tracking, spatial reasoning or scoring.
     */
    fun observe(nowMs: Long, headingDegrees: Double?, detections: List<DetectionCandidate>) {
        expire(nowMs)
        for (d in detections) {
            if (d.confidence < minConfidence) continue
            if (d.label.lowercase() == "person" && !allowPerson) continue
            remember(d.label, nowMs, headingDegrees, NormalizedBox(d.x1, d.y1, d.x2, d.y2), seedSightings = 1)
        }
        trim()
    }

    /**
     * The most recently seen landmark matching [targetName], or null. Only
     * entries seen at least [minSightings] times qualify, so a single-frame
     * false positive cannot become a destination.
     */
    fun resolve(targetName: String, nowMs: Long): Landmark? {
        expire(nowMs)
        val target = normalizeLabel(targetName)
        return entries
            .filter { it.sightings >= minSightings && labelsMatch(target, it.label) }
            .maxByOrNull { it.lastSeenMs }
    }

    fun count(nowMs: Long): Int {
        expire(nowMs)
        return entries.size
    }

    private fun remember(
        label: String,
        nowMs: Long,
        headingDegrees: Double?,
        box: NormalizedBox,
        seedSightings: Int,
    ): Landmark {
        val canonical = normalizeLabel(label)
        val centerX = box.centerX
        val worldBearing = headingDegrees?.let { wrap180(it + (centerX - 0.5) * cameraHfovDegrees) }

        val sameLabel = entries.withIndex().filter { normalizeLabel(it.value.label) == canonical }
        var candidate: IndexedValue<Landmark>? = null
        if (worldBearing != null) {
            val gated = sameLabel.filter { pair ->
                val wb = pair.value.worldBearingDeg
                wb != null && kotlin.math.abs(wrap180(worldBearing - wb)) <= bearingGateDegrees
            }
            candidate = if (gated.isNotEmpty()) {
                gated.minByOrNull { kotlin.math.abs(wrap180(worldBearing - (it.value.worldBearingDeg ?: 0.0))) }
            } else {
                // A session that started without a heading holds bearing-less
                // entries. Adopt the most recent one rather than duplicating
                // the object and resetting its sightings on the first frame
                // that carries a heading.
                sameLabel.filter { it.value.worldBearingDeg == null }.maxByOrNull { it.value.lastSeenMs }
            }
        } else if (sameLabel.isNotEmpty()) {
            candidate = sameLabel.maxByOrNull { it.value.lastSeenMs }
        }

        if (candidate == null) {
            val landmark = Landmark(
                label = canonical,
                worldBearingDeg = worldBearing,
                lastCenterX = centerX,
                lastBoxH = box.height,
                lastBoxBottom = box.y2,
                lastBox = box,
                firstSeenMs = nowMs,
                lastSeenMs = nowMs,
                sightings = seedSightings,
            )
            entries.add(landmark)
            return landmark
        }

        val previous = candidate.value
        val landmark = previous.copy(
            worldBearingDeg = worldBearing,
            lastCenterX = centerX,
            lastBoxH = box.height,
            lastBoxBottom = box.y2,
            lastBox = box,
            lastSeenMs = nowMs,
            sightings = maxOf(previous.sightings + 1, seedSightings),
        )
        entries[candidate.index] = landmark
        return landmark
    }

    private fun expire(nowMs: Long) {
        entries.removeAll { nowMs - it.lastSeenMs > ttlMs }
    }

    private fun trim() {
        if (entries.size > maxEntries) {
            entries.sortByDescending { it.lastSeenMs }
            entries.subList(maxEntries, entries.size).clear()
        }
    }
}

private val COLOUR_WORDS = setOf(
    "red", "orange", "yellow", "green", "blue", "purple", "pink", "brown",
    "black", "white", "grey", "gray", "silver", "gold",
)

private val LEADING_NOISE = setOf(
    "a", "an", "the", "my", "our", "your", "his", "her", "their", "some", "that", "this",
) + COLOUR_WORDS

/**
 * Reduce a spoken target or a detector label to a comparable noun phrase.
 *
 * Articles, possessives and colours are dropped from the front only while a
 * noun survives: "orange" is both a colour and a COCO class, and an empty
 * target matches nothing usefully.
 */
fun normalizeLabel(value: String): String {
    var tokens = value.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    while (tokens.size > 1 && tokens[0] in LEADING_NOISE) tokens = tokens.drop(1)
    return tokens.joinToString(" ")
}

private val SYNONYMS = mapOf(
    "sofa" to "couch",
    "fridge" to "refrigerator",
    "tv" to "television",
    "plant" to "potted plant",
    // Reach the risk set's aliased labels by the word a user actually says.
    "backpack" to "bag",
    "handbag" to "bag",
    "dining table" to "desk",
    "table" to "desk",
    // Common spoken forms of COCO classes the full set remembers.
    "phone" to "cell phone",
    "mobile" to "cell phone",
    "glass" to "wine glass",
    "mug" to "cup",
    "remote control" to "remote",
)

fun labelsMatch(target: String, observed: String): Boolean {
    var left = normalizeLabel(target)
    var right = normalizeLabel(observed)
    if (left == right) return true
    left = SYNONYMS[left] ?: left
    right = SYNONYMS[right] ?: right
    if (left == right) return true
    val l = left.split(" ").filter { it.isNotEmpty() }.toSet()
    val r = right.split(" ").filter { it.isNotEmpty() }.toSet()
    return l.isNotEmpty() && r.isNotEmpty() && (r.containsAll(l) || l.containsAll(r))
}

fun wrap180(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
