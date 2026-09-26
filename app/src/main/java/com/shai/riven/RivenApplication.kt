package com.shai.riven

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.shai.riven.data.background.RivenBackgroundBootstrapResult
import com.shai.riven.data.background.RivenBackgroundWorkBootstrap
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.background.WorkManagerRivenBackgroundWorkScheduler

class RivenApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        bootstrapBackgroundWork(
            WorkManagerRivenBackgroundWorkScheduler(WorkManager.getInstance(this)),
        )
    }

    internal fun bootstrapBackgroundWork(
        scheduler: RivenBackgroundWorkScheduler,
    ): RivenBackgroundBootstrapResult = RivenBackgroundWorkBootstrap.schedule(scheduler)
}
