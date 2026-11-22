package dev.vantara.replicatorops.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CaptainsLogTest {
    private val sample = """
        # Replicator Ops – Captain's Log
        Format: `## <version> · Stardate <yyyyDD.MM>`

        ## 0.3.2 · Stardate 202622.11
        - Captain's Log added
        - Second line

        ## 0.3.1 · Stardate 202621.11
        - IPv6
    """.trimIndent()

    @Test fun parsesEntries() {
        val log = CaptainsLog.parse(sample)
        assertEquals(listOf("0.3.2", "0.3.1"), log.map { it.version })
        assertEquals("202622.11", log[0].stardate)
        assertEquals(listOf("Captain's Log added", "Second line"), log[0].items)
    }

    @Test fun showsOnlyNewerThanLastSeen() {
        val log = CaptainsLog.parse(sample)
        assertEquals(listOf("0.3.2"), CaptainsLog.since(log, "0.3.1").map { it.version })
        assertEquals(listOf("0.3.2", "0.3.1"), CaptainsLog.since(log, "0.3.0").map { it.version })
        assertEquals(listOf("0.3.2"), CaptainsLog.since(log, "").map { it.version })
    }

    /** The bundled changelog must have an entry for the version being built. */
    @Test fun bundledLogCoversCurrentVersion() {
        val gradle = File("build.gradle.kts").readText()
        val version = Regex("""versionName = "([^"]+)"""").find(gradle)!!.groupValues[1]
        val log = CaptainsLog.parse(File("src/main/assets/CHANGELOG.md").readText())
        assertTrue("No Captain's Log entry for $version", log.any { it.version == version && it.items.isNotEmpty() })
    }
}
