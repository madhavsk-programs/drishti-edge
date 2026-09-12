package com.drishti.app.spatial

import com.drishti.app.net.SurfaceKind

/**
 * Kotlin port of `app/spatial/surfaces.py` — ADE20K label → [SurfaceKind].
 *
 * The token sets are verbatim. A label that matches nothing stays UNKNOWN,
 * which the risk cascade treats as "do not claim this is walkable" rather than
 * as "clear" (docs/SAFETY_RULES.md).
 */
object Surfaces {

    val HAZARD_SURFACE_TOKENS: Set<String> =
        setOf("stairs", "stairway", "step", "escalator")

    val WALL_TOKENS: Set<String> = setOf("wall")

    private val WALKABLE = setOf(
        "floor", "flooring", "rug", "carpet", "path", "sidewalk", "pavement",
    )

    private val ROAD = setOf("road", "route")

    private val NON_WALKABLE = setOf(
        "wall", "building", "ceiling", "door", "screen door", "windowpane",
        "cabinet", "wardrobe", "column", "pillar", "table", "desk", "chair",
        "armchair", "sofa", "shelf", "bookcase", "railing", "bannister",
        "fence", "pole", "person", "rider", "car", "truck", "bus", "train",
        "motorcycle", "minibike", "bicycle", "van",
        // Level changes are non-walkable AND separately hazardous.
        "stairs", "stairway", "step", "escalator",
    )

    /**
     * ADE20K labels carry comma-separated synonyms ("floor, flooring"), so a
     * label is split before matching rather than compared whole.
     */
    fun labelTokens(label: String): Set<String> =
        label.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Precedence is NON_WALKABLE → WALKABLE → ROAD, matching the Python. A
     * label that is both (a "door" in a wall) resolves to the blocking answer.
     */
    fun kindFor(label: String): SurfaceKind {
        val tokens = labelTokens(label)
        return when {
            tokens.any { it in NON_WALKABLE } -> SurfaceKind.NON_WALKABLE
            tokens.any { it in WALKABLE } -> SurfaceKind.WALKABLE
            tokens.any { it in ROAD } -> SurfaceKind.ROAD
            else -> SurfaceKind.UNKNOWN
        }
    }

    /** Class ids whose label shares any token with [tokens]. */
    fun classIdsMatching(idToLabel: Map<Int, String>, tokens: Set<String>): Set<Int> =
        idToLabel.filterValues { labelTokens(it).any { token -> token in tokens } }.keys
}
