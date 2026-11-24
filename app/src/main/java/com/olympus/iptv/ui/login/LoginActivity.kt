package com.olympus.iptv.ui.login

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private var mode: PlaylistType? = null
    private var connecting = false
    private var dashBase = ""
    private var knownDevice = ""
    private var passwordVisible = false
    private val loader = PlaylistLoader()
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

    private val store get() = (application as OlympusIptvApp).playlistStore

    private fun pasteInto(field: android.widget.EditText) {
        val clip = getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        if (text.isBlank()) {
            android.widget.Toast.makeText(this, R.string.paste_empty, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        field.setText(text)
        field.setSelection(text.length)
    }

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
        binding.onboardingStep.text = getString(R.string.onboarding_step, 1, getString(R.string.onboarding_playlist))
        listOf(binding.inputEmail, binding.inputUrl, binding.inputUser, binding.inputPass, binding.inputZip)
            .forEach { SoftKeyboard.showWhenFocused(this, it) }
        wireFieldOrder()
        if (com.olympus.iptv.ui.DeviceForm.isTouchPrimary(this)) {
            binding.btnPasteUrl.visibility = View.VISIBLE
            binding.btnPasteUrl.setOnClickListener { pasteInto(binding.inputUrl) }
        }

        com.olympus.iptv.portal.DashboardHost.addUrlListener(dashboardUrls)
        runCatching {
            showDashboard(LineupClient.peekDashboardUrl() ?: com.olympus.iptv.portal.DashboardHost.url())
        }.onFailure { com.olympus.iptv.AppLog.error("Login", "Could not show the dashboard address", it) }
        lifecycleScope.launch {
            val deviceId = store.deviceId()
            if (isFinishing) return@launch
            knownDevice = deviceId
            binding.deviceId.text = getString(R.string.device_id_label, deviceId)
            if (dashBase.isNotBlank()) showDashboard(dashBase)
        }
        lifecycleScope.launch {
            val url = runCatching { LineupClient(this@LoginActivity).dashboardAddress() }.getOrNull()
            if (!url.isNullOrBlank() && !isFinishing) showDashboard(url)
        }
        lifecycleScope.launch {
            store.session.collect { saved ->
                if (!connecting && saved != null && !saved.email.isNullOrBlank() && !isFinishing) {
                    connecting = true
                    openGuide(saved.id)
                }
            }
        }
    }

    private fun wireFieldOrder() {
        val order = listOf(
            binding.inputEmail,
            binding.inputUrl,
            binding.inputUser,
            binding.inputPass,
            binding.inputZip,
            binding.checkInternational,
            binding.checkVod,
            binding.btnConnect
        )
        order.forEachIndexed { index, view ->
            view.nextFocusUpId = order.getOrNull(index - 1)?.id ?: view.id
            view.nextFocusDownId = order.getOrNull(index + 1)?.id ?: view.id
            view.nextFocusLeftId = view.id
            if (view !== binding.inputPass) view.nextFocusRightId = view.id
        }
        binding.inputPass.nextFocusRightId = binding.btnTogglePass.id
        binding.btnTogglePass.nextFocusLeftId = binding.inputPass.id
        binding.btnTogglePass.nextFocusRightId = binding.btnTogglePass.id
        binding.btnTogglePass.nextFocusUpId = binding.inputUser.id
        binding.btnTogglePass.nextFocusDownId = binding.inputZip.id
        val next = listOf(
            binding.inputEmail to binding.inputUrl,
            binding.inputUrl to binding.inputUser,
            binding.inputUser to binding.inputPass,
            binding.inputPass to binding.inputZip
        )
        next.forEach { (from, to) ->
            from.imeOptions = EditorInfo.IME_ACTION_NEXT
            from.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
                    to.requestFocus()
                    true
                } else {
                    false
                }
            }
        }
        binding.inputZip.imeOptions = EditorInfo.IME_ACTION_DONE
        binding.inputZip.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnConnect.requestFocus()
                true
            } else {
                false
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
        dashBase = url.substringBefore('?')
        val shown = if (knownDevice.isBlank()) {
            dashBase
        } else {
            dashBase + "?device=" + Uri.encode(knownDevice)
        }
        binding.portalHint.text = getString(R.string.portal_hint, shown)
        binding.dashboardAddress.text = shown
        binding.qrCode.setImageBitmap(QrBitmap.encode(shown, 256))
        binding.copyTvUrl.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Olympus TV URL", shown))
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
            showProblem(getString(R.string.login_email_required))
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showProblem(getString(R.string.login_url_required))
            return
        }
        if (user.isBlank() || pass.isBlank()) {
            showProblem(getString(R.string.login_credentials_required))
            return
        }
        val zip = binding.inputZip.text?.toString()?.filter { it.isDigit() }?.take(5).orEmpty()
        if (zip.length != 5) {
            showProblem(getString(R.string.login_zip_required))
            return
        }

        val includeVod = binding.checkVod.isChecked
        connecting = true
        setBusy(true)
        lifecycleScope.launch {
            val home = withContext(Dispatchers.IO) { lookupZip(zip) }
            if (home == null) {
                connecting = false
                setBusy(false)
                showProblem(getString(R.string.login_zip_invalid))
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
                val ok = withTimeoutOrNull(20_000L) { loader.validate(playlist) }
                if (ok != true) {
                    connecting = false
                    showProblem(
                        if (ok == null) getString(R.string.login_provider_timeout) else getString(R.string.error_load)
                    )
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
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                connecting = false
                showProblem(e.message ?: getString(R.string.error_load))
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

    private fun suppressTransition() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun openBrowse(playlistId: String) {
        startActivity(
            Intent(this, BrowseActivity::class.java)
                .putExtra(BrowseActivity.EXTRA_PLAYLIST_ID, playlistId)
        )
        finish()
        suppressTransition()
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
        suppressTransition()
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

    private fun showProblem(message: String) {
        if (isFinishing || isDestroyed) return
        SoftKeyboard.hide(this)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.login_problem_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showError(message: String) {
        showProblem(message)
    }
}
