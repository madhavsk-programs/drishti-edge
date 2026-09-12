package com.drishti.app.spatial

import com.drishti.app.net.SurfaceKind

/**
 * ADE20K label → [SurfaceKind], grown from `app/spatial/surfaces.py`.
 *
 * The Python token sets covered 36 of ADE20K's 150 classes and everything else
 * fell through to UNKNOWN. That is the correct DEFAULT — a label that matches
 * nothing stays UNKNOWN, and the risk cascade treats UNKNOWN as "do not claim
 * this is walkable" rather than as "clear" (docs/SAFETY_RULES.md) — but it is
 * the wrong answer for a class the model names confidently and that a walker
 * would collide with. The sets below therefore cover the full ADE20K
 * vocabulary; [SurfacesTest] pins that coverage so a regression is visible.
 */
object Surfaces {

    val HAZARD_SURFACE_TOKENS: Set<String> =
        setOf("stairs", "stairway", "step", "escalator")

    val WALL_TOKENS: Set<String> = setOf("wall")

    private val WALKABLE = setOf(
        "floor", "flooring", "rug", "carpet", "path", "sidewalk", "pavement",
        // Natural ground a pedestrian walks on. Without these the whole
        // outdoor half of ADE20K is UNKNOWN, which reads as "do not claim this
        // is walkable" and pins the system to PAUSE_UNCLEAR on any lawn or
        // unpaved path.
        "grass", "earth", "field", "land", "dirt track", "sand",
    )

    private val ROAD = setOf("road", "route")

    /**
     * Everything a walker collides with, trips over or falls into.
     *
     * ADE20K has 150 classes and this list started with 35 tokens, so 114 of
     * them resolved to UNKNOWN — `swivel chair`, `coffee table`, `counter`,
     * `bench`, `stool`, `refrigerator`, `plant`, `river` among them. UNKNOWN is
     * charged at `surfaceCostUnknownWeight` (0.10) against a
     * 0.40 block threshold, so a corridor filled wall-to-wall by an office
     * swivel chair cost 0.10 and the path read CLEAR.
     *
     * The test is: would a person walking into this be stopped, hurt or
     * dropped? Overhead classes (`ceiling`, `chandelier`, `lamp`, `light`,
     * `awning`, `canopy`, `sky`) are deliberately NOT here — they are above
     * head height and blocking on them would stop the user under every lit
     * corridor.
     */
    private val NON_WALKABLE = setOf(
        // Structure and vertical surfaces.
        "wall", "building", "ceiling", "door", "screen door", "windowpane",
        "column", "pillar", "railing", "bannister", "fence", "pole",
        "house", "skyscraper", "tower", "hovel", "tent", "bridge", "booth",
        "curtain", "blind", "mirror", "painting", "poster", "bulletin board",
        "signboard", "trade name", "screen", "stage", "grandstand",
        // Furniture and fittings.
        "cabinet", "wardrobe", "table", "desk", "coffee table", "pool table",
        "chair", "armchair", "swivel chair", "seat", "stool", "ottoman",
        "bench", "sofa", "shelf", "bookcase", "chest of drawers", "buffet",
        "counter", "countertop", "kitchen island", "bar", "case", "box",
        "basket", "barrel", "bed", "cradle", "fireplace", "radiator",
        // Appliances and machines.
        "refrigerator", "stove", "oven", "microwave", "dishwasher", "washer",
        "sink", "toilet", "bathtub", "shower", "computer", "monitor",
        "crt screen", "television receiver", "arcade machine", "conveyer belt",
        // Street furniture and living obstacles.
        "person", "rider", "animal", "plant", "tree", "palm", "flower",
        "sculpture", "fountain", "ashcan", "streetlight", "traffic light",
        "pier", "swimming pool", "rock",
        // Vehicles.
        "car", "truck", "bus", "train", "motorcycle", "minibike", "bicycle",
        "van", "boat", "ship", "airplane", "tank",
        // Water is not a surface to step onto.
        "water", "river", "sea", "lake", "waterfall",
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
