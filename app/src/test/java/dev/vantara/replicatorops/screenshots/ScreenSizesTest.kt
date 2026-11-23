package dev.vantara.replicatorops.screenshots

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.vantara.replicatorops.App
import dev.vantara.replicatorops.data.BedMesh
import dev.vantara.replicatorops.data.BoxSlot
import dev.vantara.replicatorops.data.BoxState
import dev.vantara.replicatorops.data.Connection
import dev.vantara.replicatorops.data.Fan
import dev.vantara.replicatorops.data.GcodeFile
import dev.vantara.replicatorops.data.Heater
import dev.vantara.replicatorops.data.Prefs
import dev.vantara.replicatorops.data.PrinterState
import dev.vantara.replicatorops.data.ShipSystems
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.Screen
import dev.vantara.replicatorops.ui.lcars.LcarsTheme
import dev.vantara.replicatorops.ui.lcars.ScaledToScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

/**
 * Renders the main screens on a range of screen sizes and saves PNGs to
 * build/outputs/roborazzi. Run: ./gradlew recordRoborazziDebug -Pscreenshots
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenSizesTest {

    @get:Rule val compose = createComposeRule()

    private val screens = listOf(Screen.STATUS, Screen.CONTROL, Screen.SYSTEMS, Screen.FILES, Screen.COMMANDS, Screen.CONFIG)

    private fun sample(): PrinterState = PrinterState(
        connection = Connection.CONNECTED,
        klippy = "ready",
        printState = "printing",
        filename = "Mood_Ghost.gcode",
        progress = 0.42,
        printDuration = 2400.0,
        totalDuration = 2500.0,
        currentLayer = 57,
        totalLayers = 140,
        heaters = listOf(
            Heater("extruder", "extruder", "Nozzle", 219.6, 220.0),
            Heater("heater_bed", "heater_bed", "Bed", 59.8, 60.0),
            Heater("heater_generic chamber", "chamber", "Chamber", 34.1, 0.0),
        ),
        partFan = 1.0,
        fans = listOf(Fan("fan_generic cooling_fan", "Part", 1.0), Fan("fan_generic auxiliary_fan", "Aux", 0.3)),
        homedAxes = "xyz",
        position = listOf(120.0, 98.0, 11.4),
        box = BoxState(
            slots = listOf(
                BoxSlot(0, true, "PLA", 0xFFFF362DL),
                BoxSlot(1, true, "PLA Silk", 0xFFFFD23FL),
                BoxSlot(2, true, "PETG", 0xFF3A86FFL),
                BoxSlot(3, false, null, null),
            ),
            temperature = 24.3, humidity = 30.0, target = 0.0, activeSlot = 0,
        ),
        systems = ShipSystems(caseLight = 1.0, noodleDetection = true, noodleSensitivity = "medium", prePrintCheck = false),
        bedMesh = BedMesh(
            "default", 5.0 to 5.0, 265.0 to 265.0,
            List(7) { r -> List(7) { c -> 0.08 * sin(r * 0.9) - 0.05 * (c - 3) / 3.0 } },
        ),
        macros = listOf("CLEAN_NOZZLE", "LOAD_FILAMENT", "PURGE_LINE", "UNLOAD_FILAMENT"),
    )

    private fun render(device: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Prefs(app).apply {
            lastSeenVersion = dev.vantara.replicatorops.BuildConfig.VERSION_NAME
            askedNotifications = true
            redAlert = false
            host = "192.168.1.50"
        }
        val vm = AppViewModel(app)
        vm.client.disconnect()
        vm.client.showForTest(sample())
        vm.showFilesForTest(
            listOf("Mood_Ghost.gcode", "Benchy.gcode", "Pilbox_set.gcode.3mf", "Organizer.gcode.3mf", "Gargoyles.gcode.3mf", "Bracket.gcode")
                .mapIndexed { i, n -> GcodeFile(n, 1_763_800_000.0 - i * 86_400, 1_200_000L + i * 50_000) }
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val style by vm.style.collectAsState()
            LcarsTheme(style) { ScaledToScreen { App(vm) } }
        }
        for (screen in screens) {
            compose.runOnIdle { vm.select(screen) }
            compose.mainClock.advanceTimeBy(500)
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/${device}_${screen.name.lowercase()}.png")
        }
    }

    @Test @Config(qualifiers = "w320dp-h568dp-hdpi") fun smallPhone() = render("1_small_phone_320")
    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi") fun compactPhone() = render("2_phone_360")
    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi") fun phone() = render("3_phone_411")
    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi") fun phoneLandscape() = render("4_phone_landscape")
    @Test @Config(qualifiers = "w673dp-h841dp-xhdpi") fun foldableOpen() = render("5_foldable_open")
    @Test @Config(qualifiers = "w600dp-h960dp-xhdpi") fun tablet7() = render("6_tablet_7in")
    @Test @Config(qualifiers = "w800dp-h1280dp-xhdpi") fun tablet10() = render("7_tablet_10in")
    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi") fun tablet10Landscape() = render("8_tablet_10in_landscape")
    @Test @Config(qualifiers = "w1100dp-h700dp-land-mdpi") fun chromebookWindow() = render("9_chromebook")
}
