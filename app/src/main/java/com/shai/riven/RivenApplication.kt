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
import com.shai.riven.data.reset.FactoryResetBootstrapResult
import com.shai.riven.data.reset.RivenResetBootstrap

class RivenApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        settleStartupMutationsThenBootstrap(
            reset = { RivenResetBootstrap(this).recoverAndApply() },
            restore = { RivenRestoreBootstrap(this).recoverAndApply() },
            scheduler = WorkManagerRivenBackgroundWorkScheduler(WorkManager.getInstance(this)),
        )
    }

    internal fun settleRestoreThenBootstrap(
        restore: () -> RivenRestoreBootstrapResult,
        scheduler: RivenBackgroundWorkScheduler,
    ): RivenApplicationStartupResult = settleStartupMutationsThenBootstrap(
        reset = { FactoryResetBootstrapResult.NoPendingReset },
        restore = restore,
        scheduler = scheduler,
    )

    internal fun settleStartupMutationsThenBootstrap(
        reset: () -> FactoryResetBootstrapResult,
        restore: () -> RivenRestoreBootstrapResult,
        scheduler: RivenBackgroundWorkScheduler,
    ): RivenApplicationStartupResult {
        val resetResult = reset()
        if (resetResult is FactoryResetBootstrapResult.Failure) {
            return RivenApplicationStartupResult(
                reset = resetResult,
                restore = null,
                background = null,
            )
        }
        val restoreResult = restore()
        val background = if (restoreResult is RivenRestoreBootstrapResult.Failure) {
            null
        } else {
            bootstrapBackgroundWork(scheduler)
        }
        return RivenApplicationStartupResult(resetResult, restoreResult, background)
    }

    internal fun bootstrapBackgroundWork(
        scheduler: RivenBackgroundWorkScheduler,
    ): RivenBackgroundBootstrapResult = RivenBackgroundWorkBootstrap.schedule(scheduler)
}

internal data class RivenApplicationStartupResult(
    val reset: FactoryResetBootstrapResult,
    val restore: RivenRestoreBootstrapResult?,
    val background: RivenBackgroundBootstrapResult?,
)
