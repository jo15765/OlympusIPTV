package com.olympus.iptv.ui.browse

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.olympus.iptv.R
import com.olympus.iptv.playlist.TitleRatingsBundle
import com.olympus.iptv.ui.DeviceForm
import com.olympus.iptv.ui.Haptics

object VodDetailSheet {
    fun show(
        context: Context,
        bundle: TitleRatingsBundle,
        onTrailer: (() -> Unit)? = null,
        onPlay: (() -> Unit)? = null
    ) {
        if (DeviceForm.isTouchPrimary(context)) {
            showTouch(context, bundle, onTrailer, onPlay)
        } else {
            showDialog(context, bundle, onTrailer, onPlay)
        }
    }

    fun showLoading(context: Context) {
        Toast.makeText(context, R.string.vod_ratings_loading, Toast.LENGTH_SHORT).show()
    }

    private fun showDialog(
        context: Context,
        bundle: TitleRatingsBundle,
        onTrailer: (() -> Unit)?,
        onPlay: (() -> Unit)?
    ) {
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val column = buildColumn(context, bundle, density)
        val builder = AlertDialog.Builder(context).setView(ScrollView(context).apply {
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(column)
        })
        if (onPlay != null) {
            builder.setPositiveButton(R.string.play) { _, _ -> onPlay() }
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        if (onTrailer != null) {
            builder.setNeutralButton(R.string.trailer) { _, _ -> onTrailer() }
        }
        builder.show()
    }

    private fun showTouch(
        context: Context,
        bundle: TitleRatingsBundle,
        onTrailer: (() -> Unit)?,
        onPlay: (() -> Unit)?
    ) {
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val sheet = BottomSheetDialog(context)
        val column = buildColumn(context, bundle, density)
        fun button(label: Int, block: () -> Unit) {
            column.addView(TextView(context).apply {
                setText(label)
                setTextColor(context.getColor(R.color.text_primary))
                textSize = 16f
                minHeight = (48 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_nav)
                setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = (10 * density).toInt()
                layoutParams = params
                setOnClickListener {
                    Haptics.tick(this)
                    sheet.dismiss()
                    block()
                }
            })
        }
        if (onTrailer != null) button(R.string.trailer, onTrailer)
        if (onPlay != null) button(R.string.play, onPlay)
        sheet.setContentView(ScrollView(context).apply {
            setPadding(pad, (16 * density).toInt(), pad, (24 * density).toInt())
            addView(column)
        })
        sheet.show()
    }

    private fun buildColumn(
        context: Context,
        bundle: TitleRatingsBundle,
        density: Float
    ): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.bg_elevated))
        }
        fun line(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) {
            column.addView(TextView(context).apply {
                this.text = text
                setTextColor(context.getColor(color))
                textSize = size
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                if (top > 0) setPadding(0, top, 0, 0)
            })
        }
        line(bundle.title, 20f, R.color.text_primary, bold = true)
        if (bundle.ratings.isNotEmpty()) {
            line(
                bundle.ratings.joinToString("   ") { "${it.source} ${it.scoreLabel}" },
                15f,
                R.color.accent,
                top = (10 * density).toInt()
            )
            val detail = bundle.ratings
                .mapNotNull { r -> r.detail.takeIf { it.isNotBlank() }?.let { "${r.source}: $it" } }
                .joinToString(" · ")
            if (detail.isNotBlank()) line(detail, 12f, R.color.text_secondary)
        } else {
            line(context.getString(R.string.vod_ratings_none), 14f, R.color.text_secondary, top = (10 * density).toInt())
        }
        if (bundle.overview.isNotBlank()) {
            line(context.getString(R.string.vod_overview), 14f, R.color.text_primary, bold = true, top = (14 * density).toInt())
            line(bundle.overview, 14f, R.color.text_secondary)
        }
        if (bundle.reviews.isNotEmpty()) {
            line(context.getString(R.string.vod_reviews), 14f, R.color.text_primary, bold = true, top = (14 * density).toInt())
            for (review in bundle.reviews) {
                line("${review.author} · ${review.source}", 12f, R.color.accent, top = (8 * density).toInt())
                line(review.content, 13f, R.color.text_secondary)
            }
        }
        return column
    }
}
