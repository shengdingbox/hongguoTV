// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingSnapshotTest {
    @Test fun roundTripKeepsRatingAndCount() {
        val cached = CachedRating("123", 9.1, "37445", 1_700_000_000_000L)
        assertEquals(cached, RatingSnapshot.decode(RatingSnapshot.encode(cached)))
    }

    @Test fun rejectsInvalidValuesAndDamagedSnapshots() {
        assertFalse(RatingSnapshot.validId("0"))
        assertFalse(RatingSnapshot.validId("abc"))
        assertFalse(RatingSnapshot.validRating(0.0))
        assertFalse(RatingSnapshot.validRating(10.1))
        assertFalse(RatingSnapshot.validRating(Double.NaN))
        assertTrue(RatingSnapshot.validRating(8.6))
        assertNull(RatingSnapshot.decode("not-json"))
        assertNull(RatingSnapshot.decode("""{"version":1,"seriesId":"0","rating":9.0,"savedAt":1}"""))
    }
}
