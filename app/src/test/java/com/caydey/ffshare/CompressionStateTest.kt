package com.caydey.ffshare

import com.caydey.ffshare.utils.CompressionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readouts the compression screen and its notification derive from state. Both used to
 * be computed inline against values ffprobe is allowed to report as zero.
 */
class CompressionStateTest {

    private fun running(processedMillis: Int, durationMillis: Int, speed: Double = 0.0) = CompressionState.Running(
        position = 1,
        total = 1,
        outputName = "out.mp4",
        processedMillis = processedMillis,
        durationMillis = durationMillis,
        speed = speed
    )

    private fun finished(input: Long, output: Long) =
        CompressionState.Finished(emptyList(), input, output)

    @Test
    fun `percent is the share of the duration processed`() {
        assertEquals(50f, running(processedMillis = 5_000, durationMillis = 10_000).percent, 0.001f)
    }

    @Test
    fun `a zero duration reports no progress instead of dividing by it`() {
        // an image, or a video ffprobe gives no timeline for
        val state = running(processedMillis = 0, durationMillis = 0)
        assertFalse(state.hasProgress)
        assertEquals(0f, state.percent, 0.001f)
    }

    @Test
    fun `progress is only reported when there is a duration to measure against`() {
        assertTrue(running(processedMillis = 0, durationMillis = 1).hasProgress)
        assertFalse(running(processedMillis = 0, durationMillis = 0).hasProgress)
    }

    @Test
    fun `percent cannot overshoot when ffmpeg reports past the reported duration`() {
        // ffmpeg's reported time can run slightly beyond a rounded container duration
        assertEquals(100f, running(processedMillis = 12_000, durationMillis = 10_000).percent, 0.001f)
    }

    @Test
    fun `reduction is the proportion of the input size saved`() {
        assertEquals(75.0, finished(input = 4_000L, output = 1_000L).reductionPercent, 0.001)
    }

    @Test
    fun `growing past the input size reports a negative reduction`() {
        assertEquals(-50.0, finished(input = 1_000L, output = 1_500L).reductionPercent, 0.001)
    }

    @Test
    fun `a zero input size reports no reduction instead of dividing by it`() {
        assertEquals(0.0, finished(input = 0L, output = 0L).reductionPercent, 0.001)
    }

    @Test
    fun `remaining time divides leftover media by encode speed`() {
        // 30 s left, encoding at 2x → 15 real seconds
        assertEquals(15_000, running(processedMillis = 30_000, durationMillis = 60_000, speed = 2.0).remainingMillis)
    }

    @Test
    fun `remaining time is not available when speed is zero`() {
        assertEquals(-1, running(processedMillis = 30_000, durationMillis = 60_000, speed = 0.0).remainingMillis)
    }

    @Test
    fun `remaining time is not available when there is no duration`() {
        assertEquals(-1, running(processedMillis = 0, durationMillis = 0, speed = 2.0).remainingMillis)
    }

    @Test
    fun `remaining time is clamped to zero when ffmpeg overshoots the reported duration`() {
        assertEquals(0, running(processedMillis = 12_000, durationMillis = 10_000, speed = 2.0).remainingMillis)
    }

    @Test
    fun `a finished run without an error counts as succeeded`() {
        val ok = finished(input = 1_000L, output = 500L)
        assertTrue(ok.succeeded)
        assertNull(ok.errorRes)

        val failed = CompressionState.Finished(emptyList(), 0L, 0L, errorRes = R.string.ffmpeg_error)
        assertFalse(failed.succeeded)
    }
}
