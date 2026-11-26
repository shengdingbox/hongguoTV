// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class ReservationMatchTest {
    private val trailer = Series("100", "全球死考：刚下刑场你让我去考试三季",
        "https://p3.example.com/poster.jpg~small?token=old", release = ReleaseSchedule())
    private fun library(vararg rows: Series) = LibraryIndex(
        LibraryData(rows.toList(), emptyList(), emptySet(), emptyList(), LibrarySettings()))
    private fun published(id: String = "200") = trailer.copy(id = id, release = null)
    private fun detail(series: Series = published()) = Detail(series, listOf("901", "902"))

    @Test fun `规范标点和季号但不串到其他季`() {
        assertTrue(ReservationMatch.matches(trailer, published().copy(title = "全球死考:刚下刑场你让我去考试3季")))
        assertFalse(ReservationMatch.matches(trailer, published().copy(title = "全球死考：刚下刑场你让我去考试四季")))
    }

    @Test fun `三季与第三季的不同版本不能只凭剧名合并`() {
        val other = published().copy(title = "全球死考：刚下刑场你让我去考试第三季", cover = "https://example.com/other.jpg")
        assertFalse(ReservationMatch.matches(trailer, other))
        assertTrue(ReservationMatch.matches(trailer, other.copy(cover = "https://p6.example.com/poster.jpg~large?token=new")))
    }

    @Test fun `多个同名条目歧义时不关联并优先匹配原海报`() {
        val a = published("200").copy(cover = "https://example.com/a.jpg")
        val b = published("300").copy(cover = "https://example.com/b.jpg")
        assertNull(ReservationMatch.select(trailer, listOf(a, b)))
        assertEquals("200", ReservationMatch.select(trailer, listOf(a.copy(cover = trailer.cover), b))?.id)
    }

    @Test fun `后台关联正式ID后仍保留预约与分组并能恢复`() {
        val state = library(trailer)
        state.setGroup(trailer.id, "追更")
        assertTrue(state.observeFavoriteDetail(detail(), 10, originalId = trailer.id))
        assertFalse(state.favorite(trailer.id))
        assertTrue(state.reserved("200"))
        assertTrue(state.availableFavorites().isEmpty())
        assertNull(state.reservedFavorites().single().release)
        assertEquals("追更", state.groupOf("200"))
        val restored = LibraryDisk.decode(mapOf(
            "favorites" to LibraryDisk.series(state.favorites()), "reservations" to state.reservations(),
            "favoriteGroups" to LibraryDisk.groups(state.groupsSnapshot()), "favoriteUpdates" to LibraryDisk.updates(state.updates()),
        ))
        assertTrue(restored.reserved("200"))
        assertTrue(restored.observeFavoriteDetail(detail(), 20, acknowledge = true))
        assertFalse(restored.reserved("200"))
        assertEquals("200", restored.availableFavorites().single().id)
    }

    @Test fun `从搜索打开正式作品也能确认原预约且不产生重复收藏`() {
        val state = library(trailer, published())
        assertTrue(state.observeFavoriteDetail(detail(), 10, acknowledge = true))
        assertEquals(listOf("200"), state.favorites().map { it.id })
        assertTrue(state.reservedFavorites().isEmpty())
    }

    @Test fun `错误季号与未取得分集的候选不能替换预约`() {
        val state = library(trailer)
        assertFalse(state.observeFavoriteDetail(detail(published().copy(title = "全球死考：刚下刑场你让我去考试四季")), 10, originalId = trailer.id))
        assertFalse(state.observeFavoriteDetail(Detail(published(), emptyList()), 10, originalId = trailer.id))
        assertTrue(state.reserved(trailer.id))
        assertFalse(state.favorite("200"))
    }
}
