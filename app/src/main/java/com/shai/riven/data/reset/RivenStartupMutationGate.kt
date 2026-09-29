package com.shai.riven.data.reset

import android.content.Context
import com.shai.riven.data.archive.RivenRestoreGate
import com.shai.riven.data.archive.RivenRestorePaths
import java.io.File

object RivenStartupMutationGate {
    fun isPending(context: Context): Boolean =
        RivenResetGate.isPending(context) || RivenRestoreGate.isPending(context)

    internal fun isPending(
        context: Context,
        resetRoot: File,
        restoreRoot: File,
    ): Boolean =
        RivenResetGate.isPending(context, resetRoot) ||
            RivenRestoreGate.isPending(context, restoreRoot)

    internal fun defaultRestoreRoot(context: Context): File = File(
        context.applicationContext.noBackupFilesDir,
        RivenRestorePaths.RESTORE_DIRECTORY,
    )
}
