package com.nexthci.ringfitness

/** Evidence for one fresh, idle preflight; never substitutes for a successful START/STOP. */
data class ChargingRecoveryEvidence(
    val statusErrorReason: Int,
    val batteryChargeStatus: Int,
    val batteryReceivedAtMs: Long,
    val statusReceivedAtMs: Long,
    val checkedAtMs: Long,
    val statusConnectionGeneration: Long,
    val batteryConnectionGeneration: Long,
) {
    fun validate(status: HealthMessage.Status, observedAtMs: Long) {
        require(ChargingStartCompatibility.isCandidate(status, statusErrorReason))
        require(batteryChargeStatus == 0 && statusConnectionGeneration > 0 &&
            batteryConnectionGeneration == statusConnectionGeneration)
        require(statusReceivedAtMs == observedAtMs && statusReceivedAtMs > 0 && batteryReceivedAtMs > 0 && checkedAtMs > 0)
        require(checkedAtMs - statusReceivedAtMs in 0..ChargingStartCompatibility.FRESHNESS_MS &&
            checkedAtMs - batteryReceivedAtMs in 0..ChargingStartCompatibility.FRESHNESS_MS)
    }
}

object ChargingStartCompatibility {
    const val FRESHNESS_MS = 5_000L

    fun isCandidate(status: HealthMessage.Status, reason: Int?) =
        !status.collecting && status.errorCode == -16 && reason == 1

    fun evidence(status: SensorPacket.Health, battery: SensorPacket.Battery, generation: Long,
        batteryRequestedAtMs: Long, nowMs: Long): ChargingRecoveryEvidence? {
        val value = status.message as? HealthMessage.Status ?: return null
        if (!isCandidate(value, status.statusErrorReason) || battery.chargeStatus != 0 ||
            battery.receivedEpochMs < batteryRequestedAtMs) return null
        val evidence = ChargingRecoveryEvidence(1, 0, battery.receivedEpochMs, status.receivedEpochMs,
            nowMs, generation, generation)
        return evidence.takeIf { runCatching { it.validate(value, status.receivedEpochMs) }.isSuccess }
    }
}
