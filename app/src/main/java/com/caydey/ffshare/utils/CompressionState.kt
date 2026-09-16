package com.caydey.ffshare.utils

import android.net.Uri
import androidx.annotation.StringRes

/**
 * Everything a compression run exposes to whoever is watching it.
 *
 * Deliberately free of views, activities and contexts: the run outlives any
 * particular activity, so its state has to be something a recreated activity can
 * simply read again.
 */
sealed interface CompressionState {

    object Idle : CompressionState

    data class Running(
        /** 1-based, for "x of y" */
        val position: Int,
        val total: Int,
        val outputName: String,
        val processedMillis: Int,
        val durationMillis: Int,
        /** ffmpeg encode-to-real-time ratio (2.0 = encoding 2 s of media per real second) */
        val speed: Double = 0.0
    ) : CompressionState {

        /**
         * Images have no timeline to report against, and neither do videos ffprobe
         * gives a zero duration for, so both hide the progress readout rather than
         * dividing by it.
         */
        val hasProgress: Boolean get() = durationMillis > 0

        val percent: Float
            get() = if (hasProgress) {
                (processedMillis.toFloat() / durationMillis * 100f).coerceIn(0f, 100f)
            } else {
                0f
            }

        /** Estimated remaining real-world milliseconds; -1 when not computable. */
        val remainingMillis: Int
            get() = if (hasProgress && speed > 0.0) {
                ((durationMillis - processedMillis) / speed).toInt().coerceAtLeast(0)
            } else {
                -1
            }
    }

    /**
     * Terminal state for a run that was not cancelled. [errorRes] is null when every
     * file succeeded; when it is set, [outputs] still holds whatever completed before
     * the failure so that work is not thrown away.
     */
    data class Finished(
        val outputs: List<Uri>,
        val totalInputSize: Long,
        val totalOutputSize: Long,
        @StringRes val errorRes: Int? = null
    ) : CompressionState {

        /**
         * A batch has three outcomes, not two: it can stop part way and still leave files
         * worth sharing. Derived here so every consumer classifies it the same way — the
         * screen and the notification previously each rebuilt the conjunction and
         * disagreed about the partial case.
         */
        val outcome: Outcome
            get() = when {
                errorRes == null -> Outcome.COMPLETE
                outputs.isNotEmpty() -> Outcome.PARTIAL
                else -> Outcome.FAILED
            }

        val reductionPercent: Double
            get() = if (totalInputSize > 0) {
                (1 - (totalOutputSize.toDouble() / totalInputSize)) * 100.0
            } else {
                0.0
            }
    }

    object Cancelled : CompressionState

    enum class Outcome {
        /** every file compressed */
        COMPLETE,

        /** stopped on a failure, but earlier files came through and can still be shared */
        PARTIAL,

        /** nothing came through */
        FAILED
    }
}
