package org.fossify.phone.helpers

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

object ContactsSyncJob {
    const val PERIODIC_ID = 1101
    const val ONCE_ID = 1102
    private const val TAG = "PhonlyPhone"

    fun ensure(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.allPendingJobs.none { it.id == PERIODIC_ID }) {
            val periodic = JobInfo.Builder(
                PERIODIC_ID,
                ComponentName(context, ContactsSyncJobService::class.java),
            )
                .setPersisted(true)
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .build()
            val result = scheduler.schedule(periodic)
            Log.i(TAG, "schedule periodic contacts sync result=$result")
        }
        val once = JobInfo.Builder(
            ONCE_ID,
            ComponentName(context, ContactsSyncJobService::class.java),
        )
            .setMinimumLatency(1_000L)
            .setOverrideDeadline(8_000L)
            .setPersisted(true)
            .build()
        val onceResult = scheduler.schedule(once)
        Log.i(TAG, "schedule once contacts sync result=$onceResult")
    }
}

class ContactsSyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        ContactsSyncScheduler.request(this) {
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
