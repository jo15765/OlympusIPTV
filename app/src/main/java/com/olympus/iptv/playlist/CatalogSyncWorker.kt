package com.olympus.iptv.playlist

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.ui.DeviceForm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CatalogSyncWorker : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as? OlympusIptvApp ?: return false
        if (DeviceForm.isTelevision(app) || MeteredNetwork.limited(app)) {
            jobFinished(params, false)
            return false
        }
        app.appScope.launch {
            val saved = runCatching { app.playlistStore.session.first() }.getOrNull()
            if (saved != null) {
                runCatching { CatalogIngest.start(app, saved).await() }
                if (!MeteredNetwork.limited(app)) app.warmFresh(saved.id)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 2706
        private const val PERIOD_MS = 6L * 60L * 60L * 1000L

        fun schedule(context: Context) {
            if (DeviceForm.isTelevision(context)) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, CatalogSyncWorker::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .setPeriodic(PERIOD_MS)
                .build()
            scheduler.schedule(info)
        }
    }
}
