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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBatch(intent)
            ACTION_CANCEL -> cancelBatch()
            else -> if (job?.isActive != true) stopSelf()
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

        val inputs = intent.parcelableArrayList<Uri>(EXTRA_INPUTS)
        if (inputs.isNullOrEmpty()) {
            Timber.d("No files in start request")
            finishUp(CompressionState.Idle)
            return
        }

        cancelled = false
        job = scope.launch {
            try {
                compressor.compress(inputs).collect { state ->
                    _state.value = state
                    if (state is CompressionState.Running) notifications.updateProgress(state)
                }
            } finally {
                if (cancelled) _state.value = CompressionState.Cancelled
                finishUp(_state.value)
            }
        }
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

    /**
     * Runs for every outcome, cancellation included. The result notification is only
     * worth posting when the run ended with nobody looking at it; otherwise the activity
     * reads the same state and opens the share sheet itself.
     */
    private fun finishUp(outcome: CompressionState) {
        scheduleCacheCleanup()

        if (outcome is CompressionState.Finished && !appIsInForeground()) {
            notifications.postResult(outcome)
            // the notification carries the outcome now, so nothing is left to consume
            _state.value = CompressionState.Idle
        }

        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun appIsInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

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
        private const val EXTRA_INPUTS = "com.caydey.ffshare.extra.INPUTS"

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

        /** Called once whoever was watching has acted on a terminal state. */
        fun consume() {
            _state.value = CompressionState.Idle
        }
    }
}
