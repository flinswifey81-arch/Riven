package com.shai.riven.data.background

import android.content.Context
import androidx.work.WorkManager
import com.shai.riven.data.attachment.AttachmentService
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.persistence.RivenDatabase
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
                return RivenBackgroundWorkRuntime(
                    database = database,
                    executor = RivenBackgroundWorkExecutor(
                        attachmentMaintenance = attachmentMaintenance,
                        repairJobRunner = repairRunner,
                        repairSweep = repairSweep,
                    ),
                )
            } catch (failure: Exception) {
                database.close()
                throw failure
            }
        }
    }
}
