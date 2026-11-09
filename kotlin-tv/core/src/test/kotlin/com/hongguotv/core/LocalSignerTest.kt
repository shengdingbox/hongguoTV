// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本地签名算法测试。
 *
 * 向量取自 openhongguo `internal/protocol/sign/sign_test.go`（GPL-3.0），
 * 其中 Gorgon / Helios / Medusa 的期望值是参考实现在真机上校验过的输出。
 * 这些测试保证本仓库的 Kotlin 移植与参考实现逐位一致。
 */
class LocalSignerTest {

    @Test fun sm3MatchesPublishedVector() {
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            sm3("abc".toByteArray()).hex(),
        )
    }

    @Test fun aesSBoxMatchesFips197() {
        // FIPS-197 附录 B 的前若干个 S 盒值
        val box = AesSBox.bytes
        assertEquals(0x63, box[0x00].u())
        assertEquals(0x7c, box[0x01].u())
        assertEquals(0x77, box[0x02].u())
        assertEquals(0x7b, box[0x03].u())
        assertEquals(0xf2, box[0x04].u())
        assertEquals(0x6b, box[0x05].u())
        assertEquals(0x6f, box[0x06].u())
        assertEquals(0xc5, box[0x07].u())
        assertEquals(0x30, box[0x08].u())
        assertEquals(0x01, box[0x09].u())
        assertEquals(256, box.size)
    }

    @Test fun gorgonMatchesReference() {
        val query = REFERENCE_QUERY
        assertEquals(
            "84043412400154ed198c3230d5ff1364e71df6105a46126993c0",
            xGorgon(query, """{"a":1}""".toByteArray(), true, 1_700_000_000, 0x1234),
        )
    }

    @Test fun heliosMatchesReference() {
        assertEquals(
            "k0TjeBWFFnRXfkeKlh3Hmx1jLjrfhgCasqsF4XvB1+0eB1Mw",
            assembleHelios(1_700_000_000, 2_028_160_147),
        )
    }

    @Test fun medusaKeyHashAndSeedMatchReference() {
        val (hash, seed) = medusaKeyHashForTest(2_378_451_149.toInt())
        assertEquals("1b35023431b99a949ab7b70fc50a30216076492a6229d102a7a33fa3a1104170", hash.hex())
        assertEquals("b0dff9ff", seed.hex())
    }

    @Test fun xmxorMatchesReference() {
        val (hash, _) = medusaKeyHashForTest(2_378_451_149.toInt())
        assertEquals(
            "5c69c37694106e8cbdb2e074619a1dda6c5a5a65",
            xmxorForTest("0123456789abcdefghij".toByteArray(), hash).hex(),
        )
    }

    @Test fun medusaMatchesReference() {
        val entropy = SignEntropy(
            envLaunch = 103L,
            envPid = 11691L,
            nowSecs = 1_700_000_000L,
            messageRandom = 1_273_073_252,
            random = 2_378_451_149.toInt(),
            packedRandom = 2_253_366_658.toInt(),
            gorgonRandom = 0,
            heliosRandom = 0,
        )
        val value = assembleMedusa(
            "https://api5-normal-sinfonlineb.fqnovel.com/x/v1?aid=8662&device_id=7100000000000011&ts=1700000000",
            """{"a":1}""".toByteArray(),
            true,
            1_700_000_000,
            "7100000000000011",
            "7.1.3.32",
            entropy,
        )
        assertEquals(REFERENCE_MEDUSA, value)
    }

    private companion object {
        const val REFERENCE_QUERY =
            "iid=7100000000000012&device_id=7100000000000011&ac=wifi&channel=update_64" +
                "&aid=8662&app_name=novelread&version_code=71332&version_name=7.1.3.32" +
                "&device_platform=android&os=android&ssmix=a&device_type=25053RT47C" +
                "&device_brand=Redmi&language=zh&os_api=36&os_version=16" +
                "&manifest_version_code=71332&resolution=1280*2772&dpi=520" +
                "&update_version_code=71332&host_abi=arm64-v8a&dragon_device_type=phone" +
                "&pv_player=71332&compliance_status=0&need_personal_recommend=1" +
                "&player_so_load=1&is_android_pad_screen=0" +
                "&ts=1700000000&_rticket=1700000000123"

        const val REFERENCE_MEDUSA =
            "A/FTZfcZDJ/XJo9e1tubFVc+Mn3NSAABthXCoU+OAdYPONbwm9+RyUZ1cbOs05Yl1aeIPCOGtW+GK2b4gMoFqK69oa1CtJNpHvFQ90KOznW75N3Iac3SIC2SIL/AEUa1LqRKHJc0OVAOzCUSF1PpwjNvKQMxteguS4MtQqRWQiRv25xZVCxPuR4K155Yu2XGsfoblNT6sUx3M9mDqh8WhMpoRbPjSwAij42sVoYMhYUMcyYL73j0agwgsSu/bMNgpLXppp9Hd7KwpiRPFCyDMGP892DPSagFV+ndwVa0M9GGxB3ksAgAUUr91XV5uEUA6/MJpv7AUUEpFfk101bKFHLmflVjL4B9K4OEos6OgkIx2gloIAOULcEBroYDODhlyRqqKT7TX53G09xaFF/YpqtuOC6ZkfG6MOBtYCrCeqgLtoubTaE/xh0VOVRVS/mQWvXYhS00Tqcb/ywQaQzDm1eBAxc1SgDLnzwzLAPLrmDI4rDLqaAWcL6yhsqXydzBShsdZOwreQE9pHzhm3Kaw1ZdV/FLHMqIKnuGoGOOAv19acvxPext43+ckznl5fV5pIj1txHIxcgg8/fQMx0xYM9ubLBnL3lZr+x7226u1kUngjM/IFDSCsVcs3QRzyjAGo+2SWLa8Sicyiq1QsQB5Xw0KWMNlA7lA8dRmxrs2ANytt9GeELT6TZqAWwwoCRbae6aXm+j2qgybmCrmffHBE3gZWgQ8pTjpWWRedesWrwwfioLmyWqRmZRl9TgaWiJpFQZwLFz9WnDhbk8/rFeQjtPH+/my02pcdEXl2Onwwg7/iS5nhWwmWn/k378VIdufE0rFeIAKs9npH8SwAuxaT0GEiWv0AZuXppB6clslZlunKcJHHZK2g/UtbSWEodVLcYPhnFt7cZe46GeFxdSUxNb26BpkjTtwLQGqVFFlFqGb5ijjxz6BVEBpXbZVpLW0+OKFDlfKj166UTiQdk4GfWp28mwvGYFiVqlBOew//nfsP/4n8SN"
    }
}
