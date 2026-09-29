package com.shai.riven

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.shai.riven.data.archive.RivenRestoreBootstrap
import com.shai.riven.data.archive.RivenRestoreBootstrapResult
import com.shai.riven.data.background.RivenBackgroundBootstrapResult
import com.shai.riven.data.background.RivenBackgroundWorkBootstrap
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.background.WorkManagerRivenBackgroundWorkScheduler

class RivenApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        settleRestoreThenBootstrap(
            restore = { RivenRestoreBootstrap(this).recoverAndApply() },
            scheduler = WorkManagerRivenBackgroundWorkScheduler(WorkManager.getInstance(this)),
        )
    }

    internal fun settleRestoreThenBootstrap(
        restore: () -> RivenRestoreBootstrapResult,
        scheduler: RivenBackgroundWorkScheduler,
    ): RivenApplicationStartupResult {
        val restoreResult = restore()
        val background = if (restoreResult is RivenRestoreBootstrapResult.Failure) {
            null
        } else {
            bootstrapBackgroundWork(scheduler)
        }
        return RivenApplicationStartupResult(restoreResult, background)
    }

    internal fun bootstrapBackgroundWork(
        scheduler: RivenBackgroundWorkScheduler,
    ): RivenBackgroundBootstrapResult = RivenBackgroundWorkBootstrap.schedule(scheduler)
}

internal data class RivenApplicationStartupResult(
    val restore: RivenRestoreBootstrapResult,
    val background: RivenBackgroundBootstrapResult?,
)
