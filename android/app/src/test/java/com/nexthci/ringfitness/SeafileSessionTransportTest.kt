package com.nexthci.ringfitness

import org.junit.Assert.*
import org.junit.Test

class SeafileSessionTransportTest {
    @Test fun onlyTheExplicitHttpsUploadLinkIsAccepted() {
        assertEquals("cloud.tsinghua.edu.cn", SeafileSessionTransport.validateLink("https://cloud.tsinghua.edu.cn/u/d/fixture/").host)
        listOf("http://cloud.tsinghua.edu.cn/u/d/fixture/", "https://example.org/u/d/fixture/",
            "https://cloud.tsinghua.edu.cn:444/u/d/fixture/", "https://user@cloud.tsinghua.edu.cn/u/d/fixture/",
            "https://cloud.tsinghua.edu.cn/u/d/fixture/?secret=x", "https://cloud.tsinghua.edu.cn/u/d/fixture/#fragment",
            "https://cloud.tsinghua.edu.cn/u/d/fixture/extra").forEach { link ->
            assertThrows(IllegalArgumentException::class.java) { SeafileSessionTransport.validateLink(link) }
        }
    }

    @Test fun successRequiresASingleReceiptWithMatchingLengthAndAFileId() {
        val good = """[{"name":"session.zip","id":"${"a".repeat(40)}","size":120}]"""
        assertEquals(120L, SeafileSessionTransport.parseReceipt(good, 120).bytes)
        assertThrows(IllegalArgumentException::class.java) { SeafileSessionTransport.parseReceipt(good, 121) }
        for (bad in listOf(good.replace("120", "true"), good.replace("120", "120.0"),
            good.replace("session.zip", "../session.zip"), good.replace("a".repeat(40), ""), "[]")) {
            assertThrows(RuntimeException::class.java) { SeafileSessionTransport.parseReceipt(bad, 120) }
        }
    }
}
