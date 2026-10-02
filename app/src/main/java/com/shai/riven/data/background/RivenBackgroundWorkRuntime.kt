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
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.provider.ProviderProfileService
import com.shai.riven.data.provider.ProviderRuntimeProfileResolver
import com.shai.riven.data.provider.openrouter.HttpUrlConnectionOpenRouterHttpClient
import java.io.Closeable

class RivenBackgroundWorkRuntime private constructor(
    private val database: RivenDatabase,
    val executor: RivenBackgroundWorkExecutor,
) : Closeable {
    override fun close() {
        database.close()
    }

    companion object {
        fun create(applicationContext: Context): RivenBackgroundWorkRuntime {
            val context = applicationContext.applicationContext
            val database = RivenDatabase.build(context)
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
                // Real repair behavior is intentionally absent until a future feature supplies handlers.
                val repairRegistry = RepairJobHandlerRegistry(emptyList())
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
                    ),
                )
                val automaticMemorySweep = AutomaticMemorySweepService(automaticMemoryQueue)
                return RivenBackgroundWorkRuntime(
                    database = database,
                    executor = RivenBackgroundWorkExecutor(
                        attachmentMaintenance = attachmentMaintenance,
                        repairJobRunner = repairRunner,
                        repairSweep = repairSweep,
                        automaticMemoryJobRunner = automaticMemoryJobRunner,
                        automaticMemorySweep = automaticMemorySweep,
                    ),
                )
            } catch (failure: Exception) {
                database.close()
                throw failure
            }
        }
    }
}
