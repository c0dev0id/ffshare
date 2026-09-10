package com.caydey.ffshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.caydey.ffshare.extensions.parcelable
import com.caydey.ffshare.extensions.parcelableArrayList
import com.caydey.ffshare.utils.CompressionState
import com.caydey.ffshare.utils.MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE
import com.caydey.ffshare.utils.Settings
import com.caydey.ffshare.utils.Utils
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Renders whatever [CompressionService] is doing. It starts a run when it is opened with
 * shared media, but it does not own the run: closing or rotating this activity leaves
 * ffmpeg alone, and reopening it simply reads the latest state again.
 */
class HandleMediaActivity : AppCompatActivity() {
    private val utils: Utils by lazy { Utils(applicationContext) }
    private val settings: Settings by lazy { Settings(applicationContext) }

    private lateinit var txtCommandNumber: TextView
    private lateinit var txtFfmpegCommand: TextView
    private lateinit var txtInputFile: TextView
    private lateinit var txtInputFileSize: TextView
    private lateinit var txtOutputFile: TextView
    private lateinit var txtOutputFileSize: TextView
    private lateinit var txtProcessedTime: TextView
    private lateinit var txtProcessedTimeTotal: TextView
    private lateinit var txtProcessedPercent: TextView
    private lateinit var processedTableRow: TableRow

    private var receivedMedia: ArrayList<Uri>? = null

    // denying this only costs the notifications, the compression itself still runs
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Timber.d("Notification permission granted: %b", granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_handle_media)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindViews()

        receivedMedia = readMediaFromIntent()

        // a terminal state left over from an earlier run must not be read as this one's
        if (receivedMedia != null && CompressionService.state.value !is CompressionState.Running) {
            CompressionService.consume()
        }

        observeCompression()

        if (receivedMedia == null) {
            // opened from the progress notification rather than a share, so there is
            // nothing to start; if nothing is running either, there is nothing to show
            if (CompressionService.state.value == CompressionState.Idle) {
                Toast.makeText(this, getString(R.string.error_no_uri_intent), Toast.LENGTH_LONG).show()
                Timber.d("No files found in shared intent")
                finish()
            }
            return
        }

        if (utils.isReadPermissionGranted) {
            startCompression()
        } else {
            Timber.d("Requesting read permissions")
            utils.requestReadPermissions(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // first time running the app the user is asked to allow reading external storage,
        // after clicking "allow" the app continues handling the media it was shared
        if (requestCode == MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE) {
            Timber.d("Read permissions granted, continuing...")
            startCompression()
        }
    }

    private fun bindViews() {
        txtCommandNumber = findViewById(R.id.txtCommandNumber)
        txtFfmpegCommand = findViewById(R.id.txtFfmpegCommand)
        txtInputFile = findViewById(R.id.txtInputFile)
        txtInputFileSize = findViewById(R.id.txtInputFileSize)
        txtOutputFile = findViewById(R.id.txtOutputFile)
        txtOutputFileSize = findViewById(R.id.txtOutputFileSize)
        txtProcessedTime = findViewById(R.id.txtProcessedTime)
        txtProcessedTimeTotal = findViewById(R.id.txtProcessedTimeTotal)
        txtProcessedPercent = findViewById(R.id.txtProcessedPercent)
        processedTableRow = findViewById(R.id.processedTableRow)

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            CompressionService.cancel(this)
        }
    }

    private fun readMediaFromIntent(): ArrayList<Uri>? = when (intent.action) {
        Intent.ACTION_SEND -> intent.parcelable<Uri>(Intent.EXTRA_STREAM)?.let { arrayListOf(it) }
        Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayList<Uri>(Intent.EXTRA_STREAM)
        else -> null
    }?.takeIf { it.isNotEmpty() }

    private fun startCompression() {
        val media = receivedMedia ?: return
        // a rotation re-delivers the same intent; the run in flight owns it already
        if (CompressionService.state.value is CompressionState.Running) return

        requestNotificationPermission()
        CompressionService.start(this, media)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun observeCompression() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CompressionService.state.collect { render(it) }
            }
        }
    }

    private fun render(state: CompressionState) {
        when (state) {
            is CompressionState.Idle -> Unit

            is CompressionState.Running -> showProgress(state)

            is CompressionState.Cancelled -> {
                CompressionService.consume()
                Toast.makeText(this, getString(R.string.ffmpeg_canceled), Toast.LENGTH_LONG).show()
                finish()
            }

            is CompressionState.Finished -> {
                CompressionService.consume()
                showOutcomeMessage(state)
                if (state.outputs.isNotEmpty()) {
                    startActivity(utils.createShareChooser(state.outputs))
                }
                finish()
            }
        }
    }

    private fun showProgress(state: CompressionState.Running) {
        // "1 of N" is only meaningful for a batch
        txtCommandNumber.text = if (state.total > 1) {
            getString(R.string.command_x_of_y, state.position, state.total)
        } else {
            ""
        }

        txtFfmpegCommand.text = state.command
        txtInputFile.text = state.inputName
        txtInputFileSize.text = utils.bytesToHuman(state.inputSize)
        txtOutputFile.text = state.outputName
        txtOutputFileSize.text = utils.bytesToHuman(state.outputSize)

        processedTableRow.visibility = if (state.hasProgress) View.VISIBLE else View.INVISIBLE
        if (state.hasProgress) {
            txtProcessedTime.text = utils.millisToMicrowaveTime(state.processedMillis)
            txtProcessedTimeTotal.text = utils.millisToMicrowaveTime(state.durationMillis)
            txtProcessedPercent.text = getString(R.string.format_percentage, state.percent)
        }
    }

    private fun showOutcomeMessage(state: CompressionState.Finished) {
        if (state.errorRes != null) {
            Toast.makeText(this, getString(state.errorRes), Toast.LENGTH_LONG).show()
            return
        }
        if (!settings.showStatusMessages) return

        Timber.d("Showing compression size toast message")
        Toast.makeText(
            this,
            getString(
                R.string.media_reduction_message,
                utils.bytesToHuman(state.totalOutputSize),
                state.reductionPercent
            ),
            Toast.LENGTH_LONG
        ).show()
    }
}
