package com.nexthci.ringfitness

/** Optional signal owner; all calls and callbacks are serialized by the collection service. */
interface SessionHeartRatePort {
    val state: PolarUiState
    fun resetSelection()
    fun search()
    fun connect(deviceId: String)
    /** The selected identity is already durable in session before any ring START is sent. */
    fun prepare(session: FreeLivingSession): Boolean
    fun restore(session: FreeLivingSession)
    /** Seals the CSV and metadata before local data completion or the first package freeze. */
    fun finish(session: FreeLivingSession)
    fun discard(session: FreeLivingSession)
    /** Checkpoints an interrupted stream without marking the session complete. */
    fun close()
}
