package com.olympus.iptv.ui.browse

import androidx.lifecycle.lifecycleScope
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.olympus.iptv.BuildConfig
import com.olympus.iptv.R
import com.olympus.iptv.playlist.TitleRatings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun BrowseActivity.openYoutubeTrailer(name: String, preferTv: Boolean) {
    if (BuildConfig.TMDB_API_KEY.isBlank()) {
        Toast.makeText(this, R.string.tmdb_missing_key, Toast.LENGTH_LONG).show()
        return
    }
    lifecycleScope.launch {
        val trailer = withContext(Dispatchers.IO) {
            TitleRatings.trailerFor(name, preferTv)
        }
        if (isDestroyed) return@launch
        if (trailer == null) {
            Toast.makeText(screen(), R.string.trailer_missing, Toast.LENGTH_SHORT).show()
            return@launch
        }
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${trailer.key}"))
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${trailer.key}"))
        runCatching { startActivity(appIntent) }.recoverCatching { startActivity(webIntent) }
            .onFailure {
                Toast.makeText(screen(), R.string.trailer_missing, Toast.LENGTH_SHORT).show()
            }
    }
}
