package com.local.eldermetro

import android.content.*
import androidx.test.platform.app.InstrumentationRegistry
import com.local.eldermetro.domain.*
import com.local.eldermetro.taxi.*
import org.junit.Test
import org.junit.Assert.*

class NavigationLinkTest {
    private val place=Place(null,"测试入口 & A","","测试市","",GeoPoint(31.2,121.4))
    @Test fun nativeWalkingUsesDestinationAndCorrectCoordinates() {
        val uri=walkingNavigationUri(place)
        assertEquals("31.2",uri.getQueryParameter("lat")); assertEquals("121.4",uri.getQueryParameter("lon")); assertEquals("0",uri.getQueryParameter("dev"))
        assertEquals("OnFootNavi",uri.getQueryParameter("featureName"))
        val route=nativeRouteUri(place); assertEquals(place.name,route.getQueryParameter("dname")); assertNull(route.getQueryParameter("slat"))
    }
    @Test fun absentAppFallsBackToWebWithoutSubmittingOrder() {
        val launches=mutableListOf<Intent>()
        val context=object:ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent:Intent) { launches+=intent; if(intent.`package` != null) throw ActivityNotFoundException() }
        }
        openWalkingNavigation(context,place)
        assertEquals("com.autonavi.minimap",launches[0].`package`)
        assertEquals("https",launches[1].data?.scheme); assertEquals("walk",launches[1].data?.getQueryParameter("mode"))
        assertEquals(place.point.api()+","+place.name,launches[1].data?.getQueryParameter("to"))
    }
}
