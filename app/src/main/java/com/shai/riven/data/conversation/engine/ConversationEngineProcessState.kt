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

class ConversationRunLimiter internal constructor(
    maximumConcurrentRuns: Int,
    private val afterPermitAcquired: suspend () -> Unit,
) {
    constructor(maximumConcurrentRuns: Int) : this(maximumConcurrentRuns, {})

    private val semaphore = Semaphore(maximumConcurrentRuns)

    suspend fun <T> withPermitOrNull(
        timeoutMillis: Long,
        block: suspend () -> T,
    ): T? {
        var permitOwned = false
        return try {
            val acquired = withTimeoutOrNull(timeoutMillis) {
                semaphore.acquire()
                permitOwned = true
                afterPermitAcquired()
                true
            } ?: false
            if (!acquired) return null
            block()
        } finally {
            if (permitOwned) semaphore.release()
        }
    }
}

internal object ConversationRunLimiterPool {
    private val limiters = ConcurrentHashMap<Int, ConversationRunLimiter>()

    fun forMaximum(maximumConcurrentRuns: Int): ConversationRunLimiter =
        limiters.computeIfAbsent(maximumConcurrentRuns, ::ConversationRunLimiter)
}
