package com.nexthci.ringfitness

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedReader
import java.io.InputStreamReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureFileInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun writesTimestampedImuAndPpgCsvToDownloads() {
        val imu = CaptureFile.create(context, CaptureKind.IMU)
        try {
            imu.writeImu(
                SensorPacket.Imu(
                    packetSeq = 7,
                    frameCount = 2,
                    lastFrameUptimeMs = 1_000,
                    frames = listOf(
                        ImuFrame(100, 200, 300, -10, -20, -30),
                        ImuFrame(101, 201, 301, -11, -21, -31),
                    ),
                    receivedEpochMs = 1_800_000_000_000,
                ),
            )
            assertEquals(2L, imu.close())
            val text = readText(imu)
            assertTrue(text.startsWith("timestamp_iso,timestamp_unix_ms,ring_uptime_ms"))
            assertTrue(text.contains("1800000000000,1000,7,2,101,201,301,-11,-21,-31"))
        } finally {
            runCatching { imu.close() }
            context.contentResolver.delete(imu.uri, null, null)
        }

        val imu25 = CaptureFile.create(context, CaptureKind.IMU_25)
        try {
            imu25.writeImu(
                SensorPacket.Imu(
                    packetSeq = 8,
                    frameCount = 1,
                    lastFrameUptimeMs = 2_000,
                    frames = listOf(ImuFrame(111, 222, 333, -44, -55, -66)),
                    receivedEpochMs = 1_800_000_000_500,
                ),
            )
            assertEquals(1L, imu25.close())
            val text = readText(imu25)
            assertTrue(text.contains("accel_x_raw,accel_y_raw,accel_z_raw,gyro_x_raw"))
            assertTrue(text.contains("1800000000500,2000,8,1,111,222,333,-44,-55,-66"))
        } finally {
            runCatching { imu25.close() }
            context.contentResolver.delete(imu25.uri, null, null)
        }

        val imuLp25 = CaptureFile.create(context, CaptureKind.IMU_LP_25)
        try {
            imuLp25.writeImu(
                SensorPacket.Imu(
                    packetSeq = 10,
                    frameCount = 1,
                    lastFrameUptimeMs = 3_000,
                    frames = listOf(ImuFrame(444, 555, 666, 0, 0, 0)),
                    receivedEpochMs = 1_800_000_000_900,
                ),
            )
            assertEquals(1L, imuLp25.close())
            val text = readText(imuLp25)
            assertTrue(text.contains("accel_x_raw,accel_y_raw,accel_z_raw,accel_x_ms2"))
            assertTrue(!text.contains("gyro_x_raw"))
            assertTrue(text.contains("1800000000900,3000,10,1,444,555,666"))
        } finally {
            runCatching { imuLp25.close() }
            context.contentResolver.delete(imuLp25.uri, null, null)
        }

        val ppg = CaptureFile.create(context, CaptureKind.PPG_SPO2)
        try {
            ppg.writePpg(
                SensorPacket.PpgRaw(
                    packetSeq = 9,
                    mode = 1,
                    channelsMask = RingProtocol.PPG_CHANNEL_RED or RingProtocol.PPG_CHANNEL_IR,
                    lastSampleUptimeMs = 5_000,
                    green = emptyList(),
                    red = listOf(101, 102),
                    infrared = listOf(201, 202),
                    receivedEpochMs = 1_800_000_001_000,
                ),
            )
            assertEquals(2L, ppg.close())
            val text = readText(ppg)
            assertTrue(text.contains(",1,6,,101,201"))
            assertTrue(text.contains(",1,6,,102,202"))
        } finally {
            runCatching { ppg.close() }
            context.contentResolver.delete(ppg.uri, null, null)
        }
    }

    private fun readText(file: CaptureFile): String {
        val stream = context.contentResolver.openInputStream(file.uri)
            ?: error("Unable to read ${file.displayName}")
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }
}
