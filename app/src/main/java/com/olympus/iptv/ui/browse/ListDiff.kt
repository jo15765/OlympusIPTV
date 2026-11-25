package com.olympus.iptv.ui.browse

import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.DiffUtil
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal object ListDiff {
    private val main = Handler(Looper.getMainLooper())

    fun <T> dispatch(
        generation: AtomicInteger,
        token: Int,
        old: List<T>,
        next: List<T>,
        sameItem: (T, T) -> Boolean,
        sameContent: (T, T) -> Boolean,
        payload: (T, T) -> Any? = { _, _ -> null },
        apply: (DiffUtil.DiffResult, List<T>) -> Unit
    ) {
        thread(name = "list-diff", isDaemon = true) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    sameItem(old[oldItemPosition], next[newItemPosition])
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    sameContent(old[oldItemPosition], next[newItemPosition])
                override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? =
                    payload(old[oldItemPosition], next[newItemPosition])
            }, true)
            main.post {
                if (generation.get() != token) return@post
                apply(diff, next)
            }
        }
    }
}
