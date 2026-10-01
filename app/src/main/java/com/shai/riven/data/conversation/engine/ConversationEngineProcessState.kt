package com.shai.riven.data.conversation.engine

import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

internal object ConversationEngineOwnerRegistry {
    private val liveTokens = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun register(token: String) {
        check(liveTokens.add(token)) { "Conversation engine owner token is already registered" }
    }

    fun unregister(token: String) {
        liveTokens.remove(token)
    }

    fun isLive(token: String): Boolean = token in liveTokens

    fun newToken(): String = UUID.randomUUID().toString()
}

class ConversationRunLimiter(
    maximumConcurrentRuns: Int,
) {
    private val semaphore = Semaphore(maximumConcurrentRuns)

    suspend fun <T> withPermitOrNull(
        timeoutMillis: Long,
        block: suspend () -> T,
    ): T? {
        val acquired = withTimeoutOrNull(timeoutMillis) {
            semaphore.acquire()
            true
        } ?: false
        if (!acquired) return null
        return try {
            block()
        } finally {
            semaphore.release()
        }
    }
}

internal object ConversationRunLimiterPool {
    private val limiters = ConcurrentHashMap<Int, ConversationRunLimiter>()

    fun forMaximum(maximumConcurrentRuns: Int): ConversationRunLimiter =
        limiters.computeIfAbsent(maximumConcurrentRuns, ::ConversationRunLimiter)
}
