package com.olympus.iptv.playlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore

/**
 * Bounds concurrent TMDB / Watchmode / ratings HTTP so Coil logos and live playback
 * keep priority on the shared OkHttp pool (budget STBs).
 */
object MetaLookupGate {
    private val semaphore = Semaphore(3)

    fun <T> withPermit(block: () -> T): T {
        semaphore.acquire()
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }

    suspend fun <T> withPermitAsync(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            semaphore.acquire()
            try {
                block()
            } finally {
                semaphore.release()
            }
        }
}
