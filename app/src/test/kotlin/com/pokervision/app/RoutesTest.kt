package com.pokervision.app

import com.pokervision.app.navigation.Routes
import com.pokervision.feature.home.HomeEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {

    @Test
    fun `every home entry maps to a distinct route`() {
        val routes = HomeEntry.entries.map(Routes::forHomeEntry)
        assertEquals(HomeEntry.entries.size, routes.toSet().size)
    }

    @Test
    fun `detail routes embed the id`() {
        assertEquals("images/42", Routes.imageDetail(42))
        assertEquals("videos/7", Routes.videoDetail(7))
        assertEquals("images/{mediaId}", Routes.IMAGE_DETAIL)
    }
}
