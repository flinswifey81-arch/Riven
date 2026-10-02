package com.shai.riven.data.persistence

import android.content.Context
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide lease owner for the canonical database.
 *
 * Foreground conversation runtime and WorkManager workers must share both the Room instance and
 * its canonical-recall commit fence. A lease prevents either owner from closing the database while
 * the other is still using it. Restore/reset run before runtime acquisition and continue to use
 * unshared database instances explicitly.
 */
class RivenDatabaseLease internal constructor(
    val database: RivenDatabase,
    private val release: (RivenDatabase) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release(database)
    }
}

object RivenDatabaseProvider {
    private data class Entry(
        val database: RivenDatabase,
        var leaseCount: Int,
    )

    private var entry: Entry? = null

    @Synchronized
    fun acquire(context: Context): RivenDatabaseLease {
        val current = entry ?: Entry(
            database = RivenDatabase.build(context.applicationContext),
            leaseCount = 0,
        ).also { entry = it }
        current.leaseCount += 1
        return RivenDatabaseLease(current.database, ::release)
    }

    @Synchronized
    private fun release(database: RivenDatabase) {
        val current = entry ?: return
        if (current.database !== database) return
        check(current.leaseCount > 0) { "Canonical database lease underflow" }
        current.leaseCount -= 1
        if (current.leaseCount == 0) {
            entry = null
            current.database.close()
        }
    }
}
