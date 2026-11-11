package com.straphanger.app.data.local

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** IDs for rides that are not derived from their data: a prefix naming the source, then a random UUID. */
object RideIds {
    @OptIn(ExperimentalUuidApi::class)
    fun newId(prefix: String): String = "${prefix}_${Uuid.random()}"
}
