package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Test

class SeafileSessionTransportTest {
    @Test fun requestIdentityTracksTheInstalledAppVersion() {
        assertEquals("RingFitnessSteps/${BuildConfig.VERSION_NAME}", SeafileSessionTransport.userAgent())
    }

    @Test fun attemptDeadlineScalesWithBytesWithoutSettingACaptureDurationLimit() {
        assertEquals(120_000L, SeafileSessionTransport.uploadDeadlineMillis(0))
        assertEquals(121_000L, SeafileSessionTransport.uploadDeadlineMillis(16_384))
        assertEquals(122_000L, SeafileSessionTransport.uploadDeadlineMillis(16_385))
        assertTrue(SeafileSessionTransport.uploadDeadlineMillis(1024L * 1024 * 1024) >
            SeafileSessionTransport.uploadDeadlineMillis(1024L * 1024))
        assertTrue(SeafileSessionTransport.uploadDeadlineMillis(Long.MAX_VALUE) > 0)
        assertThrows(IllegalArgumentException::class.java) { SeafileSessionTransport.uploadDeadlineMillis(-1) }
    }

    @Test fun responseCodesSeparatePermanentDestinationFailuresFromRetryableOutages() {
        listOf(300, 301, 302, 307, 308, 400, 401, 403, 404, 409, 410).forEach {
            assertTrue(SeafileSessionTransport.responseFailure("test", it) is PermanentUploadException)
        }
        listOf(408, 429, 500, 503).forEach {
            assertTrue(SeafileSessionTransport.responseFailure("test", it) is RetryableUploadException)
        }
    }

    @Test fun onlyTheExplicitHttpsUploadLinkIsAccepted() {
        assertEquals("cloud.tsinghua.edu.cn", SeafileSessionTransport.validateLink("https://cloud.tsinghua.edu.cn/u/d/fixture/").host)
        listOf("http://cloud.tsinghua.edu.cn/u/d/fixture/", "https://example.org/u/d/fixture/",
            "https://cloud.tsinghua.edu.cn:444/u/d/fixture/", "https://user@cloud.tsinghua.edu.cn/u/d/fixture/",
            "https://cloud.tsinghua.edu.cn/u/d/fixture/?secret=x", "https://cloud.tsinghua.edu.cn/u/d/fixture/#fragment",
            "https://cloud.tsinghua.edu.cn/u/d/fixture/extra").forEach { link ->
            assertThrows(IllegalArgumentException::class.java) { SeafileSessionTransport.validateLink(link) }
        }
    }

    @Test fun onlyLegacyOrActivityQualifiedSessionArchiveNamesAreAccepted() {
        val id = "11111111-1111-4111-8111-111111111111"
        listOf(
            "ringfitness-session-$id.zip",
            "ringfitness-session-walking-$id.zip",
            "ringfitness-session-running-$id.zip",
        ).forEach { assertEquals(it, SeafileSessionTransport.validateArchiveName(it)) }
        listOf(
            "session-$id.zip",
            "ringfitness-session-cycling-$id.zip",
            "ringfitness-session-walking-$id.zip.part",
            "ringfitness-session-walking-../../$id.zip",
        ).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                SeafileSessionTransport.validateArchiveName(name)
            }
        }
    }

    @Test fun successRequiresASingleReceiptWithMatchingLengthAndAFileId() {
        val name = "ringfitness-session-walking-11111111-1111-4111-8111-111111111111.zip"
        val good = """[{"name":"$name","id":"${"a".repeat(40)}","size":120}]"""
        assertEquals(120L, SeafileSessionTransport.parseReceipt(good, name, 120).bytes)
        assertThrows(IllegalArgumentException::class.java) { SeafileSessionTransport.parseReceipt(good, name, 121) }
        assertThrows(IllegalArgumentException::class.java) {
            SeafileSessionTransport.parseReceipt(good, name.replace("walking", "running"), 120)
        }
        for (bad in listOf(good.replace("120", "true"), good.replace("120", "120.0"),
            good.replace(name, "../$name"), good.replace("a".repeat(40), ""), "[]")) {
            assertThrows(RuntimeException::class.java) { SeafileSessionTransport.parseReceipt(bad, name, 120) }
        }
    }
}
