package com.example.esphomecontroller

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MdiIconsTest {
    private val sample = Mdi.parse(sequenceOf(
        "ceiling-light\tF0769\t0\tlight-ceiling\tHome Automation|Lighting",
        "lightbulb\tF0335\t0\tbulb idea\tHome Automation",
        "sofa\tF04B9\t0\tcouch\tHome Automation",
        "lightbulb-old\tF0001\t1\t\t",
        "broken line",
        "bad\tZZZZ\t0\t\t",
    ))

    /** The bundled index, read straight from the module's assets (unit tests run in the app module directory). */
    private val bundled by lazy { Mdi.parse(File("src/main/assets/mdi/icons.tsv").readLines().asSequence()) }

    @Test fun parsesLinesAndSkipsMalformedOnes() {
        assertEquals(listOf("ceiling-light", "lightbulb", "sofa", "lightbulb-old"), sample.map { it.name })
        val light = sample.first()
        assertEquals(0xF0769, light.codepoint); assertEquals(listOf("light-ceiling"), light.aliases)
        assertEquals(listOf("Home Automation", "Lighting"), light.tags); assertEquals("Ceiling light", light.label)
        assertEquals(String(Character.toChars(0xF0769)), light.glyph)
        assertTrue(sample.last().deprecated)
    }

    @Test fun indexPrefersNamesOverAliases() {
        val index = Mdi.index(sample + MdiIcon("couch", 0xF0002))
        assertEquals("couch", index["couch"]?.name)
        assertEquals("ceiling-light", index["light-ceiling"]?.name)
    }

    @Test fun searchRanksNamesFirstAndHidesDeprecatedUnlessNamed() {
        assertEquals(listOf("lightbulb", "lightbulb-old", "ceiling-light"), Mdi.search("light", sample).map { it.name })
        assertEquals(listOf("sofa"), Mdi.search("couch", sample).map { it.name })
        assertEquals(listOf("ceiling-light"), Mdi.search("Ceiling Light", sample).map { it.name })
        assertEquals(listOf("ceiling-light"), Mdi.search("mdi:ceiling-light", sample).map { it.name })
        assertTrue(Mdi.search("old", sample).isEmpty())
        assertEquals(3, Mdi.search(" ", sample).size)
    }

    @Test fun bundledIndexHasTheFontAndEverySuggestedAndMigratedIcon() {
        assertTrue(bundled.size > 7000)
        assertTrue(File("src/main/res/font/mdi.ttf").length() > 1_000_000)
        val byName = Mdi.index(bundled)
        val needed = SuggestedSectionIcons.flatMap { it.second } + DashboardCodec.LEGACY_ICON_KEYS.values +
            listOf("home", "sofa", "bed", "stairs", "garage", "door", "blinds", "speaker", "wifi", "water", "lock", "bell", "star", "car")
        needed.forEach { name -> assertTrue("$name missing or deprecated", byName[name]?.deprecated == false) }
        assertEquals(bundled.size, bundled.map { it.name }.toSet().size)
    }

    @Test fun savedIconsMigrateFromOldKeysAndKeepTheMdiPrefix() {
        fun decode(icon: String) = DashboardCodec.decode(JSONObject("""{"id":"d","name":"D","sections":[{"id":"s","type":"TILES","icon":"$icon"}]}""")).sections.single().icon
        assertEquals("fridge", decode("kitchen"))
        assertEquals("sofa", decode("sofa"))
        assertEquals("power", decode("mdi:power"))
        assertEquals("power-plug", decode("power"))
        val d = Dashboard("d", "D", listOf(Section("s", SectionType.TILES, icon = "ceiling-light")))
        assertEquals("mdi:ceiling-light", DashboardCodec.encode(d).getJSONArray("sections").getJSONObject(0).getString("icon"))
    }
}
