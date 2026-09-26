package com.shai.riven.data.context

interface RivenContextSource {
    val descriptor: RivenContextSourceDescriptor

    suspend fun read(request: RivenContextReadRequest): RivenContextSourceResult
}
