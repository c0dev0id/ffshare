package com.caydey.ffshare

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.text.method.LinkMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.caydey.ffshare.databinding.ActivityMainBinding
import com.caydey.ffshare.update.ReleaseInfo
import com.caydey.ffshare.update.UpdateChecker
import com.caydey.ffshare.utils.Utils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val utils: Utils by lazy { Utils(applicationContext) }

    private val selectedFileLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isEmpty()) return@registerForActivityResult
        val intent = Intent(this, HandleMediaActivity::class.java)
            .setAction(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Parcelable>(it))
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.lblVersion.text = getString(R.string.version, App.versionName)
        binding.lblIntroductionLine0.movementMethod = LinkMovementMethod.getInstance()
        binding.lblIntroductionLine1.movementMethod = LinkMovementMethod.getInstance()
        binding.lblIntroductionLine2.movementMethod = LinkMovementMethod.getInstance()

        binding.btnSelectFile.setOnClickListener {
            selectedFileLauncher.launch(utils.getAllowedMimes())
        }
        binding.btnCheckUpdate.setOnClickListener { runUpdateFlow() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> startActivity(Intent(applicationContext, PreferencesActivity::class.java))
            R.id.action_history -> startActivity(Intent(applicationContext, LogsActivity::class.java))
        }
        return super.onOptionsItemSelected(item)
    }

    private fun runUpdateFlow() {
        val checker = UpdateChecker(this)
        val button = binding.btnCheckUpdate

        fun setButton(labelRes: Int, enabled: Boolean) {
            button.isEnabled = enabled
            button.setText(labelRes)
        }

        fun showMessage(text: String) {
            if (isFinishing || isDestroyed) return
            MaterialAlertDialogBuilder(this)
                .setMessage(text)
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        fun downloadAndInstall(release: ReleaseInfo) {
            setButton(R.string.downloading, enabled = false)
            binding.progressUpdate.apply {
                isIndeterminate = true
                visibility = View.VISIBLE
            }
            lifecycleScope.launch {
                try {
                    val file = checker.download(release) { percent ->
                        if (binding.progressUpdate.isIndeterminate) binding.progressUpdate.isIndeterminate = false
                        binding.progressUpdate.progress = percent
                    }
                    startActivity(checker.installIntent(file))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showMessage(getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
                } finally {
                    setButton(R.string.check_for_updates, enabled = true)
                    binding.progressUpdate.visibility = View.GONE
                }
            }
        }

        fun promptInstall(release: ReleaseInfo) {
            if (isFinishing || isDestroyed) return
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.update_available, release.versionName))
                .setPositiveButton(R.string.update_download) { _, _ -> downloadAndInstall(release) }
                .setNegativeButton(R.string.cancel_ffmpeg, null)
                .show()
        }

        setButton(R.string.checking_updates, enabled = false)
        lifecycleScope.launch {
            try {
                val release = checker.check()
                if (release == null) showMessage(getString(R.string.update_none))
                else promptInstall(release)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                setButton(R.string.check_for_updates, enabled = true)
            }
        }
    }
}
