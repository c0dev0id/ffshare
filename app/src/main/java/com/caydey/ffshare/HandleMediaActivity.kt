package com.caydey.ffshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.caydey.ffshare.databinding.ActivityHandleMediaBinding
import com.caydey.ffshare.extensions.parcelable
import com.caydey.ffshare.extensions.parcelableArrayList
import com.caydey.ffshare.utils.CompressionState
import com.caydey.ffshare.utils.MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE
import com.caydey.ffshare.utils.Settings
import com.caydey.ffshare.utils.Utils
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Renders whatever [CompressionService] is doing.
 *
 * Three states:
 * - Ready: media received, service idle → pick resolution and tap Compress
 * - Running: compression in progress → progress + cancel
 * - Finished: done → view stats, Share to send, Done to clean up
 *
 * The activity never owns the compression run; closing or rotating it leaves ffmpeg alone.
 */
class HandleMediaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHandleMediaBinding
    private val utils: Utils by lazy { Utils(applicationContext) }
    private val settings: Settings by lazy { Settings(applicationContext) }

    private var receivedMedia: ArrayList<Uri>? = null

    /** Resolution entries shown in the dropdown — indices match resolutionValues. */
    private val resolutionLabels by lazy {
        resources.getStringArray(R.array.settings_max_resolution)
    }
    private val resolutionValues by lazy {
        resources.getStringArray(R.array.settings_max_resolution_values).map { it.toInt() }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Timber.d("Notification permission granted: %b", granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHandleMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        receivedMedia = readMediaFromIntent()
        setupResolutionDropdown()
        setupButtons()
        observeCompression()

        if (receivedMedia == null) {
            // opened from the result notification: attach to whatever the service has
            if (CompressionService.state.value == CompressionState.Idle) {
                Toast.makeText(this, getString(R.string.error_no_uri_intent), Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }

        // If something is already running or finished, just attach — don't start a new run
        val current = CompressionService.state.value
        if (current is CompressionState.Running || current is CompressionState.Finished) return

        if (utils.isReadPermissionGranted) {
            showReady()
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
        if (requestCode == MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE) {
            Timber.d("Read permissions granted")
            showReady()
        }
    }

    private fun setupResolutionDropdown() {
        binding.actvResolution.setSimpleItems(resolutionLabels)

        // pre-select the currently persisted value
        val currentIndex = resolutionValues.indexOf(settings.maxVideoResolution)
        if (currentIndex >= 0) binding.actvResolution.setText(resolutionLabels[currentIndex], false)
    }

    private fun setupButtons() {
        binding.btnCompress.setOnClickListener { startCompression() }
        binding.btnCancel.setOnClickListener { CompressionService.cancel(this) }
        binding.btnShare.setOnClickListener { shareOutputs() }
        binding.btnDone.setOnClickListener {
            CompressionService.done(this)
            finish()
        }
    }

    private fun readMediaFromIntent(): ArrayList<Uri>? = when (intent.action) {
        Intent.ACTION_SEND -> intent.parcelable<Uri>(Intent.EXTRA_STREAM)?.let { arrayListOf(it) }
        Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayList<Uri>(Intent.EXTRA_STREAM)
        else -> null
    }?.takeIf { it.isNotEmpty() }

    private fun startCompression() {
        val media = receivedMedia ?: return
        if (CompressionService.state.value is CompressionState.Running) return

        // persist the selected resolution before handing off to the service
        val selected = binding.actvResolution.text.toString()
        val index = resolutionLabels.indexOf(selected)
        if (index >= 0) settings.maxVideoResolution = resolutionValues[index]

        requestNotificationPermission()
        CompressionService.start(this, media)
    }

    private fun shareOutputs() {
        val state = CompressionService.state.value as? CompressionState.Finished ?: return
        if (state.outputs.isEmpty()) return
        startActivity(utils.createShareChooser(state.outputs))
        // state is NOT consumed here — the user can share again if something goes wrong
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
            is CompressionState.Idle -> {
                if (receivedMedia != null) showReady()
                // else: we came from a notification after service already cleaned up — do nothing
            }
            is CompressionState.Running -> showRunning(state)
            is CompressionState.Finished -> showFinished(state)
            is CompressionState.Cancelled -> {
                CompressionService.consume()
                Toast.makeText(this, getString(R.string.ffmpeg_canceled), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun showReady() {
        val name = receivedMedia?.firstOrNull()?.let { utils.getFilenameFromUri(it) } ?: ""
        binding.txtReadyFileName.text = name
        showGroup(binding.groupReady)
    }

    private fun showRunning(state: CompressionState.Running) {
        showGroup(binding.groupRunning)

        binding.txtCommandNumber.text = if (state.total > 1) {
            getString(R.string.command_x_of_y, state.position, state.total)
        } else {
            ""
        }
        binding.txtRunningFileName.text = state.outputName

        if (state.hasProgress) {
            binding.progressBar.isIndeterminate = false
            binding.progressBar.progress = state.percent.toInt()
            binding.groupTimeInfo.visibility = View.VISIBLE
            binding.txtProcessedTime.text = utils.millisToMicrowaveTime(state.processedMillis)
            binding.txtProcessedTimeTotal.text = utils.millisToMicrowaveTime(state.durationMillis)
            binding.txtProcessedPercent.text = getString(R.string.format_percentage, state.percent)
        } else {
            binding.progressBar.isIndeterminate = true
            binding.groupTimeInfo.visibility = View.INVISIBLE
        }
    }

    private fun showFinished(state: CompressionState.Finished) {
        showGroup(binding.groupFinished)

        if (state.errorRes != null) {
            binding.txtResultSummary.text = getString(state.errorRes)
            binding.txtResultError.visibility = View.GONE
            binding.btnShare.isEnabled = state.outputs.isNotEmpty()
        } else {
            binding.txtResultSummary.text = getString(
                R.string.result_input_to_output,
                utils.bytesToHuman(state.totalInputSize),
                utils.bytesToHuman(state.totalOutputSize),
                state.reductionPercent
            )
            binding.txtResultError.visibility = View.GONE
            binding.btnShare.isEnabled = true
        }
    }

    private fun showGroup(group: View) {
        binding.groupReady.visibility = View.GONE
        binding.groupRunning.visibility = View.GONE
        binding.groupFinished.visibility = View.GONE
        group.visibility = View.VISIBLE
    }
}
