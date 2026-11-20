package com.olympus.iptv.ui.login

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.olympus.iptv.R
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.portal.DashboardHost

/** Step 2 of first-run setup: show the phone-pairing QR, then open favorites. */
class PairingStepActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val playlistId = intent.getStringExtra(FavoritesSetupActivity.EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            val pad = (28 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val step = TextView(this).apply {
            text = getString(R.string.onboarding_step, 2, getString(R.string.onboarding_pair))
            setTextColor(getColor(R.color.accent))
            textSize = 16f
        }
        val address = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 22f
            val top = (16 * resources.displayMetrics.density).toInt()
            setPadding(0, top, 0, top)
        }
        val qr = ImageView(this).apply {
            val size = (220 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        val next = Button(this).apply {
            text = getString(R.string.onboarding_pair_next)
            isAllCaps = false
            setTextColor(getColor(R.color.bg))
            setBackgroundResource(R.drawable.bg_button)
            isFocusable = true
            setOnClickListener {
                startActivity(
                    Intent(this@PairingStepActivity, FavoritesSetupActivity::class.java)
                        .putExtra(FavoritesSetupActivity.EXTRA_PLAYLIST_ID, playlistId)
                )
                finish()
            }
        }
        root.addView(step)
        root.addView(address)
        root.addView(qr)
        root.addView(next)
        setContentView(root)
        next.requestFocus()
        val url = LineupClient.peekDashboardUrl() ?: DashboardHost.url()
        address.text = url
        qr.setImageBitmap(QrBitmap.encode(url, 256))
    }
}
