package com.olympus.iptv.ui.login

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.data.PlaylistType
import com.olympus.iptv.data.SavedPlaylist
import com.olympus.iptv.databinding.ActivityLoginBinding
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.playlist.PlaylistLoader
import com.olympus.iptv.playlist.XtreamClient
import com.olympus.iptv.ui.SoftKeyboard
import com.olympus.iptv.ui.browse.BrowseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private var mode: PlaylistType? = null
    private var passwordVisible = false
    private val loader = PlaylistLoader()
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

    private val store get() = (application as OlympusIptvApp).playlistStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = runBlocking { store.session.first() }
        if (saved != null && !saved.email.isNullOrBlank()) {
            openBrowse(saved.id)
            return
        }
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnM3u.setOnClickListener { selectMode(PlaylistType.M3U) }
        binding.btnXtream.setOnClickListener { selectMode(PlaylistType.XTREAM) }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnConnect.setOnClickListener { connect() }
        binding.btnTogglePass.setOnClickListener { togglePassword() }
        binding.checkVod.isChecked = true
        binding.checkInternational.isChecked = false
        listOf(binding.btnM3u, binding.btnXtream, binding.btnCancel, binding.btnConnect).forEach { button ->
            button.isFocusable = true
            button.isFocusableInTouchMode = true
        }
        binding.btnM3u.post { binding.btnM3u.requestFocus() }
        listOf(binding.inputEmail, binding.inputUrl, binding.inputUser, binding.inputPass, binding.inputZip)
            .forEach { SoftKeyboard.showWhenFocused(this, it) }

        com.olympus.iptv.portal.DashboardHost.addUrlListener(dashboardUrls)
        runCatching {
            showDashboard(LineupClient.peekDashboardUrl() ?: com.olympus.iptv.portal.DashboardHost.url())
        }.onFailure { com.olympus.iptv.AppLog.error("Login", "Could not show the dashboard address", it) }
        lifecycleScope.launch {
            val deviceId = store.deviceId()
            if (!isFinishing) binding.deviceId.text = getString(R.string.device_id_label, deviceId)
        }
        lifecycleScope.launch {
            val url = runCatching { LineupClient(this@LoginActivity).dashboardAddress() }.getOrNull()
            if (!url.isNullOrBlank() && !isFinishing) showDashboard(url)
        }
        lifecycleScope.launch {
            store.session.collect { saved ->
                if (saved != null && !saved.email.isNullOrBlank() && !isFinishing) {
                    openBrowse(saved.id)
                }
            }
        }
    }

    private val dashboardUrls: (String) -> Unit = { url ->
        runOnUiThread { if (!isDestroyed) showDashboard(url) }
    }

    override fun onDestroy() {
        com.olympus.iptv.portal.DashboardHost.removeUrlListener(dashboardUrls)
        super.onDestroy()
    }

    private fun showDashboard(url: String) {
        binding.portalHint.text = getString(R.string.portal_hint, url)
        binding.dashboardAddress.text = url
        binding.qrCode.setImageBitmap(QrBitmap.encode(url, 256))
        binding.copyTvUrl.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Olympus TV URL", url))
            android.widget.Toast.makeText(this, R.string.copy_tv_url_done, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectMode(type: PlaylistType) {
        mode = type
        binding.loginForm.visibility = View.VISIBLE
        binding.chooser.visibility = View.GONE
        binding.compactSlot.visibility = View.VISIBLE
        binding.errorText.visibility = View.GONE
        if (binding.btnM3u.parent !== binding.compactSlot) {
            park(binding.btnM3u)
            park(binding.btnXtream)
            park(binding.btnCancel)
            binding.m3uDetail.visibility = View.GONE
            binding.xtreamDetail.visibility = View.GONE
            binding.cancelDetail.visibility = View.GONE
            binding.m3uChevron.visibility = View.GONE
            binding.xtreamChevron.visibility = View.GONE
            binding.cancelChevron.visibility = View.GONE
        }
        binding.checkVod.isChecked = true
        val m3u = type == PlaylistType.M3U
        styleMode(binding.btnM3u, binding.m3uTitle, m3u)
        styleMode(binding.btnXtream, binding.xtreamTitle, !m3u)
        binding.inputUrl.hint = getString(if (m3u) R.string.m3u_url else R.string.xtream_host)
        binding.inputEmail.requestFocus()
    }

    private fun styleMode(card: View, title: android.widget.TextView, selected: Boolean) {
        card.background = ContextCompat.getDrawable(
            this,
            if (selected) R.drawable.bg_button else R.drawable.bg_button_secondary
        )
        title.setTextColor(ContextCompat.getColor(this, if (selected) R.color.bg else R.color.text_primary))
    }

    private fun park(view: View) {
        (view.parent as android.view.ViewGroup).removeView(view)
        val density = resources.displayMetrics.density
        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            (44 * density).toInt()
        )
        params.topMargin = (6 * density).toInt()
        binding.compactSlot.addView(view, params)
    }

    private fun connect() {
        val selected = mode
        if (selected == null) {
            showError(getString(R.string.login_pick_type))
            return
        }
        val email = binding.inputEmail.text?.toString()?.trim().orEmpty()
        val url = binding.inputUrl.text?.toString()?.trim().orEmpty()
        val user = binding.inputUser.text?.toString()?.trim().orEmpty()
        val pass = binding.inputPass.text?.toString()?.trim().orEmpty()
        if (!email.contains("@") || !email.contains(".")) {
            showError(getString(R.string.login_email_required))
            return
        }
        if (!url.startsWith("http")) {
            showError(getString(R.string.login_url_required))
            return
        }
        if (user.isBlank() || pass.isBlank()) {
            showError(getString(R.string.login_credentials_required))
            return
        }
        val zip = binding.inputZip.text?.toString()?.filter { it.isDigit() }?.take(5).orEmpty()
        if (zip.length != 5) {
            showError(getString(R.string.login_zip_required))
            return
        }

        val includeVod = binding.checkVod.isChecked
        setBusy(true)
        lifecycleScope.launch {
            val home = withContext(Dispatchers.IO) { lookupZip(zip) }
            if (home == null) {
                setBusy(false)
                showError(getString(R.string.login_zip_invalid))
                return@launch
            }
            val playlist = playlistFor(selected, email, url, user, pass, includeVod).copy(
                homeZip = zip,
                homeCountry = home.country,
                homeLat = home.lat,
                homeLon = home.lon,
                includeInternational = binding.checkInternational.isChecked
            )
            try {
                val ok = loader.validate(playlist)
                if (!ok) {
                    showError(getString(R.string.error_load))
                    return@launch
                }
                store.saveSession(playlist)
                val lineup = store.lineup(playlist.id)
                store.saveLineup(
                    playlist.id,
                    lineup.copy(
                        includeInternational = binding.checkInternational.isChecked,
                        revision = System.currentTimeMillis()
                    )
                )
                openGuide(playlist.id)
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_load))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun playlistFor(
        selected: PlaylistType,
        email: String,
        url: String,
        user: String,
        pass: String,
        includeVod: Boolean
    ): SavedPlaylist {
        return when (selected) {
            PlaylistType.M3U -> SavedPlaylist(
                id = PlaylistStore.newId(),
                name = user,
                type = PlaylistType.M3U,
                m3uUrl = url,
                username = user,
                password = pass,
                includeVod = includeVod,
                email = email
            )
            PlaylistType.XTREAM -> SavedPlaylist(
                id = PlaylistStore.newId(),
                name = user,
                type = PlaylistType.XTREAM,
                xtreamHost = XtreamClient.normalizeHost(url),
                xtreamUsername = user,
                xtreamPassword = pass,
                username = user,
                password = pass,
                includeVod = includeVod,
                email = email
            )
        }
    }

    private data class HomeZip(val lat: Double, val lon: Double, val country: String)

    private fun lookupZip(zip: String): HomeZip? {
        val request = Request.Builder().url("https://api.zippopotam.us/us/$zip").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string().orEmpty())
                val places = json.optJSONArray("places") ?: return null
                if (places.length() == 0) return null
                val place = places.getJSONObject(0)
                val lat = place.optString("latitude").toDoubleOrNull() ?: return null
                val lon = place.optString("longitude").toDoubleOrNull() ?: return null
                HomeZip(lat, lon, "us")
            }
        }.getOrNull()
    }

    private fun openBrowse(playlistId: String) {
        startActivity(
            Intent(this, BrowseActivity::class.java)
                .putExtra(BrowseActivity.EXTRA_PLAYLIST_ID, playlistId)
        )
        finish()
        overridePendingTransition(0, 0)
    }

    private suspend fun openGuide(playlistId: String) {
        val skip = when (store.favoritesSetupDone()) {
            true -> true
            false -> false
            null -> {
                val already = withContext(Dispatchers.IO) {
                    (application as OlympusIptvApp).catalogDb.bucketCounts(playlistId).firstOrNull() ?: 0
                } > 0
                if (already) store.setFavoritesSetupDone(true)
                already
            }
        }
        val next = if (skip) {
            Intent(this, BrowseActivity::class.java)
                .putExtra(BrowseActivity.EXTRA_PLAYLIST_ID, playlistId)
        } else {
            Intent(this, FavoritesSetupActivity::class.java)
                .putExtra(FavoritesSetupActivity.EXTRA_PLAYLIST_ID, playlistId)
        }
        startActivity(next)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun togglePassword() {
        passwordVisible = !passwordVisible
        val cursor = binding.inputPass.selectionEnd.coerceAtLeast(0)
        binding.inputPass.inputType = if (passwordVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        binding.inputPass.typeface = android.graphics.Typeface.DEFAULT
        binding.inputPass.setSelection(cursor.coerceAtMost(binding.inputPass.text?.length ?: 0))
        binding.btnTogglePass.setImageResource(
            if (passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        )
        binding.btnTogglePass.contentDescription = getString(
            if (passwordVisible) R.string.hide_password else R.string.show_password
        )
    }

    private fun setBusy(busy: Boolean) {
        binding.loading.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnConnect.isEnabled = !busy
        binding.btnM3u.isEnabled = !busy
        binding.btnXtream.isEnabled = !busy
        binding.btnConnect.text = getString(if (busy) R.string.loading else R.string.connect)
    }

    private fun showError(message: String) {
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.errorText.post {
            binding.errorText.requestRectangleOnScreen(android.graphics.Rect(0, 0, binding.errorText.width, binding.errorText.height), false)
        }
    }
}
