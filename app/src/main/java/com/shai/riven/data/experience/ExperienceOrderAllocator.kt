package com.shai.riven.data.experience

import com.shai.riven.data.persistence.dao.MemoryDao

/** Shared caller-transaction-owned allocator for the canonical Experience event sequence. */
internal class ExperienceOrderAllocator(
    private val memoryDao: MemoryDao,
) {
    fun next(): Long {
        val maximum = memoryDao.maximumEventOrder() ?: return FIRST_EVENT_ORDER
        if (maximum == Long.MAX_VALUE) throw ExperienceOrderOverflowException
        return maximum + 1L
    }

    private companion object {
        const val FIRST_EVENT_ORDER = 1L
    }
}

internal data object ExperienceOrderOverflowException : RuntimeException()
