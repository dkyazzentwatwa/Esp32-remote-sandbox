package org.espsketchide.app.settings

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.espsketchide.app.R
import org.espsketchide.app.databinding.ActivitySettingsBinding
import org.espsketchide.app.ui.Insets

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applyToAppBar(binding.appBar)
        Insets.applyBottomPadding(binding.settingsContainer)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = PREFS_NAME
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<ListPreference>(KEY_THEME)?.setOnPreferenceChangeListener { _, newValue ->
            AppCompatDelegate.setDefaultNightMode(ThemeMode.fromKey(newValue as String).nightMode)
            true
        }
        // useSimpleSummaryProvider would drop the "not for building" note, so build the summary here.
        findPreference<ListPreference>(KEY_BOARD)?.setSummaryProvider { pref ->
            val board = (pref as ListPreference).entry ?: getString(R.string.board_esp32)
            getString(R.string.settings_board_summary, board)
        }
        findPreference<Preference>(KEY_LICENSES)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_licenses)
                .setMessage(licensesText())
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
            true
        }
    }

    /** NOTICES.md first, then every bundled license text. */
    private fun licensesText(): String {
        val assets = requireContext().assets
        val others = assets.list(LICENSES_DIR).orEmpty().filter { it != NOTICES_FILE }.sorted()
        return (listOf(NOTICES_FILE) + others).joinToString("\n\n————————\n\n") { name ->
            assets.open("$LICENSES_DIR/$name").bufferedReader().use { it.readText() }.trim()
        }
    }

    private companion object {
        const val KEY_LICENSES = "licenses"
        const val LICENSES_DIR = "licenses"
        const val NOTICES_FILE = "NOTICES.md"
    }
}
