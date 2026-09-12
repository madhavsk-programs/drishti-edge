package com.drishti.app.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Chair shows suitcase?"
 *
 * Two live screenshots of the same office chairs: at walking distance YOLO11n
 * called them `chair` at 0.64 and 0.80; with the chair back filling the lens —
 * a large dark rounded rectangle — it called it `suitcase` at 0.37 and 0.41.
 * Both clear the 0.35 safety gate, so the app announced "a suitcase ahead" at
 * the exact moment the obstacle mattered most.
 *
 * The tracker made that worse rather than absorbing it. Association was gated
 * on the NAME, so the moment the name changed the whole history was discarded
 * and a fresh track began, with the wrong name and no way back. A name is a
 * guess about an object; the object is the thing that persists.
 */
class LabelVotingTest {

    private fun tracker(crossLabelIou: Double? = 0.60) = SessionTracker(
        iouThreshold = 0.20,
        centreDistanceThreshold = 0.12,
        maxAgeFrames = 3,
        crossLabelIouThreshold = crossLabelIou,
    )

    private fun box(label: String, confidence: Double, shift: Double = 0.0) =
        DetectionCandidate(label, confidence, 0.30 + shift, 0.30, 0.70 + shift, 0.95)

    @Test
    fun aChairRecognisedOnTheApproachIsStillAChairUpClose() {
        val tracker = tracker()
        tracker.update(listOf(box("chair", 0.80)), 1, 0L)
        tracker.update(listOf(box("chair", 0.64)), 2, 100L)
        val close = tracker.update(listOf(box("suitcase", 0.41)), 3, 200L)

        assertEquals(1, close.size)
        assertEquals("the track must survive the rename", 1, close[0].trackId)
        assertEquals("chair", close[0].detection.label)
    }

    @Test
    fun theNameCarriesTheBestEvidenceThereHasEverBeenForIt() {
        val tracker = tracker()
        tracker.update(listOf(box("chair", 0.80)), 1, 0L)
        val close = tracker.update(listOf(box("suitcase", 0.41)), 2, 100L)

        // The box confidence is this frame's; the NAME is worth 0.80, which is
        // what should decide whether it is worth saying out loud.
        assertEquals(0.41, close[0].detection.confidence, 1e-9)
        assertEquals(0.80, close[0].labelConfidence, 1e-9)
    }

    @Test
    fun aFirstSightingIsWorthExactlyItsOwnConfidence() {
        val first = tracker().update(listOf(box("suitcase", 0.41)), 1, 0L)
        assertEquals("suitcase", first[0].detection.label)
        assertEquals(0.41, first[0].labelConfidence, 1e-9)
    }

    @Test
    fun aPersistentlyDifferentNameEventuallyWins() {
        // Voting is not a veto. One confident early look should not outrank a
        // dozen consistent later ones, or a misread on the approach would be
        // permanent.
        val tracker = tracker()
        tracker.update(listOf(box("chair", 0.80)), 1, 0L)
        var latest = emptyList<TrackedDetection>()
        for (frame in 2..6) {
            latest = tracker.update(listOf(box("suitcase", 0.41)), frame, frame * 100L)
        }
        assertEquals("suitcase", latest[0].detection.label)
    }

    @Test
    fun aBoxThatIsMerelyNearbyDoesNotStealAnotherObjectsName() {
        val tracker = tracker()
        tracker.update(listOf(box("chair", 0.80)), 1, 0L)
        // Overlapping enough for the ordinary same-name gate (IoU 0.20), far
        // short of the cross-name one.
        val next = tracker.update(listOf(box("person", 0.80, shift = 0.25)), 2, 100L)
        assertEquals("person", next[0].detection.label)
        assertNotEquals(1, next[0].trackId)
    }

    @Test
    fun anObjectWithATrackOfItsOwnNameKeepsIt() {
        // A person and a chair in almost the same place: the same-name pass
        // runs first, so neither is absorbed into the other.
        val tracker = tracker()
        tracker.update(listOf(box("chair", 0.80), box("person", 0.80, shift = 0.02)), 1, 0L)
        val next = tracker.update(
            listOf(box("chair", 0.75), box("person", 0.75, shift = 0.02)), 2, 100L,
        )
        assertEquals(listOf("chair", "person"), next.map { it.detection.label })
        assertEquals(listOf(1, 2), next.map { it.trackId })
    }

    @Test
    fun parityModeKeepsThePythonBehaviour() {
        val tracker = tracker(crossLabelIou = null)
        tracker.update(listOf(box("chair", 0.80)), 1, 0L)
        val renamed = tracker.update(listOf(box("suitcase", 0.41)), 2, 100L)
        assertEquals("suitcase", renamed[0].detection.label)
        assertEquals(2, renamed[0].trackId)
    }

    @Test
    fun trackIdsStillSurviveAnOrdinarySameNameSequence() {
        val tracker = tracker()
        val ids = (1..4).map { frame ->
            tracker.update(
                listOf(box("chair", 0.80, shift = frame * 0.01)), frame, frame * 100L,
            )[0].trackId
        }
        assertTrue("expected one stable id, got $ids", ids.toSet().size == 1)
    }
}
