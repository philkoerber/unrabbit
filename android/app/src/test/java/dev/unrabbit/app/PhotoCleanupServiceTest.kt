package dev.unrabbit.app

import android.app.job.JobScheduler
import android.content.ComponentName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoCleanupServiceTest {
    @Test fun maintenanceIsPeriodicPersistedOfflineAndNotDuplicatedOnLaunch() {
        val context = RuntimeEnvironment.getApplication()
        repeat(2) { PhotoCleanupService.schedule(context) }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val jobs = scheduler.allPendingJobs.filter { it.id == PhotoCleanupService.JOB_ID }
        val job = jobs.single()
        assertEquals(ComponentName(context, PhotoCleanupService::class.java), job.service)
        assertTrue(job.isPeriodic)
        assertTrue(job.isPersisted)
        assertEquals(60 * 60 * 1000L, job.intervalMillis)
        assertNull(job.requiredNetwork)
    }
}
