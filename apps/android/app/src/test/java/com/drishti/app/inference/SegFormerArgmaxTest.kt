package com.drishti.app.inference

import kotlin.random.Random
import kotlin.system.measureNanoTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SegFormer decode is 150 classes over a 128x128 map — 2.4M comparisons per
 * frame, on the guidance path, and it used to run in pixel-major order, striding
 * a whole 64 KB plane between consecutive reads.
 */
class SegFormerArgmaxTest {

    private val width = 128
    private val height = 128
    private val classes = 150
    private val pixels = width * height

    private fun logits(seed: Int): FloatArray {
        val random = Random(seed)
        return FloatArray(pixels * classes) { random.nextFloat() * 20f - 10f }
    }

    /** The original loop, kept as the reference implementation. */
    private fun naive(logits: FloatArray, out: IntArray, classCount: Int) {
        for (p in out.indices) {
            var best = 0
            var bestScore = logits[p]
            for (c in 1 until classCount) {
                val score = logits[c * out.size + p]
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
            out[p] = best
        }
    }

    @Test
    fun classMajorArgmaxMatchesThePixelMajorOne() {
        for (seed in 1..3) {
            val data = logits(seed)
            val want = IntArray(pixels).also { naive(data, it, classes) }
            val got = IntArray(pixels)
            SegFormerSegmenter.argmaxChannelMajor(data, got, classes)
            assertArrayEquals("seed $seed", want, got)
        }
    }

    @Test
    fun tiesResolveToTheLowestClassIndexLikeTheOriginal() {
        // Every class identical: strictly-greater comparisons must keep class 0.
        val data = FloatArray(pixels * classes) { 1.0f }
        val got = IntArray(pixels)
        SegFormerSegmenter.argmaxChannelMajor(data, got, classes)
        assertTrue(got.all { it == 0 })
    }

    @Test
    fun handlesASingleClassAndAnEmptyMap() {
        val single = IntArray(pixels)
        SegFormerSegmenter.argmaxChannelMajor(FloatArray(pixels) { it.toFloat() }, single, 1)
        assertTrue(single.all { it == 0 })
        SegFormerSegmenter.argmaxChannelMajor(FloatArray(0), IntArray(0), 150)
    }

    /**
     * Not a hard performance gate — CI machines vary and a flaky timing test is
     * worse than none — but the ordering is the entire point of the change, so a
     * regression that made it SLOWER should be visible.
     */
    @Test
    fun classMajorOrderIsNotSlowerThanPixelMajor() {
        val data = logits(7)
        val a = IntArray(pixels)
        val b = IntArray(pixels)
        repeat(3) {
            naive(data, a, classes)
            SegFormerSegmenter.argmaxChannelMajor(data, b, classes)
        }
        var naiveNanos = Long.MAX_VALUE
        var fastNanos = Long.MAX_VALUE
        repeat(5) {
            naiveNanos = minOf(naiveNanos, measureNanoTime { naive(data, a, classes) })
            fastNanos = minOf(
                fastNanos,
                measureNanoTime { SegFormerSegmenter.argmaxChannelMajor(data, b, classes) },
            )
        }
        println(
            "argmax 128x128x150: pixel-major %.2f ms, class-major %.2f ms (%.1fx)".format(
                naiveNanos / 1e6, fastNanos / 1e6, naiveNanos.toDouble() / fastNanos,
            ),
        )
        assertTrue(
            "class-major must not be slower: $fastNanos ns vs $naiveNanos ns",
            fastNanos <= naiveNanos * 1.2,
        )
    }
}
