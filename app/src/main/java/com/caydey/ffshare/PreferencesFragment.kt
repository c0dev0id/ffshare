package com.caydey.ffshare

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.preference.*
import timber.log.Timber

class PreferencesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        dynamicallyShowCustomName()
        dynamicallyAddCustomParamTooltips()
        wireBatteryOptimizationSettings()
    }

    /**
     * Hands the user off to the system screen instead of asking for the exemption
     * ourselves: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is restricted to app categories this
     * one does not belong to, and a foreground service does not need it on stock Android.
     */
    private fun wireBatteryOptimizationSettings() {
        val preference = findPreference<Preference>("pref_battery_optimization")
        preference?.setOnPreferenceClickListener {
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            } catch (e: ActivityNotFoundException) {
                Timber.d(e, "No battery optimization settings screen available")
                Toast.makeText(
                    context,
                    getString(R.string.settings_battery_optimization_unavailable),
                    Toast.LENGTH_LONG
                ).show()
            }
            true
        }
    }
    private fun dynamicallyAddCustomParamTooltips() {
        val customParamKeys = arrayOf("pref_custom_video_params", "pref_custom_audio_params", "pref_custom_image_params")
        for (customParamKey in customParamKeys) {
            val element = findPreference<EditTextPreference>(customParamKey)
            element?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        }
    }
    private fun dynamicallyShowCustomName() {
        // only show pref_compressed_media_custom_name if pref_compressed_media_name is "Custom"
        val customMediaNamePreference = findPreference<EditTextPreference>("pref_compressed_media_custom_name")
        val compressedMediaNamePreference = findPreference<ListPreference>("pref_compressed_media_name")
        compressedMediaNamePreference?.setOnPreferenceChangeListener { _, value ->
            customMediaNamePreference?.isVisible = (value == "CUSTOM")
            true
        }
        // trigger update for initial load
        compressedMediaNamePreference?.callChangeListener(compressedMediaNamePreference.value)
    }
}