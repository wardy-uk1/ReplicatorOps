package dev.vantara.replicatorops

import dev.vantara.replicatorops.ui.lcars.screenScale
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenScaleTest {
    @Test fun normalPhone() = assertEquals(1f, screenScale(411, 891), 0.001f)
    @Test fun phoneLandscapeStaysTheSame() = assertEquals(1f, screenScale(891, 411), 0.001f)
    @Test fun smallPhoneShrinks() = assertEquals(0.947f, screenScale(360, 640), 0.01f)
    @Test fun tinyPhoneFloor() = assertEquals(0.85f, screenScale(300, 500), 0.001f)
    @Test fun sevenInchTablet() = assertEquals(1.3f, screenScale(600, 960), 0.01f)
    @Test fun bigTabletCap() = assertEquals(1.5f, screenScale(1280, 800), 0.001f)
}
