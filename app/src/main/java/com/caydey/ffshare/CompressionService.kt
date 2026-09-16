package com.caydey.ffshare

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.caydey.ffshare.extensions.parcelableArrayList
import com.caydey.ffshare.utils.CompressionState
import com.caydey.ffshare.utils.MediaCompressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Owns a compression run so that it outlives the activity that started it.
 *
 * State is exposed as a process-wide [StateFlow] rather than through binding: only one
 * run happens at a time, and an activity that is recreated only ever needs to read the
 * latest value again.
 */
class CompressionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val compressor: MediaCompressor by lazy { MediaCompressor(applicationContext) }
    private val notifications: CompressionNotifications by lazy { CompressionNotifications(this) }

    private var job: Job? = null

    /** set before cancelling so the teardown can tell a cancel from a natural end */
    @Volatile
    private var cancelled = false

    private var lastNotifiedPercent = -1
    private var lastNotifiedAt = 0L

    /** Outputs of the last finished run, kept until Done or until a new run replaces them. */
    private var finishedOutputs: List<Uri> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBatch(intent)
            ACTION_CANCEL -> cancelBatch()
            ACTION_DONE -> finishDone()
            else -> if (job?.isActive != true && _state.value !is CompressionState.Finished) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startBatch(intent: Intent) {
        // has to happen promptly, the service was started with startForegroundService
        val notification = notifications.progress(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                CompressionNotifications.ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(CompressionNotifications.ID_PROGRESS, notification)
        }

        if (job?.isActive == true) {
            Timber.d("Compression already running, ignoring start request")
            return
        }

        // a new run replaces whatever the last finished one was still holding
        releaseFinishedRun()

        val inputs = intent.parcelableArrayList<Uri>(EXTRA_INPUTS)
        if (inputs.isNullOrEmpty()) {
            Timber.d("No files in start request")
            finishUp(CompressionState.Idle)
            return
        }

        cancelled = false
        lastNotifiedPercent = -1
        lastNotifiedAt = 0L
        job = scope.launch {
            try {
                compressor.compress(inputs).collect { state ->
                    _state.value = state
                    if (state is CompressionState.Running) notifyProgress(state)
                }
            } finally {
                if (cancelled) _state.value = CompressionState.Cancelled
                finishUp(_state.value)
            }
        }
    }

    /**
     * ffmpeg reports statistics several times a second. Rebuilding and posting the
     * notification for every one of them is thousands of binder calls on the main thread
     * for a long video, and Android silently drops updates past roughly ten a second, so
     * the progress bar stutters while the work is still being done.
     */
    private fun notifyProgress(state: CompressionState.Running) {
        val percent = state.percent.toInt()
        val now = SystemClock.elapsedRealtime()
        val stale = now - lastNotifiedAt >= NOTIFICATION_MIN_INTERVAL_MS
        if (percent == lastNotifiedPercent && !stale) return

        lastNotifiedPercent = percent
        lastNotifiedAt = now
        notifications.updateProgress(state)
    }

    private fun cancelBatch() {
        Timber.d("Cancelling compression")
        cancelled = true
        val running = job
        if (running == null || !running.isActive) {
            stopSelf()
            return
        }
        running.cancel()
    }

    /** Called by the UI when the user taps Done: cleans up outputs and stops the service. */
    private fun finishDone() {
        releaseFinishedRun()
        _state.value = CompressionState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Runs for every outcome. For Finished, posts the result notification and keeps the
     * service alive so the UI can re-attach and the outputs stay available until Done.
     */
    private fun finishUp(outcome: CompressionState) {
        scheduleCacheCleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (outcome is CompressionState.Finished) {
            finishedOutputs = outcome.outputs
            notifications.postResult(outcome)
            // service stays alive; the UI or ACTION_DONE will stop it
            return
        }

        stopSelf()
    }

    /**
     * Releases what the last finished run was holding: its files and its notification.
     *
     * Reads a field rather than the state flow. The flow is reset to Idle by the activity
     * before it asks for a new run, so by the time this is reached there is no Finished
     * left in it to read the outputs back out of.
     */
    private fun releaseFinishedRun() {
        if (finishedOutputs.isEmpty()) return

        Timber.d("Releasing the outputs of the previous finished run")
        deleteOutputFiles(finishedOutputs)
        finishedOutputs = emptyList()
        notifications.cancelResult()
    }

    private fun deleteOutputFiles(outputs: List<Uri>) {
        outputs.forEach { uri ->
            val relative = uri.path?.removePrefix("/shared_media/") ?: return@forEach
            File(cacheDir, "media/$relative").parentFile?.deleteRecursively()
        }
    }

    private fun scheduleCacheCleanup() {
        Timber.d("Scheduling cleanup alarm")
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(applicationContext, CacheCleanUpReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )

        // every 12 hours clear cache
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime(),
            AlarmManager.INTERVAL_HALF_DAY,
            pendingIntent
        )
    }

    companion object {
        const val ACTION_START = "com.caydey.ffshare.action.START_COMPRESSION"
        const val ACTION_CANCEL = "com.caydey.ffshare.action.CANCEL_COMPRESSION"
        const val ACTION_DONE = "com.caydey.ffshare.action.DONE"
        private const val EXTRA_INPUTS = "com.caydey.ffshare.extra.INPUTS"
        private const val NOTIFICATION_MIN_INTERVAL_MS = 500L

        private val _state = MutableStateFlow<CompressionState>(CompressionState.Idle)
        val state: StateFlow<CompressionState> = _state.asStateFlow()

        fun start(context: Context, inputs: ArrayList<Uri>) {
            val intent = Intent(context, CompressionService::class.java)
                .setAction(ACTION_START)
                .putParcelableArrayListExtra(EXTRA_INPUTS, inputs)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, CompressionService::class.java).setAction(ACTION_CANCEL)
            )
        }

        fun done(context: Context) {
            context.startService(
                Intent(context, CompressionService::class.java).setAction(ACTION_DONE)
            )
        }

        /** Called once whoever was watching has acted on a terminal state. */
        fun consume() {
            _state.value = CompressionState.Idle
        }
    }
}
