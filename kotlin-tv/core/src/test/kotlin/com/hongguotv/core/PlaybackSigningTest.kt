package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放签名的上游基准测试向量（Apache-2.0）。 */
class PlaybackSigningTest {
    @Test fun ladonMatchesGoldenVectors() {
        assertEquals(
            "AQIDBKM7Y7bWnOIUXmTybD6/MpGbuge4sBwUJsyHVivhtMsH",
            PlaybackSigning.ladonForTest(1_700_000_000, 0x04030201, "1967"),
        )
        assertEquals(
            "AQIDBPhgjvKhYEWp2lodHDSBnITF8APHUjBLe2ja98bNrRn+",
            PlaybackSigning.ladonForTest(1_800_000_000, 0x04030201, "8662"),
        )
    }

    @Test fun argusMatchesAstonGoldenVectors() {
        assertEquals(
            "8oGwZRLe2V38m+1gfqIeLa2MoV9ZBWosW5Y0NXKHAB2aEYRF86eNA7rLWm6xUCiwYMBDI/yq5ajsCmDF+Zws9KOfZO0mqezSYjF9/D5C1EliRxXJBBuZBaQZJgT6gakwiRnJkm+8F1ZVFIFO9GxkdwLxdxYidY2sLQjAsG8q4241ayJ/0zOxsVJNlqzPs7PfJb83zY6J1eIUaugTB6QnbXOKEQUgLhiYC0Uszr1vBP2Vj1cgtWu0IgouGM/ie3CohEAkY2oOkN/W1leWheBszijv",
            PlaybackSigning.argusForTest(
                "aid=1967&device_id=test-device&iid=test-install",
                null, 1_700_000_000, 659662259, "1967", "test-device", "test-install",
            ),
        )
        assertEquals(
            "8oGzD48Lx0I+DCXDwk+l2r54SBRLSexXn2DlWQdGZE1UFZXj1GWup1ztpbF3eGeKcJAItaddhQwgYm7odM2byEYHtJ0pNvNUAoumWtQPqducerNIGe0Hs3z5V7oJDVgEySh6Kqc69jI8T+zU13SblzavSSZGSegC030jul66JPvjsBGzZoerKy88GDYDbumRNPCtqnfztLNNlGwmnA+0Tv+dnBC1xJ9rIEBThybfhq8wL5YqxxstLwuLGffOIlbxoQdheycfZBDpJG5kiK4eX7b3gr+PVkmCZBkJleZIYDl22UQS7nQGJKyu/a0U2MC7oWo=",
            PlaybackSigning.argusForTest(
                "aid=8662&device_id=7000000000000000001&iid=7000000000000000002&version_name=7.1.3.32&_rticket=1800000000999&ts=1800000000",
                """{"video_id":"123","biz_param":{"video_platform":3}}""".toByteArray(),
                1_800_000_000, -1, "8662", "7000000000000000001", "7000000000000000002",
            ),
        )
    }

    @Test fun argusMatchesZfdangGoldenVectorWithIndependentInstallId() {
        assertEquals(
            "8oHyrVYEy1xDCDlx64ko40qC6JC9VYY/ZkjJzEl50iSFDw8Yfph1hupLxfKrA9GswSEz0Yi4goA7OQ4u+H8ECdOaQDL4GduQ3LULSq9Q4iSEGbS3L6G24GXChtnmPr6n46M2PLlKpXvF7uE0lhH4yD+X69FpdM41gQwx5Y5U3NdwXFaxlqYFszyp7dlUE6BEBWqwDsiAH8Jk9TGKr/jfHt4fVmMc9zRyNRdGeV0x6aP//x7aV+8Po8a/9KK3UNG5KEpmIgzRT6NUttkBP9gRNUh3",
            PlaybackSigning.argusForTest(
                "aid=1967&device_id=dev_aaaaaaaa&iid=111",
                null, 1_700_000_000, 659662259, "1967", "dev_aaaaaaaa", "",
            ),
        )
    }

    @Test fun playbackHeadersBindBodyAndUseRealEnvelope() {
        val query = "aid=8662&device_id=7000000000000000001&iid=7000000000000000002&version_name=7.3.9.32&ts=1700000000"
        val body = """{"video_id":"7543196048185887806"}""".toByteArray()
        val headers = PlaybackSigning.headers(query, body, 1_700_000_000, "7000000000000000001", "7000000000000000002", versionName = "7.1.3.32")
        assertTrue(headers.getValue("x-argus").startsWith("8o"))
        assertTrue(headers.getValue("x-argus").length > 64)
        assertTrue(headers.getValue("x-ladon").isNotBlank())
        assertFalse(headers.containsKey("x-medusa"))
    }
}
