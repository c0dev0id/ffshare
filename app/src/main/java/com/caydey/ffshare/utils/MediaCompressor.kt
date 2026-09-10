package com.caydey.ffshare.utils

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.Statistics
import com.caydey.ffshare.R
import com.caydey.ffshare.utils.logs.Log
import com.caydey.ffshare.utils.logs.LogsDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Runs a batch of files through ffmpeg and reports what it is doing as a stream of
 * [CompressionState].
 *
 * Knows nothing about activities or views. Cancelling the coroutine that collects the
 * flow cancels the ffmpeg session that is running at the time, and nothing else.
 */
class MediaCompressor(private val context: Context) {
    private val utils: Utils by lazy { Utils(context) }
    private val settings: Settings by lazy { Settings(context) }
    private val logsDbHelper: LogsDbHelper by lazy { LogsDbHelper(context) }

    private val ffmpegParamMaker = FFmpegParamMaker(settings, utils)

    /** Outcome of a single file; exactly one of [output] and [errorRes] is set unless cancelled. */
    private class FileOutcome(
        val output: Uri? = null,
        val inputSize: Long = 0L,
        val outputSize: Long = 0L,
        @StringRes val errorRes: Int? = null,
        val cancelled: Boolean = false
    )

    /**
     * Compresses [inputs] one after another. A file that fails ends the batch, but the
     * files that already succeeded are still reported so the work is not wasted.
     */
    fun compress(inputs: List<Uri>): Flow<CompressionState> = channelFlow {
        val outputs = ArrayList<Uri>(inputs.size)
        var totalInputSize = 0L
        var totalOutputSize = 0L

        for ((index, input) in inputs.withIndex()) {
            Timber.d("Processing %d of %d files", index + 1, inputs.size)
            val outcome = compressOne(input, index + 1, inputs.size) { trySend(it) }

            if (outcome.cancelled) {
                send(CompressionState.Cancelled)
                return@channelFlow
            }
            if (outcome.errorRes != null) {
                send(CompressionState.Finished(outputs.toList(), totalInputSize, totalOutputSize, outcome.errorRes))
                return@channelFlow
            }

            totalInputSize += outcome.inputSize
            totalOutputSize += outcome.outputSize
            outputs.add(outcome.output!!)
        }

        send(CompressionState.Finished(outputs.toList(), totalInputSize, totalOutputSize))
    }

    private suspend fun compressOne(
        input: Uri,
        position: Int,
        total: Int,
        emit: (CompressionState) -> Unit
    ): FileOutcome = withContext(Dispatchers.IO) {
        val mediaType = utils.getMediaType(input)
        if (!utils.isSupportedMediaType(mediaType)) {
            Timber.d("Unsupported filetype, throwing error")
            return@withContext FileOutcome(errorRes = R.string.error_unknown_filetype)
        }

        val inputName = utils.getFilenameFromUri(input) ?: "unknown"

        // output file, (random uuid, custom name, original name)
        val (outputFile, outputMediaType) = utils.getCacheOutputFile(input, mediaType)
        // needs to go through FileProvider, not Uri.fromFile(...), to pass security checks
        val outputUri = FileProvider.getUriForFile(
            context, context.applicationContext.packageName + ".fileprovider", outputFile
        )

        // saf parameters are one-use, so a fresh one is taken for every command
        val mediaInformation = FFprobeKit
            .getMediaInformation(FFmpegKitConfig.getSafParameterForRead(context, input))
            .getMediaInformation()
        if (mediaInformation == null) {
            Timber.d("Unable to get media information, throwing error")
            return@withContext FileOutcome(errorRes = R.string.error_invalid_file)
        }

        val inputSize = mediaInformation.getSize()?.toLong() ?: 0L

        // images have no timeline to report progress against
        var durationMillis = 0
        if (!utils.isImage(mediaType)) {
            if (mediaInformation.getDuration() == null || mediaInformation.getSize() == null) {
                Timber.d("Unable to get size & duration for media, throwing error")
                return@withContext FileOutcome(errorRes = R.string.error_invalid_file)
            }
            durationMillis = ((mediaInformation.getDuration()?.toFloat() ?: 0f) * 1_000).toInt()
        }

        val params = ffmpegParamMaker.create(input, mediaInformation, mediaType, outputMediaType)
        val inputSaf = FFmpegKitConfig.getSafParameterForRead(context, input)
        val outputSaf = FFmpegKitConfig.getSafParameterForWrite(context, outputUri)
        val command = "-y -i $inputSaf $params $outputSaf"
        val prettyCommand = "ffmpeg -y -i $inputName $params ${outputFile.name}"

        fun running(processedMillis: Int, outputSize: Long) = CompressionState.Running(
            position = position,
            total = total,
            command = prettyCommand,
            inputName = inputName,
            inputSize = inputSize,
            outputName = outputFile.name,
            outputSize = outputSize,
            processedMillis = processedMillis,
            durationMillis = durationMillis
        )

        emit(running(0, 0L))

        Timber.d("Executing ffmpeg command: 'ffmpeg %s'", command)
        val session = executeFFmpeg(command) { statistics ->
            emit(running(statistics.getTime().toInt(), statistics.getSize()))
        }

        val returnCode = session.getReturnCode()
        if (returnCode == null || !returnCode.isValueSuccess()) {
            if (returnCode != null && returnCode.isValueCancel()) {
                Timber.d("ffmpeg command was cancelled")
                return@withContext FileOutcome(cancelled = true)
            }
            Timber.d("ffmpeg command failed")
            logsDbHelper.addLog(
                Log(prettyCommand, inputName, outputFile.name, false, session.getOutput(), inputSize, -1)
            )
            return@withContext FileOutcome(errorRes = R.string.ffmpeg_error)
        }

        Timber.d("ffmpeg command executed successfully")
        if (settings.copyExifTags && ExifTools.isValidType(mediaType)) {
            Timber.d("copying exif tags")
            context.contentResolver.openInputStream(input)?.use { ExifTools.copyExif(it, outputFile) }
        }

        val outputSize = outputFile.length()
        // settle the readout on its final values, 97.8% -> 100.0%
        emit(running(durationMillis, outputSize))

        logsDbHelper.addLog(
            Log(prettyCommand, inputName, outputFile.name, true, session.getOutput(), inputSize, outputSize)
        )
        FileOutcome(output = outputUri, inputSize = inputSize, outputSize = outputSize)
    }

    /**
     * Bridges ffmpeg-kit's callbacks into a suspending call. Cancelling the caller cancels
     * this session by id, rather than every session in the process as [FFmpegKit.cancel]
     * with no argument would.
     */
    private suspend fun executeFFmpeg(
        command: String,
        onStatistics: (Statistics) -> Unit
    ): FFmpegSession = suspendCancellableCoroutine { continuation ->
        val session = FFmpegKit.executeAsync(
            command,
            { completed -> if (continuation.isActive) continuation.resume(completed) },
            { /* logs are read back off the session once it completes */ },
            { statistics -> onStatistics(statistics) }
        )
        continuation.invokeOnCancellation { FFmpegKit.cancel(session.getSessionId()) }
    }
}
