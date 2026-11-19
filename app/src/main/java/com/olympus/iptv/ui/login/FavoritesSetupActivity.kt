package com.olympus.iptv.ui.login

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.AutoFavorites
import com.olympus.iptv.databinding.ActivityFavoritesSetupBinding
import com.olympus.iptv.playlist.CatalogIngest
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.ui.DeviceForm
import com.olympus.iptv.ui.InsetsPad
import com.olympus.iptv.ui.browse.BrowseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class FavoritesSetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFavoritesSetupBinding
    private val playlistId by lazy { intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty() }
    private val fromGuide by lazy { intent.getBooleanExtra(EXTRA_FROM_GUIDE, false) }
    private var channels: List<AutoFavorites.GuideChannel>? = null
    private var busy = false
    private var catalogReady = false
    private var homeCountry = "us"
    private var savedHome: Place? = null
    private var lastCard: View? = null
    private var currentSport = ""
    private val chosen = mutableMapOf<String, MutableSet<String>>()
    private val touchPrimary by lazy { DeviceForm.isTouchPrimary(this) }
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (touchPrimary) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.setDecorFitsSystemWindows(window, false)
            InsetsPad.apply(
                binding.root,
                top = true,
                bottom = true,
                left = true,
                right = true
            )
            enableTouchScrolling()
        }
        if (fromGuide || touchPrimary) {
            binding.btnBack.visibility = View.VISIBLE
            binding.btnBack.setOnClickListener { leaveSetup() }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leaveSetup()
            }
        })
        binding.cardLocal.setOnClickListener { choose(binding.cardLocal); addLocal() }
        binding.cardSports.setOnClickListener { choose(binding.cardSports); addSports() }
        binding.cardTeams.setOnClickListener { choose(binding.cardTeams); showSports() }
        binding.cardSlingBlue.setOnClickListener { choose(binding.cardSlingBlue); addSling(orange = false) }
        binding.cardSlingOrange.setOnClickListener { choose(binding.cardSlingOrange); addSling(orange = true) }
        binding.cardFilo.setOnClickListener { choose(binding.cardFilo); addPackage("filo") }
        binding.cardUtoob.setOnClickListener { choose(binding.cardUtoob); addPackage("utoob") }
        binding.cardFubo.setOnClickListener { choose(binding.cardFubo); addPackage("fubo") }
        binding.cardDirectv.setOnClickListener { choose(binding.cardDirectv); addPackage("directv") }
        binding.cardFrndly.setOnClickListener { choose(binding.cardFrndly); addPackage("frndly") }
        binding.cardHulu.setOnClickListener { choose(binding.cardHulu); addPackage("hulu") }
        binding.btnAddTeams.setOnClickListener { addTeams() }
        binding.btnNext.setOnClickListener { openGuide() }
        AutoFavorites.sports.forEach { sport -> binding.sportRow.addView(sportChip(sport)) }
        val catalogReadyOnOpen = intent.getBooleanExtra(EXTRA_CATALOG_READY, false)
        if (catalogReadyOnOpen) {
            showFavorites()
            focusCard(binding.cardLocal)
        } else {
            showLoading(true)
        }
        lifecycleScope.launch {
            loadSavedHome()
            var showedSplash = !catalogReadyOnOpen
            if (catalogReadyOnOpen) {
                val missing = runCatching { catalogNeedsDownload() }.getOrDefault(false)
                if (missing && !isFinishing) {
                    showedSplash = true
                    showLoading(true)
                    runCatching { pullCatalog() }
                }
            } else {
                runCatching { pullCatalog() }
            }
            if (isFinishing) return@launch
            val international = (application as OlympusIptvApp).playlistStore.lineup(playlistId).includeInternational == true
            if (!international) applyCountryFilter(false)
            showFavorites()
            if (showedSplash || currentFocus == null) focusCard(binding.cardLocal)
        }
    }

    private fun leaveSetup() {
        if (binding.sportsPanel.visibility == View.VISIBLE) {
            binding.sportsPanel.visibility = View.GONE
            binding.setupScroll.visibility = View.VISIBLE
            binding.cardBoard.visibility = View.VISIBLE
            focusCard(binding.cardTeams)
        } else if (fromGuide) {
            finish()
        } else {
            openGuide()
        }
    }

    private fun enableTouchScrolling() {
        binding.setupScroll.isSmoothScrollingEnabled = true
        binding.setupScroll.isNestedScrollingEnabled = true
        binding.teamScroll.isSmoothScrollingEnabled = true
        binding.teamScroll.isNestedScrollingEnabled = true
        listOf(
            binding.cardLocal, binding.cardSports, binding.cardTeams, binding.cardSlingBlue,
            binding.cardSlingOrange, binding.cardFilo, binding.cardUtoob, binding.cardFubo,
            binding.cardDirectv, binding.cardFrndly, binding.cardHulu, binding.btnNext
        ).forEach { card ->
            card.isFocusableInTouchMode = false
        }
    }

    private fun focusCard(card: View) {
        if (touchPrimary) return
        card.requestFocus()
    }

    private suspend fun catalogNeedsDownload(): Boolean {
        if (playlistId.isBlank()) return false
        val app = application as OlympusIptvApp
        val saved = withContext(Dispatchers.IO) {
            app.playlistStore.playlists.first().firstOrNull { it.id == playlistId }
        } ?: return false
        val counts = withContext(Dispatchers.IO) { app.catalogDb.bucketCounts(saved.id) }
        return counts[0] == 0 ||
            (saved.includeVod && counts[1] == 0 && counts[2] == 0 && !CatalogIngest.alreadySettled(saved.id))
    }

    private suspend fun pullCatalog() {
        if (playlistId.isBlank()) return
        val app = application as OlympusIptvApp
        var saved = withContext(Dispatchers.IO) {
            app.playlistStore.playlists.first().firstOrNull { it.id == playlistId }
        } ?: return
        if (!saved.includeVod) {
            saved = saved.copy(includeVod = true)
            withContext(Dispatchers.IO) { app.playlistStore.saveSession(saved) }
        }
        val db = app.catalogDb
        var counts = withContext(Dispatchers.IO) { db.bucketCounts(saved.id) }
        publishCounts(counts, 0)
        val needPull = counts[0] == 0 ||
            (saved.includeVod && counts[1] == 0 && counts[2] == 0 && !CatalogIngest.alreadySettled(saved.id))
        if (!needPull) {
            publishCounts(counts, 100)
            return
        }
        CatalogIngest.start(app, saved)
        val job = app.catalogRefresh
        var steady = 0
        var lastKey = ""
        while (job?.isActive == true && !isFinishing) {
            counts = withContext(Dispatchers.IO) { db.bucketCounts(saved.id) }
            val percent = CatalogIngest.progress.overall(saved.includeVod)
            publishCounts(counts, percent)
            val key = "${counts[0]}:${counts[1]}:${counts[2]}"
            val filled = counts[0] > 0 && (!saved.includeVod || (counts[1] > 0 && counts[2] > 0))
            if (percent >= 100 && counts[0] > 0) break
            if (key == lastKey && filled) {
                steady += 1
                if (steady >= 4) break
            } else {
                steady = 0
            }
            lastKey = key
            delay(800)
        }
        publishCounts(withContext(Dispatchers.IO) { db.bucketCounts(saved.id) }, 100)
    }

    private fun publishCounts(counts: IntArray, percent: Int = CatalogIngest.progress.overall(true)) {
        val format = NumberFormat.getIntegerInstance(Locale.US)
        binding.catalogPercent.text = getString(R.string.catalog_percent, percent.coerceIn(0, 100))
        binding.catalogProgress.text = getString(
            R.string.catalog_stats,
            format.format(counts.getOrElse(0) { 0 }),
            format.format(counts.getOrElse(1) { 0 }),
            format.format(counts.getOrElse(2) { 0 })
        )
    }

    private fun showFavorites() {
        catalogReady = true
        binding.loadingPane.visibility = View.GONE
        binding.setupHeading.visibility = View.VISIBLE
        binding.setupScroll.visibility = View.VISIBLE
        binding.cardBoard.visibility = View.VISIBLE
        binding.btnNext.visibility = if (fromGuide) View.GONE else View.VISIBLE
    }

    private fun showLoading(loading: Boolean) {
        catalogReady = !loading
        binding.loadingPane.visibility = if (loading) View.VISIBLE else View.GONE
        binding.setupHeading.visibility = if (loading) View.GONE else View.VISIBLE
        binding.setupScroll.visibility = if (loading) View.GONE else View.VISIBLE
        binding.cardBoard.visibility = if (loading) View.GONE else View.VISIBLE
    }

    private fun choose(card: View) {
        lastCard = card
        markPicked(card)
    }

    private fun openGuide() {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).playlistStore.account(playlistId)
            }
            if (saved == null) {
                com.olympus.iptv.AppLog.error("Favorites", "Continue found no saved account")
                binding.statusText.setText(R.string.favorites_setup_guide_failed)
                return@launch
            }
            com.olympus.iptv.AppLog.info("Favorites", "Opening the guide")
            withContext(Dispatchers.IO) {
                (application as OlympusIptvApp).playlistStore.setFavoritesSetupDone(true)
            }
            try {
                startActivity(
                    Intent(this@FavoritesSetupActivity, BrowseActivity::class.java)
                        .putExtra(BrowseActivity.EXTRA_PLAYLIST_ID, saved.id)
                        .putExtra(BrowseActivity.EXTRA_SKIP_RESUME, true)
                )
                finish()
            } catch (error: Exception) {
                com.olympus.iptv.AppLog.error("Favorites", "Opening the guide failed", error)
                binding.statusText.setText(R.string.favorites_setup_guide_failed)
            }
        }
    }

    private fun addLocal() {
        if (busy || !catalogReady) return
        setBusy(true)
        binding.statusText.setText(R.string.favorites_setup_finding)
        lifecycleScope.launch {
            val outcome = runCatching {
                val guide = guideChannels()
                if (guide.isEmpty()) return@runCatching "" to (0 to 0)
                val place = resolvePlace(guide, wantLocals = true)
                val locals = if (place == null) null else AutoFavorites.localStations(guide, place.lat, place.lon)
                val picks = locals?.picks.orEmpty() + AutoFavorites.nationalPicks(guide)
                val market = if (locals != null && locals.picks.isNotEmpty()) locals.market else ""
                market to savePicks(picks)
            }
            if (isFinishing) return@launch
            setBusy(false)
            lastCard?.let { focusCard(it) }
            val result = outcome.getOrNull()
            if (result == null) {
                binding.statusText.setText(R.string.favorites_setup_none)
                return@launch
            }
            val market = result.first
            val added = result.second.first
            val already = result.second.second
            binding.statusText.text = when {
                market.isNotEmpty() && added > 0 -> getString(R.string.favorites_setup_added_city, added, market)
                market.isEmpty() && added > 0 -> getString(R.string.favorites_setup_city_failed, added)
                already > 0 -> getString(R.string.favorites_setup_already)
                else -> getString(R.string.favorites_setup_none)
            }
        }
    }

    private fun addSling(orange: Boolean) {
        if (busy || !catalogReady) return
        setBusy(true)
        binding.statusText.setText(R.string.favorites_setup_adding)
        lifecycleScope.launch {
            val outcome = runCatching {
                val guide = guideChannels()
                val place = if (guide.isEmpty()) null else resolvePlace(guide, wantLocals = true)
                val located = AutoFavorites.slingPicks(orange, guide, place?.lat, place?.lon)
                located.market to savePicks(located.picks)
            }
            if (isFinishing) return@launch
            setBusy(false)
            lastCard?.let { focusCard(it) }
            val result = outcome.getOrNull()
            if (result == null) {
                binding.statusText.setText(R.string.favorites_setup_none)
                return@launch
            }
            val market = result.first
            val added = result.second.first
            val already = result.second.second
            binding.statusText.text = when {
                market.isNotEmpty() && added > 0 -> getString(R.string.favorites_setup_added_city, added, market)
                added > 0 -> getString(R.string.favorites_setup_added, added)
                already > 0 -> getString(R.string.favorites_setup_already)
                else -> getString(R.string.favorites_setup_none)
            }
        }
    }

    private fun addPackage(id: String) {
        if (busy || !catalogReady) return
        setBusy(true)
        binding.statusText.setText(R.string.favorites_setup_adding)
        lifecycleScope.launch {
            val outcome = runCatching {
                val guide = guideChannels()
                val place = if (guide.isEmpty()) {
                    null
                } else if (AutoFavorites.wantsLocalStations(id)) {
                    resolvePlace(guide, wantLocals = true)
                } else {
                    withContext(Dispatchers.IO) { locate() }?.takeIf { it.country.equals("US", ignoreCase = true) }
                }
                val located = AutoFavorites.packagePicks(id, guide, place?.lat, place?.lon)
                located.market to savePicks(located.picks)
            }
            if (isFinishing) return@launch
            setBusy(false)
            lastCard?.let { focusCard(it) }
            val result = outcome.getOrNull()
            if (result == null) {
                binding.statusText.setText(R.string.favorites_setup_none)
                return@launch
            }
            val market = result.first
            val added = result.second.first
            val already = result.second.second
            binding.statusText.text = when {
                market.isNotEmpty() && added > 0 -> getString(R.string.favorites_setup_added_city, added, market)
                added > 0 -> getString(R.string.favorites_setup_added, added)
                already > 0 -> getString(R.string.favorites_setup_already)
                else -> getString(R.string.favorites_setup_none)
            }
        }
    }

    private suspend fun applyCountryFilter(includeAll: Boolean) {
        if (playlistId.isBlank()) return
        val app = application as OlympusIptvApp
        val config = app.playlistStore.lineup(playlistId)
        val options = withContext(Dispatchers.IO) { app.catalogDb.countryChoices(playlistId) }
        val home = homeCountry
        val hidden = if (includeAll) {
            config.hiddenCountries.orEmpty().filter { it == home }
        } else if (options.isEmpty()) {
            return
        } else {
            options.map { it.id }.filter { it != home }
        }
        if ((config.includeInternational == true) == includeAll && config.hiddenCountries.orEmpty() == hidden) return
        val email = app.playlistStore.session.first()?.email
        val deviceId = app.playlistStore.deviceId()
        val remoteRevision = if (email.isNullOrBlank()) {
            0L
        } else {
            runCatching { LineupClient(app).remoteLineup(email, deviceId)?.revision ?: 0L }.getOrDefault(0L)
        }
        val next = config.copy(
            includeInternational = includeAll,
            hiddenCountries = hidden,
            revision = System.currentTimeMillis()
        )
        app.playlistStore.saveLineup(playlistId, next)
        app.stagedLineup = playlistId to next
        if (!email.isNullOrBlank()) {
            runCatching {
                LineupClient(app).push(email, deviceId, next, maxOf(config.revision ?: 0L, remoteRevision))
            }
        }
    }

    private fun addSports() {
        if (busy || !catalogReady) return
        setBusy(true)
        binding.statusText.setText(R.string.favorites_setup_adding)
        lifecycleScope.launch {
            val counts = runCatching { savePicks(AutoFavorites.sportsPicks(guideChannels())) }.getOrNull()
            if (isFinishing) return@launch
            setBusy(false)
            lastCard?.let { focusCard(it) }
            showCount(counts)
        }
    }

    private fun markPicked(card: View) {
        val group = card as? android.view.ViewGroup ?: return
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child.tag == "picked") child.visibility = View.VISIBLE
        }
    }

    private fun showSports() {
        binding.setupScroll.visibility = View.GONE
        binding.cardBoard.visibility = View.GONE
        binding.sportsPanel.visibility = View.VISIBLE
        if (currentSport.isEmpty()) binding.statusText.setText(R.string.favorites_setup_pick_sport)
        binding.sportRow.getChildAt(0)?.let { focusCard(it) }
    }

    private fun selectSport(sport: AutoFavorites.Sport, chip: TextView) {
        if (busy) return
        currentSport = sport.id
        for (index in 0 until binding.sportRow.childCount) {
            binding.sportRow.getChildAt(index).isSelected = binding.sportRow.getChildAt(index) === chip
        }
        binding.teamList.removeAllViews()
        binding.statusText.setText(R.string.favorites_setup_adding)
        lifecycleScope.launch {
            val teams = runCatching { AutoFavorites.teamsFor(sport.id, guideChannels()) }.getOrDefault(emptyList())
            if (isFinishing || currentSport != sport.id) return@launch
            binding.teamList.removeAllViews()
            if (teams.isEmpty()) {
                binding.statusText.setText(R.string.favorites_setup_no_teams)
                return@launch
            }
            val selected = chosen.getOrPut(sport.id) { mutableSetOf() }
            teams.forEach { team -> binding.teamList.addView(teamRow(team.name, selected)) }
            binding.statusText.setText(R.string.favorites_setup_pick_teams)
        }
    }

    private fun addTeams() {
        if (busy || currentSport.isEmpty()) {
            binding.statusText.setText(R.string.favorites_setup_pick_sport)
            return
        }
        val names = chosen[currentSport].orEmpty()
        if (names.isEmpty()) {
            binding.statusText.setText(R.string.favorites_setup_pick_teams)
            return
        }
        setBusy(true)
        binding.statusText.setText(R.string.favorites_setup_adding)
        lifecycleScope.launch {
            val counts = runCatching {
                savePicks(AutoFavorites.picksForTeams(currentSport, guideChannels(), names))
            }.getOrNull()
            if (isFinishing) return@launch
            setBusy(false)
            showCount(counts)
        }
    }

    private fun showCount(counts: Pair<Int, Int>?) {
        val added = counts?.first ?: 0
        val already = counts?.second ?: 0
        binding.statusText.text = when {
            added > 0 -> getString(R.string.favorites_setup_added, added)
            already > 0 -> getString(R.string.favorites_setup_already)
            else -> getString(R.string.favorites_setup_none)
        }
    }

    private suspend fun guideChannels(): List<AutoFavorites.GuideChannel> {
        channels?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            (application as OlympusIptvApp).catalogDb
                .channelsInGroups(playlistId, AutoFavorites.groups)
                .map { AutoFavorites.GuideChannel(it.id, it.name, it.group) }
        }
        if (loaded.isNotEmpty()) channels = loaded
        return loaded
    }

    private suspend fun loadSavedHome() {
        val saved = (application as OlympusIptvApp).playlistStore.session.first() ?: return
        val country = saved.homeCountry?.trim()?.lowercase().orEmpty()
        if (country.isNotEmpty()) homeCountry = country
        val lat = saved.homeLat
        val lon = saved.homeLon
        if (lat != null && lon != null) {
            savedHome = Place(saved.homeZip.orEmpty(), lat, lon, homeCountry)
        }
    }

    private suspend fun resolvePlace(guide: List<AutoFavorites.GuideChannel>, wantLocals: Boolean): Place? {
        savedHome?.let { return it }
        val geo = withContext(Dispatchers.IO) { locate() }
        if (geo != null && geo.country.equals("US", ignoreCase = true)) {
            if (!wantLocals || AutoFavorites.localStations(guide, geo.lat, geo.lon).picks.isNotEmpty()) return geo
        }
        return promptZip()
    }

    private suspend fun promptZip(): Place? {
        while (!isFinishing) {
            val zip = askZipCode() ?: return null
            binding.statusText.setText(R.string.favorites_setup_finding)
            val place = withContext(Dispatchers.IO) { lookupZip(zip) }
            if (place != null) {
                homeCountry = place.country.lowercase()
                savedHome = Place(zip, place.lat, place.lon, homeCountry)
                val app = application as OlympusIptvApp
                val saved = app.playlistStore.session.first()
                if (saved != null) {
                    app.playlistStore.saveSession(
                        saved.copy(
                            homeZip = zip,
                            homeCountry = homeCountry,
                            homeLat = place.lat,
                            homeLon = place.lon
                        )
                    )
                }
                val international = app.playlistStore.lineup(playlistId).includeInternational == true
                if (!international) applyCountryFilter(false)
                return savedHome
            }
            binding.statusText.setText(R.string.favorites_setup_zip_invalid)
        }
        return null
    }

    private suspend fun askZipCode(): String? = suspendCancellableCoroutine { cont ->
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            hint = getString(R.string.favorites_setup_zip_hint)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.favorites_setup_zip_title)
            .setMessage(R.string.favorites_setup_zip_message)
            .setView(input)
            .setPositiveButton(R.string.favorites_setup_zip_find, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> if (cont.isActive) cont.resume(null) }
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val zip = input.text?.toString()?.filter { it.isDigit() }?.take(5).orEmpty()
                if (zip.length == 5 && cont.isActive) {
                    dialog.dismiss()
                    cont.resume(zip)
                } else {
                    input.error = getString(R.string.favorites_setup_zip_invalid)
                }
            }
        }
        dialog.setOnCancelListener { if (cont.isActive) cont.resume(null) }
        cont.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }

    private suspend fun savePicks(picks: List<AutoFavorites.ChannelPick>): Pair<Int, Int> {
        val fresh = picks.distinctBy { it.id }
        if (fresh.isEmpty()) return 0 to 0
        val app = application as OlympusIptvApp
        val base = app.playlistStore.favoritesRevision(playlistId)
        val current = app.playlistStore.favorites(playlistId).toMutableList()
        var added = 0
        var already = 0
        for (pick in fresh) {
            val key = "live:${pick.id}"
            if (current.contains(key)) already += 1 else {
                current.add(key)
                added += 1
            }
        }
        val email = app.playlistStore.session.first()?.email
        if (added > 0) {
            val revision = app.playlistStore.replaceFavorites(playlistId, current)
            if (!email.isNullOrBlank()) {
                LineupClient(app).pushFavorites(email, app.playlistStore.deviceId(), current, revision, base)
            }
        }
        val labeled = fresh.filter { it.label.isNotBlank() }
        if (labeled.isNotEmpty()) {
            val config = app.playlistStore.lineup(playlistId)
            val names = config.channelNames.orEmpty().toMutableMap()
            var changed = false
            for (pick in labeled) {
                if (names[pick.id] == pick.label) continue
                names[pick.id] = pick.label
                changed = true
            }
            if (changed) {
                val next = config.copy(channelNames = names, revision = System.currentTimeMillis())
                app.playlistStore.saveLineup(playlistId, next)
                app.stagedLineup = playlistId to next
                if (!email.isNullOrBlank()) {
                    LineupClient(app).push(email, app.playlistStore.deviceId(), next, config.revision ?: 0L)
                }
            }
        }
        return added to already
    }

    private data class Place(val city: String, val lat: Double, val lon: Double, val country: String)

    private fun locate(): Place? {
        val request = Request.Builder().url("https://ipwho.is/").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string().orEmpty())
                if (!json.optBoolean("success")) return null
                val lat = json.optDouble("latitude", Double.NaN)
                val lon = json.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return null
                Place(json.optString("city"), lat, lon, json.optString("country_code"))
            }
        }.getOrNull()
    }

    private fun lookupZip(zip: String): Place? {
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
                Place(place.optString("place name"), lat, lon, "US")
            }
        }.getOrNull()
    }

    private fun sportChip(sport: AutoFavorites.Sport): TextView {
        return TextView(this).apply {
            text = sport.label
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundResource(R.drawable.bg_focusable)
            isFocusable = true
            isFocusableInTouchMode = !touchPrimary
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { selectSport(sport, this) }
        }
    }

    private fun teamRow(name: String, selected: MutableSet<String>): TextView {
        return TextView(this).apply {
            text = teamLabel(name, name in selected)
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundResource(R.drawable.bg_focusable)
            isFocusable = true
            isFocusableInTouchMode = !touchPrimary
            isClickable = true
            isSelected = name in selected
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            setOnClickListener {
                if (!selected.add(name)) selected.remove(name)
                isSelected = name in selected
                text = teamLabel(name, isSelected)
            }
        }
    }

    private fun teamLabel(name: String, selected: Boolean): String {
        return if (selected) "✓   $name" else "      $name"
    }

    private fun setBusy(value: Boolean) {
        busy = value
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_CATALOG_READY = "catalog_ready"
        const val EXTRA_FROM_GUIDE = "from_guide"
    }
}
