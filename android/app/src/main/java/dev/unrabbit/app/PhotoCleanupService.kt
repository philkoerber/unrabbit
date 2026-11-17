package dev.unrabbit.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Local-only maintenance, also scheduled when the chat is closed and across reboots. */
class PhotoCleanupService : JobService() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    override fun onStartJob(params: JobParameters): Boolean {
        val attempt = ++generation
        io.execute {
            val failed = runCatching { PhotoStore(this).pruneExpired() }.isFailure
            main.post { if (generation == attempt) jobFinished(params, failed) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { generation++; return true }

    override fun onDestroy() {
        generation++
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        const val JOB_ID = 701
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, PhotoCleanupService::class.java))
                    .setPeriodic(60 * 60 * 1000L).setPersisted(true).build())
        }
    }
}
