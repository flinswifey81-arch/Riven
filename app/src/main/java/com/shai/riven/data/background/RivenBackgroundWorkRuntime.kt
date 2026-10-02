package com.shai.riven.data.background

import android.content.Context
import androidx.work.WorkManager
import com.shai.riven.data.automaticmemory.AutomaticMemoryJobRunner
import com.shai.riven.data.automaticmemory.AutomaticMemoryQueueService
import com.shai.riven.data.automaticmemory.AutomaticMemorySweepService
import com.shai.riven.data.automaticmemory.OpenRouterAutomaticMemoryModelFactory
import com.shai.riven.data.attachment.AttachmentService
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.credential.ProviderCredentialStore
import com.shai.riven.data.persistence.RivenDatabaseLease
import com.shai.riven.data.memory.MemoryAgingService
import com.shai.riven.data.persistence.RivenDatabaseProvider
import com.shai.riven.data.personality.LockedRivenPersonalityContextSource
import com.shai.riven.data.provider.ProviderProfileService
import com.shai.riven.data.provider.ProviderRuntimeProfileResolver
import com.shai.riven.data.provider.openrouter.HttpUrlConnectionOpenRouterHttpClient
import java.io.Closeable

class RivenBackgroundWorkRuntime private constructor(
    private val databaseLease: RivenDatabaseLease,
    val executor: RivenBackgroundWorkExecutor,
) : Closeable {
    override fun close() {
        databaseLease.close()
    }

    companion object {
        fun create(applicationContext: Context): RivenBackgroundWorkRuntime {
            val context = applicationContext.applicationContext
            val databaseLease = RivenDatabaseProvider.acquire(context)
            val database = databaseLease.database
            try {
                val scheduler = WorkManagerRivenBackgroundWorkScheduler(
                    WorkManager.getInstance(context),
                )
                val attachmentService = AttachmentService(
                    database = database,
                    blobStore = FileAttachmentBlobStore.fromContext(context),
                )
                val attachmentMaintenance = AttachmentMaintenanceService(
                    attachmentDao = database.attachmentDao(),
                    attachmentService = attachmentService,
                )
                val repairRegistry = RepairJobHandlerRegistry(
                    derivedArtifactRepairHandlers(DerivedArtifactRepairService(database)),
                )
                val repairRunner = RepairJobRunner(
                    database = database,
                    handlerRegistry = repairRegistry,
                )
                val repairSweep = RepairSweepService(
                    maintenanceDao = database.maintenanceDao(),
                    handlerRegistry = repairRegistry,
                    scheduler = scheduler,
                )
                val automaticMemoryQueue = AutomaticMemoryQueueService(database, scheduler)
                val automaticMemoryJobRunner = AutomaticMemoryJobRunner(
                    database = database,
                    modelFactory = OpenRouterAutomaticMemoryModelFactory(
                        profileResolver = ProviderRuntimeProfileResolver(
                            ProviderProfileService(database),
                            ProviderCredentialStore.fromContext(context),
                        ),
                        httpClient = HttpUrlConnectionOpenRouterHttpClient(),
                        lockedPersonalityCanon = LockedRivenPersonalityContextSource(context)::verifiedCanon,
                    ),
                )
                val automaticMemorySweep = AutomaticMemorySweepService(automaticMemoryQueue)
                val memoryLifecycleSweep = MemoryLifecycleSweepService(MemoryAgingService(database))
                return RivenBackgroundWorkRuntime(
                    databaseLease = databaseLease,
                    executor = RivenBackgroundWorkExecutor(
                        attachmentMaintenance = attachmentMaintenance,
                        repairJobRunner = repairRunner,
                        repairSweep = repairSweep,
                        automaticMemoryJobRunner = automaticMemoryJobRunner,
                        automaticMemorySweep = automaticMemorySweep,
                        memoryLifecycleSweep = memoryLifecycleSweep,
                    ),
                )
            } catch (failure: Exception) {
                databaseLease.close()
                throw failure
            }
        }
    }
}
