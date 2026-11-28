/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.RepoFiles
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherContext
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.resource.ResourceMode
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import app.morphe.patches.instagram.misc.settings.MERGED_LOGO_BAR
import app.morphe.patches.instagram.reels.cleanup.UFI_ID
import app.morphe.patches.instagram.reels.cleanup.loadsLiteral
import app.morphe.patches.instagram.reels.tab.MAIN_FEED_ACTION_BAR
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Accent color's theme half (#8): the styles added for every attribute Instagram's styles set to one
 * of its blues, and AccentTheme.apply once Instagram's Prism overrides are on an activity's theme or its
 * theme util has styled a context.
 */
class AccentThemeTest {
    /** The patch's copies of AccentColor's blues and AccentHue's colors are the extension's own. */
    @Test
    fun theTablesAreTheExtensionsOwn() {
        val misc = File(RepoFiles.root, "extensions/instagram/src/main/java/app/hushgram/extension/instagram/misc")
        val hues = Regex("""^\s+([A-Z]+)\((0x[0-9a-f]+|0), (0x[0-9a-f]+|0), (0x[0-9a-f]+|0)\)[,;]""", RegexOption.MULTILINE)
            .findAll(File(misc, "AccentHue.java").readText())
            .map { it.groupValues.drop(1) }
            .filter { it[0] != "BLUE" && it[0] != WALLPAPER && it[0] != CUSTOM }
            .map { (name, fill, light, dark) -> listOf(name, fill, light, dark) }
            .toList()
        // Custom (#520) has no colors in AccentHue. The patch gives it Dusk's as stand-ins.
        val custom = ACCENT_PALETTES.single { it.name == CUSTOM }
        assertEquals(ACCENT_PALETTES.single { it.name == "DUSK" }.shades, custom.shades)
        assertEquals(
            hues,
            ACCENT_PALETTES.filter { it.name != CUSTOM }.map { palette ->
                listOf(
                    palette.name,
                    "0x%06x".format(palette.shades.getValue(AccentRole.FILL)),
                    "0x%06x".format(palette.shades.getValue(AccentRole.LINK_ON_LIGHT)),
                    "0x%06x".format(palette.shades.getValue(AccentRole.LINK_ON_DARK)),
                )
            },
        )
        val roles = HashMap<Int, AccentRole>()
        val pending = ArrayList<Int>()
        val role = File(misc, "AccentColor.java").readText().substringAfter("static Role role(int color)").substringBefore("default:")
        for (line in role.lines().map { it.trim() }) {
            Regex("""case (0x[0-9a-f]+):""").matchEntire(line)?.let { pending += it.groupValues[1].removePrefix("0x").toInt(16) }
            Regex("""return Role\.([A-Z_]+);""").matchEntire(line)?.let { found ->
                pending.forEach { roles[it] = AccentRole.valueOf(found.groupValues[1]) }
                pending.clear()
            }
        }
        assertEquals(roles, ACCENT_BLUES)
        // PURPLE's worked out shades, as AccentHue works them out.
        val purple = ACCENT_PALETTES.first().shades
        assertEquals(0x6430d5, purple.getValue(AccentRole.PRESSED))
        assertEquals(0x351971, purple.getValue(AccentRole.DIM))
        assertEquals(0xeee7fe, purple.getValue(AccentRole.PALE))
        // Material You's system steps, in the Role order MaterialYou.java lists them.
        val steps = Regex("""android\.R\.color\.system_accent1_(\d+),""")
            .findAll(File(misc, "MaterialYou.java").readText()).map { it.groupValues[1].toInt() }.toList()
        assertEquals(AccentRole.entries.map { WALLPAPER_STEPS.getValue(it) }, steps)
        assertTrue("AccentHue lists the wallpaper", Regex("""^\s+$WALLPAPER\(0, 0, 0\)[,;]""", RegexOption.MULTILINE)
            .containsMatchIn(File(misc, "AccentHue.java").readText()))
    }

    /**
     * Material You (#187): each opaque shade gets a fallback color and the wallpaper's system color on
     * Android 12, a palette style naming them, and a badge pair; a see-through shade gets nothing.
     */
    @Test
    fun theWallpaperResourcesPointEachOpaqueShadeAtTheSystemPalette() {
        val uses = setOf(
            AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff),
            AccentUse("igds_color_link", AccentRole.LINK_ON_LIGHT, 0xff),
            AccentUse("igds_color_primary_button_indigo", AccentRole.FILL, 0xff),
            AccentUse("igds_color_selected_text_background", AccentRole.FILL, 0x4d),
        )
        val attrs = parse("<resources><attr name=\"$BADGE_ATTRIBUTE\" format=\"reference|color\" /></resources>")
        val colors = parse("<resources />")
        val colorsV31 = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)
        addWallpaperResources(uses, attrs, colors, colorsV31, styles)

        val fallback = colors.elements("color").associate { it.getAttribute("name") to it.textContent }
        val system = colorsV31.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals(
            mapOf(
                "hushgram_accent_wallpaper_fill_ff" to "@android:color/system_accent1_500",
                "hushgram_accent_wallpaper_link_on_dark_ff" to "@android:color/system_accent1_200",
                "hushgram_accent_wallpaper_link_on_light_ff" to "@android:color/system_accent1_600",
                WALLPAPER_BADGE_COLOR to "@android:color/system_accent1_500",
            ),
            system,
        )
        assertEquals("#ff7f67be", fallback["hushgram_accent_wallpaper_fill_ff"])
        assertEquals("#ffd0bcff", fallback["hushgram_accent_wallpaper_link_on_dark_ff"])
        assertTrue("no see-through wallpaper shade", fallback.keys.none { it.startsWith("hushgram_accent_wallpaper_fill_4d") })
        val added = styles.elements("style").associate { style ->
            style.getAttribute("name") to style.elements("item").map { it.getAttribute("name") to it.textContent }
        }
        assertEquals(
            listOf(
                "hushgram_accent_fill_ff" to "@color/hushgram_accent_wallpaper_fill_ff",
                "hushgram_accent_link_on_dark_ff" to "@color/hushgram_accent_wallpaper_link_on_dark_ff",
                "hushgram_accent_link_on_light_ff" to "@color/hushgram_accent_wallpaper_link_on_light_ff",
            ),
            added[WALLPAPER_PALETTE_STYLE],
        )
        assertEquals(listOf(BADGE_ATTRIBUTE to "@color/$WALLPAPER_BADGE_COLOR"), added[WALLPAPER_BADGE_STYLE])

        val again = assertThrows(PatchException::class.java) {
            addWallpaperResources(uses, attrs, parse("<resources />"), colorsV31, parse("<resources />"))
        }
        assertTrue(again.message, again.message!!.contains("already have a hushgram_accent_wallpaper_fill_ff"))
    }

    /** A blue named through a color, a color naming another, or written out counts, with its alpha. Nothing else does. */
    @Test
    fun usesAreTheAttributesSetToABlue() {
        val colors = colorValues(listOf(
            parse("""
                <resources>
                    <color name="indigo_0600">#ff85a1ff</color>
                    <color name="indigo_1000">#ff4a5df9</color>
                    <color name="faded">#4d0095f6</color>
                    <color name="alias">@color/indigo_1000</color>
                    <color name="gray">#ff737373</color>
                </resources>
            """),
            parse("""<resources><color name="indigo_0600">#ff3143e3</color></resources>"""),
        ))
        val uses = accentUses(listOf(parse("""
            <resources>
                <style name="Dark">
                    <item name="igds_color_link">@color/indigo_0600</item>
                    <item name="igds_color_primary_button_indigo">@color/alias</item>
                    <item name="igds_color_selected_text_background">@color/faded</item>
                    <item name="android:textColorLink">#ff708dff</item>
                    <item name="igds_color_secondary_text">@color/gray</item>
                    <item name="igds_color_other">?attr/igds_color_link</item>
                    <item name="android:layout_width">match_parent</item>
                </style>
            </resources>
        """)), colors)
        assertEquals(
            setOf(
                AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff),
                AccentUse("igds_color_link", AccentRole.LINK_ON_LIGHT, 0xff),
                AccentUse("igds_color_primary_button_indigo", AccentRole.FILL, 0xff),
                AccentUse("igds_color_selected_text_background", AccentRole.FILL, 0x4d),
                AccentUse("android:textColorLink", AccentRole.LINK_ON_DARK, 0xff),
            ),
            uses,
        )
        assertEquals("HushGramAccent_igds_color_link_link_on_dark_ff", AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff).style)
        assertEquals("HushGramAccent_android_textColorLink_fill_4d", AccentUse("android:textColorLink", AccentRole.FILL, 0x4d).style)
        assertEquals(0x80112233.toInt(), parseColor("#80112233"))
        assertEquals(0xff112233.toInt(), parseColor("#112233"))
        assertEquals(0xffaabbcc.toInt(), parseColor("#abc"))
        assertEquals(null, parseColor("@color/x"))
    }

    /** A shade attribute per part and alpha, each color's values and style, a style per use, and the list answered. */
    @Test
    fun theResourcesAddedPointEachUseAtItsShade() {
        val uses = setOf(
            AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff),
            AccentUse("igds_color_link", AccentRole.LINK_ON_LIGHT, 0xff),
            AccentUse("igds_color_selected_text_background", AccentRole.FILL, 0x4d),
        )
        val attrs = parse("<resources><attr name=\"igds_color_link\" format=\"reference|color\" /></resources>")
        val colors = parse("<resources />")
        val styles = parse("<resources><style name=\"Theme.Instagram\" /></resources>")

        val listed = addAccentResources(uses, attrs, colors, styles)

        assertEquals(
            listOf("igds_color_link", "hushgram_accent_fill_4d", "hushgram_accent_link_on_dark_ff", "hushgram_accent_link_on_light_ff"),
            attrs.elements("attr").map { it.getAttribute("name") },
        )
        assertEquals(setOf("color"), attrs.elements("attr").drop(1).map { it.getAttribute("format") }.toSet())
        val values = colors.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals(ACCENT_PALETTES.size * 3, values.size)
        assertEquals("#4d7638fa", values["hushgram_accent_purple_fill_4d"])
        assertEquals("#ffa07cfd", values["hushgram_accent_purple_link_on_dark_ff"])
        assertEquals("#ff5806e0", values["hushgram_accent_purple_link_on_light_ff"])
        assertEquals("#ff00727c", values["hushgram_accent_teal_link_on_light_ff"])
        val added = styles.elements("style").drop(1).associate { style ->
            style.getAttribute("name") to style.elements("item").map { it.getAttribute("name") to it.textContent }
        }
        assertEquals(
            listOf(
                "hushgram_accent_fill_4d" to "@color/hushgram_accent_green_fill_4d",
                "hushgram_accent_link_on_dark_ff" to "@color/hushgram_accent_green_link_on_dark_ff",
                "hushgram_accent_link_on_light_ff" to "@color/hushgram_accent_green_link_on_light_ff",
            ),
            added["HushGramAccentPalette_GREEN"],
        )
        assertEquals(
            listOf("igds_color_link" to "?attr/hushgram_accent_link_on_dark_ff"),
            added["HushGramAccent_igds_color_link_link_on_dark_ff"],
        )
        assertEquals(
            listOf("igds_color_selected_text_background" to "?attr/hushgram_accent_fill_4d"),
            added["HushGramAccent_igds_color_selected_text_background_fill_4d"],
        )
        assertEquals(ACCENT_PALETTES.size + uses.size, added.size)
        assertEquals("igds_color_link igds_color_selected_text_background", listed)

        val again = assertThrows(PatchException::class.java) { addAccentResources(uses, attrs, parse("<resources />"), parse("<resources />")) }
        assertTrue(again.message, again.message!!.contains("already have a hushgram_accent_fill_4d"))
        assertTrue("no badge styles without the badge attribute", added.keys.none { it.startsWith("HushGramAccentBadge_") })
    }

    /** With Instagram's badge attribute declared, each color gets a badge color at its fill and a style pointing the attribute at it (#315). */
    @Test
    fun theBadgeStylesPointTheBadgeAttributeAtEachFill() {
        val uses = setOf(AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff))
        val attrs = parse("<resources><attr name=\"$BADGE_ATTRIBUTE\" format=\"reference|color\" /></resources>")
        val colors = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)

        val values = colors.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("#ff7638fa", values["hushgram_accent_purple_badge"])
        assertEquals("#ff00838f", values["hushgram_accent_teal_badge"])
        assertEquals(ACCENT_PALETTES.size * 2, values.size)
        val badges = styles.elements("style").filter { it.getAttribute("name").startsWith("HushGramAccentBadge_") }
            .associate { style -> style.getAttribute("name") to style.elements("item").map { it.getAttribute("name") to it.textContent } }
        assertEquals(ACCENT_PALETTES.map { badgeStyle(it) }, badges.keys.toList())
        assertEquals(listOf(BADGE_ATTRIBUTE to "@color/hushgram_accent_orange_badge"), badges["HushGramAccentBadge_ORANGE"])

        val taken = parse("<resources><color name=\"hushgram_accent_red_badge\">#ff000000</color></resources>")
        val fresh = parse("<resources><attr name=\"$BADGE_ATTRIBUTE\" format=\"reference|color\" /></resources>")
        val again = assertThrows(PatchException::class.java) { addAccentResources(uses, fresh, taken, parse("<resources />")) }
        assertTrue(again.message, again.message!!.contains("already have a hushgram_accent_red_badge"))
    }

    /** With Instagram's icon attribute declared, each color gets a light and a dark icon color at its link shades and a style for each (#8). */
    @Test
    fun theIconStylesPointTheIconAttributeAtEachLinkShade() {
        val uses = setOf(AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff))
        val attrs = parse("<resources><attr name=\"$ICON_ATTRIBUTE\" format=\"reference|color\" /></resources>")
        val colors = parse("<resources />")
        val colorsV31 = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)
        addWallpaperResources(uses, attrs, colors, colorsV31, styles)

        val values = colors.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("#ffa07cfd", values["hushgram_accent_purple_icon_dark"])
        assertEquals("#ff5806e0", values["hushgram_accent_purple_icon_light"])
        assertEquals("#ff00727c", values[iconColor("TEAL", IconTheme.LIGHT)])
        assertEquals("#ffd0bcff", values[iconColor(WALLPAPER, IconTheme.DARK)])
        val system = colorsV31.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("@android:color/system_accent1_200", system[iconColor(WALLPAPER, IconTheme.DARK)])
        assertEquals("@android:color/system_accent1_600", system[iconColor(WALLPAPER, IconTheme.LIGHT)])
        val icons = styles.elements("style").filter { it.getAttribute("name").startsWith("HushGramAccentIcons_") }
            .associate { style -> style.getAttribute("name") to style.elements("item").map { it.getAttribute("name") to it.textContent } }
        assertEquals(
            (ACCENT_PALETTES.map { it.name } + WALLPAPER).flatMap { palette -> IconTheme.entries.map { iconStyle(palette, it) } },
            icons.keys.toList(),
        )
        assertEquals(listOf(ICON_ATTRIBUTE to "@color/hushgram_accent_orange_icon_dark"), icons["HushGramAccentIcons_ORANGE_dark"])
        assertEquals(listOf(ICON_ATTRIBUTE to "@color/hushgram_accent_wallpaper_icon_light"), icons["HushGramAccentIcons_WALLPAPER_light"])

        val taken = parse("<resources><color name=\"hushgram_accent_red_icon_light\">#ff000000</color></resources>")
        val fresh = parse("<resources><attr name=\"$ICON_ATTRIBUTE\" format=\"reference|color\" /></resources>")
        val again = assertThrows(PatchException::class.java) { addAccentResources(uses, fresh, taken, parse("<resources />")) }
        assertTrue(again.message, again.message!!.contains("already have a hushgram_accent_red_icon_light"))
    }

    /** With Instagram's gradient attributes declared, each color gets its ring shades and a style pointing the gradient at them, light to deep (#187). */
    @Test
    fun theRingStylesPointTheGradientAtEachColor() {
        val uses = setOf(AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff))
        val gradient = listOf(
            "igds_color_gradient_yellow", "igds_color_gradient_orange", "igds_color_gradient_red",
            "igds_color_gradient_pink", "igds_color_gradient_purple", "igds_color_gradient_lavender",
        )
        val attrs = declaring(gradient)
        val colors = parse("<resources />")
        val colorsV31 = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)
        addWallpaperResources(uses, attrs, colors, colorsV31, styles)

        val values = colors.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("#ffa07cfd", values["hushgram_accent_purple_ring_link_on_dark"])
        assertEquals("#ff7638fa", values["hushgram_accent_purple_ring_fill"])
        assertEquals("#ff6430d5", values["hushgram_accent_purple_ring_pressed"])
        val rings = styleItems(styles, "HushGramAccentRings_")
        assertEquals((ACCENT_PALETTES.map { it.name } + WALLPAPER).map { ringStyle(it) }, rings.keys.toList())
        assertEquals(
            listOf(
                "igds_color_gradient_yellow" to "@color/hushgram_accent_purple_ring_link_on_dark",
                "igds_color_gradient_orange" to "@color/hushgram_accent_purple_ring_link_on_dark",
                "igds_color_gradient_red" to "@color/hushgram_accent_purple_ring_fill",
                "igds_color_gradient_pink" to "@color/hushgram_accent_purple_ring_fill",
                "igds_color_gradient_purple" to "@color/hushgram_accent_purple_ring_pressed",
                "igds_color_gradient_lavender" to "@color/hushgram_accent_purple_ring_pressed",
            ),
            rings["HushGramAccentRings_PURPLE"],
        )
        val system = colorsV31.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("@android:color/system_accent1_500", system[ringColor(WALLPAPER, AccentRole.FILL)])
        assertEquals("@android:color/system_accent1_200", system[ringColor(WALLPAPER, AccentRole.LINK_ON_DARK)])
        // Solid story rings: every stop of the gradient at the one fill shade, so the ring is one color (#519).
        val solid = styleItems(styles, "HushGramAccentRingsSolid_")
        assertEquals((ACCENT_PALETTES.map { it.name } + WALLPAPER).map { solidRingStyle(it) }, solid.keys.toList())
        assertEquals(gradient.map { it to "@color/hushgram_accent_purple_ring_fill" }, solid[solidRingStyle("PURPLE")])
        assertEquals(gradient.map { it to "@color/${ringColor(WALLPAPER, AccentRole.FILL)}" }, solid[solidRingStyle(WALLPAPER)])

        val yellowOnly = parse("<resources />")
        val yellowColors = parse("<resources />")
        addAccentResources(uses, declaring(listOf("igds_color_gradient_yellow")), yellowColors, yellowOnly)
        assertEquals(
            "the solid ring's shade is there even when no gradient stop takes it",
            "#ff7638fa",
            yellowColors.elements("color").associate { it.getAttribute("name") to it.textContent }["hushgram_accent_purple_ring_fill"],
        )
        assertEquals(
            listOf("igds_color_gradient_yellow" to "@color/hushgram_accent_purple_ring_fill"),
            styleItems(yellowOnly, "HushGramAccentRingsSolid_")[solidRingStyle("PURPLE")],
        )

        val oneOfThem = parse("<resources />")
        addAccentResources(uses, declaring(listOf("igds_color_gradient_red")), parse("<resources />"), oneOfThem)
        assertEquals(
            "only what the build declares",
            listOf("igds_color_gradient_red" to "@color/hushgram_accent_teal_ring_fill"),
            styleItems(oneOfThem, "HushGramAccentRings_")["HushGramAccentRings_TEAL"],
        )

        val none = parse("<resources />")
        addAccentResources(uses, parse("<resources />"), parse("<resources />"), none)
        assertTrue("no ring styles without the gradient", styleItems(none, "HushGramAccentRings_").isEmpty())
        assertTrue("no solid ring styles without the gradient", styleItems(none, "HushGramAccentRingsSolid_").isEmpty())

        val takenSolid = parse("<resources><style name=\"HushGramAccentRingsSolid_RED\" /></resources>")
        val solidAgain = assertThrows(PatchException::class.java) { addAccentResources(uses, declaring(gradient), parse("<resources />"), takenSolid) }
        assertTrue(solidAgain.message, solidAgain.message!!.contains("already have a HushGramAccentRingsSolid_RED"))

        val taken = parse("<resources><color name=\"hushgram_accent_red_ring_fill\">#ff000000</color></resources>")
        val again = assertThrows(PatchException::class.java) { addAccentResources(uses, declaring(gradient), taken, parse("<resources />")) }
        assertTrue(again.message, again.message!!.contains("already have a hushgram_accent_red_ring_fill"))
    }

    /** With Instagram's background attribute declared, each color gets a dark and a light shade for each step and a style for each theme (#187). */
    @Test
    fun theBackgroundStylesPaintEachSurfaceInAShadeOfEachColor() {
        val uses = setOf(AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff))
        val attrs = declaring(listOf("igds_color_primary_background", "igds_color_elevated_background", "igds_color_secondary_background"))
        val colors = parse("<resources />")
        val colorsV31 = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)
        addWallpaperResources(uses, attrs, colors, colorsV31, styles)

        val values = colors.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("#ff090414", values["hushgram_accent_purple_background_dark_base"])
        assertEquals("#ff110823", values["hushgram_accent_purple_background_dark_raised"])
        assertEquals("#ff180b32", values["hushgram_accent_purple_background_dark_top"])
        assertEquals("#fffaf7ff", values["hushgram_accent_purple_background_light_base"])
        assertEquals("#fff4efff", values["hushgram_accent_purple_background_light_raised"])
        assertEquals("#ffefe7fe", values["hushgram_accent_purple_background_light_top"])
        val backgrounds = styleItems(styles, "HushGramAccentBackgrounds_")
        assertEquals(
            (ACCENT_PALETTES.map { it.name } + WALLPAPER).flatMap { palette -> IconTheme.entries.map { backgroundStyle(palette, it) } },
            backgrounds.keys.toList(),
        )
        assertEquals(
            listOf(
                "igds_color_primary_background" to "@color/hushgram_accent_purple_background_dark_base",
                "igds_color_elevated_background" to "@color/hushgram_accent_purple_background_dark_raised",
                "igds_color_secondary_background" to "@color/hushgram_accent_purple_background_dark_top",
            ),
            backgrounds["HushGramAccentBackgrounds_PURPLE_dark"],
        )
        val system = colorsV31.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("@android:color/system_neutral2_900", system["hushgram_accent_wallpaper_background_dark_base"])
        assertEquals("@android:color/system_neutral2_10", system["hushgram_accent_wallpaper_background_light_base"])
        assertEquals("@android:color/system_neutral2_700", system["hushgram_accent_wallpaper_background_dark_top"])
        assertEquals("@android:color/system_neutral2_100", system["hushgram_accent_wallpaper_background_light_top"])

        val noPrimary = parse("<resources />")
        addAccentResources(uses, declaring(listOf("igds_color_elevated_background")), parse("<resources />"), noPrimary)
        assertTrue("a theme that can't say what its screens are gets none", styleItems(noPrimary, "HushGramAccentBackgrounds_").isEmpty())

        val taken = parse("<resources><color name=\"hushgram_accent_red_background_light_top\">#ff000000</color></resources>")
        val fresh = declaring(listOf("igds_color_primary_background", "igds_color_secondary_background"))
        val again = assertThrows(PatchException::class.java) { addAccentResources(uses, fresh, taken, parse("<resources />")) }
        assertTrue(again.message, again.message!!.contains("already have a hushgram_accent_red_background_light_top"))
    }

    /**
     * With Instagram's background attribute declared, Classic colors gets the older look's shade for each
     * step, navy and charcoal on a dark theme and off-white on a light one, and a style for each theme (#639).
     */
    @Test
    fun theClassicStylesPaintEachSurfaceInTheOlderLook() {
        val uses = setOf(AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff))
        val attrs = declaring(listOf("igds_color_primary_background", "igds_color_elevated_background", "igds_color_secondary_background"))
        val colors = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)

        val values = colors.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("#ff101b2d", values["hushgram_classic_background_dark_base"])
        assertEquals("#ff262626", values["hushgram_classic_background_dark_raised"])
        assertEquals("#ff363636", values["hushgram_classic_background_dark_top"])
        assertEquals("#fffafafa", values["hushgram_classic_background_light_base"])
        assertEquals("#ffffffff", values["hushgram_classic_background_light_raised"])
        assertEquals("#ffefefef", values["hushgram_classic_background_light_top"])
        val classic = styleItems(styles, "HushGramClassicBackgrounds_")
        assertEquals(listOf("HushGramClassicBackgrounds_light", "HushGramClassicBackgrounds_dark"), classic.keys.toList())
        assertEquals(
            listOf(
                "igds_color_primary_background" to "@color/hushgram_classic_background_dark_base",
                "igds_color_elevated_background" to "@color/hushgram_classic_background_dark_raised",
                "igds_color_secondary_background" to "@color/hushgram_classic_background_dark_top",
            ),
            classic["HushGramClassicBackgrounds_dark"],
        )

        val withDark = parse("<resources />")
        addAccentResources(
            uses,
            declaring(listOf("igds_color_primary_background", "igds_color_elevated_background_dark")),
            parse("<resources />"),
            withDark,
        )
        val dark = styleItems(withDark, "HushGramClassicBackgrounds_")
        assertEquals(
            "the dark style takes the always-dark surface",
            "@color/hushgram_classic_background_dark_raised",
            dark.getValue("HushGramClassicBackgrounds_dark").toMap()["igds_color_elevated_background_dark"],
        )
        assertEquals(
            "the light style leaves Instagram's dark gray for the pills on it",
            listOf("igds_color_primary_background" to "@color/hushgram_classic_background_light_base"),
            dark["HushGramClassicBackgrounds_light"],
        )
        val accent = styleItems(withDark, "HushGramAccentBackgrounds_")
        assertTrue("the accent has background styles", accent.isNotEmpty())
        assertTrue(
            "nor do the accent's light backgrounds",
            accent.filterKeys { it.endsWith("_light") }.values.none { items -> items.any { it.first.endsWith("_dark") } },
        )

        val noPrimary = parse("<resources />")
        addAccentResources(uses, declaring(listOf("igds_color_elevated_background")), parse("<resources />"), noPrimary)
        assertTrue("a theme that can't say what its screens are gets none", styleItems(noPrimary, "HushGramClassicBackgrounds_").isEmpty())

        val taken = parse("<resources><style name=\"HushGramClassicBackgrounds_dark\" /></resources>")
        val fresh = declaring(listOf("igds_color_primary_background"))
        val again = assertThrows(PatchException::class.java) { addAccentResources(uses, fresh, parse("<resources />"), taken) }
        assertTrue(again.message, again.message!!.contains("already have a HushGramClassicBackgrounds_dark"))
    }

    /**
     * The Reels tab's bar takes the page's shade from every dark background style, so it no longer turns
     * Prism black as the tab opens (#187), and no light style touches it, since Instagram keeps that tab
     * dark with white icons on a light theme.
     */
    @Test
    fun theReelsTabBarTakesThePageShadeInDarkStylesOnly() {
        val uses = setOf(AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff))
        val attrs = declaring(listOf("igds_color_primary_background", REELS_TAB_BAR))
        val colors = parse("<resources />")
        val colorsV31 = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)
        addWallpaperResources(uses, attrs, colors, colorsV31, styles)

        val accent = styleItems(styles, "HushGramAccentBackgrounds_")
        assertEquals(
            "the Reels tab bar goes with the page",
            "@color/${backgroundColor(WALLPAPER, IconTheme.DARK, BackgroundStep.BASE)}",
            accent.getValue(backgroundStyle(WALLPAPER, IconTheme.DARK)).toMap()[REELS_TAB_BAR],
        )
        assertEquals(
            "@color/${backgroundColor("PURPLE", IconTheme.DARK, BackgroundStep.BASE)}",
            accent.getValue(backgroundStyle("PURPLE", IconTheme.DARK)).toMap()[REELS_TAB_BAR],
        )
        assertEquals(
            "@color/${classicColor(IconTheme.DARK, BackgroundStep.BASE)}",
            styleItems(styles, "HushGramClassicBackgrounds_").getValue(classicStyle(IconTheme.DARK)).toMap()[REELS_TAB_BAR],
        )
        val light = (accent.filterKeys { it.endsWith("_light") } + styleItems(styles, "HushGramClassicBackgrounds_light")).values
        assertTrue("the light styles leave the Reels tab dark", light.isNotEmpty() && light.none { items -> items.any { it.first == REELS_TAB_BAR } })
        assertTrue("the light styles still paint the page", light.all { items -> items.any { it.first == "igds_color_primary_background" } })
    }

    /**
     * A profile's Edit profile and Share profile buttons, and the other gray secondary buttons, take the
     * top shade from every background style, dark and light, instead of Prism's grays (#187).
     */
    @Test
    fun theSecondaryButtonsTakeTheTopShade() {
        val uses = setOf(AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff))
        val attrs = declaring(listOf("igds_color_primary_background") + SECONDARY_BUTTONS)
        val colors = parse("<resources />")
        val colorsV31 = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)
        addWallpaperResources(uses, attrs, colors, colorsV31, styles)

        val accent = styleItems(styles, "HushGramAccentBackgrounds_")
        val classic = styleItems(styles, "HushGramClassicBackgrounds_")
        assertEquals(2, SECONDARY_BUTTONS.size)
        for (theme in IconTheme.values()) {
            for (button in SECONDARY_BUTTONS) {
                assertEquals(
                    "$button, $theme",
                    "@color/${backgroundColor(WALLPAPER, theme, BackgroundStep.TOP)}",
                    accent.getValue(backgroundStyle(WALLPAPER, theme)).toMap()[button],
                )
                assertEquals(
                    "@color/${backgroundColor("PURPLE", theme, BackgroundStep.TOP)}",
                    accent.getValue(backgroundStyle("PURPLE", theme)).toMap()[button],
                )
                assertEquals(
                    "@color/${classicColor(theme, BackgroundStep.TOP)}",
                    classic.getValue(classicStyle(theme)).toMap()[button],
                )
            }
        }
    }

    /** Text stays Instagram's color, so every color's backgrounds have to leave that text easy to read on them (#187, #639). */
    @Test
    fun instagramsTextStaysReadableOnEveryBackground() {
        for (palette in ACCENT_PALETTES) {
            for (step in BackgroundStep.entries) {
                val dark = contrast(0xf5f5f5, palette.background(IconTheme.DARK, step))
                assertTrue("${palette.name} $step on a dark theme: $dark", dark >= 7.0)
                val light = contrast(0x0c1014, palette.background(IconTheme.LIGHT, step))
                assertTrue("${palette.name} $step on a light theme: $light", light >= 7.0)
            }
        }
        for (step in BackgroundStep.entries) {
            val dark = contrast(0xf5f5f5, classicBackground(IconTheme.DARK, step))
            assertTrue("classic $step on a dark theme: $dark", dark >= 7.0)
            val light = contrast(0x0c1014, classicBackground(IconTheme.LIGHT, step))
            assertTrue("classic $step on a light theme: $light", light >= 7.0)
        }
    }

    /** Dusk blue, Pine and Slate are the whole sets asked for in #528: three gradient stops, a likes color and screen colors. */
    @Test
    fun theCalmColorsCarryTheirWholeSet() {
        val uses = setOf(AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff))
        val attrs = declaring(RING_ATTRIBUTES.keys + BADGE_ATTRIBUTE + BACKGROUND_ATTRIBUTE + "igds_color_secondary_background")
        val colors = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)

        val values = colors.elements("color").associate { it.getAttribute("name") to it.textContent }
        val rings = styleItems(styles, "HushGramAccentRings_")
        for ((palette, stops) in mapOf(
            "DUSK" to listOf("#ff4a6b82", "#ff769f95", "#ffc8d5b9"),
            "PINE" to listOf("#ff2a6f68", "#ffe07a5f", "#fff2cc8f"),
            "SLATE" to listOf("#ff3a506b", "#ff00a896", "#ff6fffe9"),
        )) {
            val painted = rings.getValue(ringStyle(palette)).associate { (attribute, color) -> attribute to values[color.removePrefix("@color/")] }
            assertEquals(palette, stops[0], painted["igds_color_gradient_purple"])
            assertEquals(palette, stops[0], painted["igds_color_gradient_lavender"])
            assertEquals(palette, stops[1], painted["igds_color_gradient_red"])
            assertEquals(palette, stops[1], painted["igds_color_gradient_pink"])
            assertEquals(palette, stops[2], painted["igds_color_gradient_orange"])
            assertEquals(palette, stops[2], painted["igds_color_gradient_yellow"])
            assertEquals(
                "Solid story rings stay on the color you picked",
                stops[0],
                values[styleItems(styles, "HushGramAccentRingsSolid_").getValue(solidRingStyle(palette)).first().second.removePrefix("@color/")],
            )
        }

        assertEquals("#ffd48c70", values["hushgram_accent_dusk_badge"])
        assertEquals("#ffe09f3e", values["hushgram_accent_pine_badge"])
        assertEquals("#ff5bc0be", values["hushgram_accent_slate_badge"])

        assertEquals("#ff182022", values[backgroundColor("DUSK", IconTheme.DARK, BackgroundStep.BASE)])
        assertEquals("#fff4f6f4", values[backgroundColor("DUSK", IconTheme.LIGHT, BackgroundStep.BASE)])
        assertEquals("#ff1f2421", values[backgroundColor("PINE", IconTheme.DARK, BackgroundStep.BASE)])
        assertEquals("#fffaf6f0", values[backgroundColor("PINE", IconTheme.LIGHT, BackgroundStep.BASE)])
        assertEquals("#ff0b132b", values[backgroundColor("SLATE", IconTheme.DARK, BackgroundStep.BASE)])
        assertEquals("#ffeceff1", values[backgroundColor("SLATE", IconTheme.LIGHT, BackgroundStep.BASE)])
        assertEquals("a raised surface stands off the screen", "#ff243239", values[backgroundColor("DUSK", IconTheme.DARK, BackgroundStep.TOP)])

        assertEquals("other colors keep their shades", "@color/hushgram_accent_teal_ring_fill", rings.getValue(ringStyle("TEAL")).toMap()["igds_color_gradient_pink"])
        assertEquals("other colors' badges stay the fill", "#ff00838f", values["hushgram_accent_teal_badge"])

        val taken = parse("<resources><color name=\"hushgram_accent_pine_ring_stop_fill\">#ff000000</color></resources>")
        val again = assertThrows(PatchException::class.java) { addAccentResources(uses, declaring(RING_ATTRIBUTES.keys), taken, parse("<resources />")) }
        assertTrue(again.message, again.message!!.contains("already have a hushgram_accent_pine_ring_stop_fill"))
    }

    private fun declaring(attributes: Collection<String>) =
        parse("<resources>" + attributes.joinToString("") { "<attr name=\"$it\" format=\"reference|color\" />" } + "</resources>")

    private fun styleItems(styles: org.w3c.dom.Document, prefix: String): Map<String, List<Pair<String, String>>> =
        styles.elements("style").filter { it.getAttribute("name").startsWith(prefix) }
            .associate { style -> style.getAttribute("name") to style.elements("item").map { it.getAttribute("name") to it.textContent } }

    /** The contrast ratio of two colors, as WCAG works it out. */
    private fun contrast(first: Int, second: Int): Double {
        fun luminance(rgb: Int): Double {
            fun channel(value: Int): Double = (value / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * channel((rgb shr 16) and 0xff) + 0.7152 * channel((rgb shr 8) and 0xff) + 0.0722 * channel(rgb and 0xff)
        }
        val (high, low) = listOf(luminance(first), luminance(second)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }

    /**
     * Instagram's light and dark themes give the glyph attributes colors of their own rather than the
     * icon attribute, so the icon styles set each one declared to the same shade (#8). Without the icon
     * attribute there are no icon styles at all, glyphs or not.
     */
    @Test
    fun theIconStylesAlsoSetEachDeclaredGlyphAttribute() {
        val uses = setOf(AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff))
        val declared = (listOf(ICON_ATTRIBUTE, "glyphColorSecondary") + GLYPH_ATTRIBUTES.drop(1))
            .joinToString("") { "<attr name=\"$it\" format=\"reference|color\" />" }
        val attrs = parse("<resources>$declared</resources>")
        val colors = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)
        addWallpaperResources(uses, attrs, colors, parse("<resources />"), styles)

        val icons = styles.elements("style").filter { it.getAttribute("name").startsWith("HushGramAccentIcons_") }
            .associate { style -> style.getAttribute("name") to style.elements("item").map { it.getAttribute("name") to it.textContent } }
        assertEquals(
            listOf(ICON_ATTRIBUTE, "glyphColorSecondaryActive", "feedSaveActiveColor").map { it to "@color/hushgram_accent_purple_icon_light" },
            icons[iconStyle("PURPLE", IconTheme.LIGHT)],
        )
        assertEquals(
            listOf(ICON_ATTRIBUTE, "glyphColorSecondaryActive", "feedSaveActiveColor").map { it to "@color/hushgram_accent_wallpaper_icon_dark" },
            icons[iconStyle(WALLPAPER, IconTheme.DARK)],
        )
        assertTrue("the unselected tabs' gray stays", icons.values.flatten().none { it.first == "glyphColorSecondary" })
        assertTrue("the like heart's red stays", icons.values.flatten().none { it.first == BADGE_ATTRIBUTE })

        val glyphsOnly = parse("<resources>${GLYPH_ATTRIBUTES.joinToString("") { "<attr name=\"$it\" />" }}</resources>")
        val bare = parse("<resources />")
        addAccentResources(uses, glyphsOnly, parse("<resources />"), bare)
        assertTrue(bare.elements("style").none { it.getAttribute("name").startsWith("HushGramAccentIcons_") })
    }

    /**
     * With the icon attribute declared, each color gets a light and a dark text style pointing each
     * declared text attribute at the same link shade its icon style uses, for Text in the accent color
     * (#187). Secondary text isn't in it. Without the icon attribute there are no text styles, and a
     * name already there is refused.
     */
    @Test
    fun theTextStylesPointTheTextAttributesAtEachIconColor() {
        val uses = setOf(AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff))
        val declared = (listOf(ICON_ATTRIBUTE, "igds_color_secondary_text") + TEXT_ATTRIBUTES)
            .joinToString("") { "<attr name=\"$it\" format=\"reference|color\" />" }
        val attrs = parse("<resources>$declared</resources>")
        val colors = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)
        addWallpaperResources(uses, attrs, colors, parse("<resources />"), styles)

        val text = styles.elements("style").filter { it.getAttribute("name").startsWith("HushGramAccentText_") }
            .associate { style -> style.getAttribute("name") to style.elements("item").map { it.getAttribute("name") to it.textContent } }
        assertEquals(
            (ACCENT_PALETTES.map { it.name } + WALLPAPER).flatMap { palette -> IconTheme.entries.map { textStyle(palette, it) } },
            text.keys.toList(),
        )
        assertEquals("HushGramAccentText_TEAL_dark", textStyle("TEAL", IconTheme.DARK))
        assertEquals(
            listOf("igds_color_primary_text", "igds_color_text_and_icon_primary").map { it to "@color/${iconColor("TEAL", IconTheme.LIGHT)}" },
            text[textStyle("TEAL", IconTheme.LIGHT)],
        )
        assertEquals(
            TEXT_ATTRIBUTES.map { it to "@color/${iconColor(WALLPAPER, IconTheme.DARK)}" },
            text[textStyle(WALLPAPER, IconTheme.DARK)],
        )
        assertTrue("secondary text stays gray", text.values.flatten().none { it.first == "igds_color_secondary_text" })

        val onlyOne = parse("<resources><attr name=\"$ICON_ATTRIBUTE\" /><attr name=\"igds_color_primary_text\" /></resources>")
        val some = parse("<resources />")
        addAccentResources(uses, onlyOne, parse("<resources />"), some)
        assertEquals(
            listOf("igds_color_primary_text" to "@color/${iconColor("PURPLE", IconTheme.DARK)}"),
            some.elements("style").single { it.getAttribute("name") == textStyle("PURPLE", IconTheme.DARK) }
                .elements("item").map { it.getAttribute("name") to it.textContent },
        )

        val textOnly = parse("<resources>${TEXT_ATTRIBUTES.joinToString("") { "<attr name=\"$it\" />" }}</resources>")
        val bare = parse("<resources />")
        addAccentResources(uses, textOnly, parse("<resources />"), bare)
        assertTrue(bare.elements("style").none { it.getAttribute("name").startsWith("HushGramAccentText_") })

        val taken = parse("<resources><style name=\"${textStyle("RED", IconTheme.LIGHT)}\" /></resources>")
        val again = assertThrows(PatchException::class.java) { addAccentResources(uses, parse("<resources>$declared</resources>"), parse("<resources />"), taken) }
        assertTrue(again.message, again.message!!.contains("already have a ${textStyle("RED", IconTheme.LIGHT)}"))
    }

    /**
     * With the icon attribute declared, each color gets a light and a dark gray text color, its link
     * shade at [SUBTEXT_ALPHA], and a style pointing each declared gray text attribute at it, for Gray
     * text in the accent color (#8). Material You takes a paler step on light and a deeper one on dark,
     * since a system color can't take an alpha. Without the icon attribute there are none, and a name
     * already there is refused.
     */
    @Test
    fun theGrayTextStylesPointTheGrayTextAttributesAtASofterShade() {
        val uses = setOf(AccentUse("igds_color_link", AccentRole.LINK_ON_DARK, 0xff))
        val declared = (listOf(ICON_ATTRIBUTE) + TEXT_ATTRIBUTES + SUBTEXT_ATTRIBUTES)
            .joinToString("") { "<attr name=\"$it\" format=\"reference|color\" />" }
        val attrs = parse("<resources>$declared</resources>")
        val colors = parse("<resources />")
        val colorsV31 = parse("<resources />")
        val styles = parse("<resources />")

        addAccentResources(uses, attrs, colors, styles)
        addWallpaperResources(uses, attrs, colors, colorsV31, styles)

        val subtext = styles.elements("style").filter { it.getAttribute("name").startsWith("HushGramAccentSubtext_") }
            .associate { style -> style.getAttribute("name") to style.elements("item").map { it.getAttribute("name") to it.textContent } }
        assertEquals(
            (ACCENT_PALETTES.map { it.name } + WALLPAPER).flatMap { palette -> IconTheme.entries.map { subtextStyle(palette, it) } },
            subtext.keys.toList(),
        )
        assertEquals("HushGramAccentSubtext_PINK_light", subtextStyle("PINK", IconTheme.LIGHT))
        assertEquals(
            listOf("igds_color_secondary_text", "igds_color_text_and_icon_secondary").map { it to "@color/${subtextColor("PINK", IconTheme.LIGHT)}" },
            subtext[subtextStyle("PINK", IconTheme.LIGHT)],
        )
        val values = colors.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("pink's light link shade at 70%", "#b3c1014f", values["hushgram_accent_pink_subtext_light"])
        assertEquals("pink's dark link shade at 70%", "#b3ff6fa2", values["hushgram_accent_pink_subtext_dark"])
        assertEquals("the typed color's stand-in", "#b34a6b82", values["hushgram_accent_custom_subtext_light"])
        assertEquals("#ff9a82db", values[subtextColor(WALLPAPER, IconTheme.LIGHT)])
        val system = colorsV31.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("@android:color/system_accent1_400", system[subtextColor(WALLPAPER, IconTheme.LIGHT)])
        assertEquals("@android:color/system_accent1_300", system[subtextColor(WALLPAPER, IconTheme.DARK)])
        assertTrue("main text keeps its own styles", subtext.values.flatten().none { it.first in TEXT_ATTRIBUTES })

        val noIcon = parse("<resources>${SUBTEXT_ATTRIBUTES.joinToString("") { "<attr name=\"$it\" />" }}</resources>")
        val bare = parse("<resources />")
        addAccentResources(uses, noIcon, parse("<resources />"), bare)
        assertTrue(bare.elements("style").none { it.getAttribute("name").startsWith("HushGramAccentSubtext_") })

        val taken = parse("<resources><color name=\"${subtextColor("RED", IconTheme.DARK)}\">#ff000000</color></resources>")
        val again = assertThrows(PatchException::class.java) {
            addAccentResources(uses, parse("<resources>$declared</resources>"), taken, parse("<resources />"))
        }
        assertTrue(again.message, again.message!!.contains("already have a ${subtextColor("RED", IconTheme.DARK)}"))
    }

    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val declared = app.morphe.ExtensionDex.classDef(ACCENT_THEME).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .associateBy { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$APPLY_THEME is not in the extension: ${declared.keys}", APPLY_THEME in declared)
        assertTrue("$HEADER_ICONS is not in the extension: ${declared.keys}", HEADER_ICONS in declared)
        assertTrue("$HEADER_ICON is not in the extension: ${declared.keys}", HEADER_ICON in declared)
        for (signature in listOf(ATTRIBUTES_STUB, CUSTOM_COLORS_STUB)) {
            val stub = declared[signature]
            assertTrue("$signature is not in the extension: ${declared.keys}", stub != null)
            assertTrue("$signature has a register to write into", stub!!.implementation!!.registerCount >= 1)
        }
    }

    /**
     * A typed color (#520) gets a full set of colors and styles like a preset's, holding Dusk blue's
     * shades as stand-ins, and their names are what the patch writes into the extension's stub.
     */
    @Test
    fun aTypedColorGetsStandInColorsAndTheirNamesAreListed() {
        val uses = setOf(
            AccentUse("igds_color_link", AccentRole.LINK_ON_LIGHT, 0xff),
            AccentUse("igds_color_primary_button_indigo", AccentRole.FILL, 0xff),
            AccentUse("igds_color_highlight", AccentRole.FILL, 0x33),
        )
        val attrs = declaring(listOf(BADGE_ATTRIBUTE, "igds_color_primary_icon", "igds_color_primary_background"))
        val colors = parse("<resources />")
        val styles = parse("<resources />")
        addAccentResources(uses, attrs, colors, styles)
        val custom = ACCENT_PALETTES.single { it.name == CUSTOM }
        val dusk = ACCENT_PALETTES.single { it.name == "DUSK" }
        assertEquals("the stand-ins are Dusk blue's", dusk.shades, custom.shades)
        val names = customColorNames(colors).split(" ")
        assertTrue(names.toString(), "hushgram_accent_custom_fill_ff" in names && "hushgram_accent_custom_fill_33" in names)
        assertTrue(names.toString(), "hushgram_accent_custom_badge" in names && "hushgram_accent_custom_icon_dark" in names)
        assertTrue(names.toString(), names.any { it.startsWith("hushgram_accent_custom_background_dark_") })
        assertTrue("only the custom colors are listed: $names", names.all { it.startsWith(CUSTOM_COLOR_PREFIX) })
        val values = colors.elements("color").associate { it.getAttribute("name") to it.textContent }
        assertEquals("#33%06x".format(custom.shades.getValue(AccentRole.FILL)), values["hushgram_accent_custom_fill_33"])
        assertTrue(styles.elements("style").any { it.getAttribute("name") == "HushGramAccentPalette_CUSTOM" })
        for (palette in listOf("DUSK", "PINE", "SLATE")) {
            assertTrue("$palette has a palette style", styles.elements("style").any { it.getAttribute("name") == "HushGramAccentPalette_$palette" })
        }
    }

    /** The custom color names go into their own stub, a name that can't be written refuses, and none leaves the stub alone. */
    @Test
    fun theCustomColorNamesGoIntoTheirStub() {
        val context = PatchContexts.of(listOf(activity(), overrides(), util(), extension()))
        context.applyAccentTheme("igds_color_link", "hushgram_accent_custom_fill_ff hushgram_accent_custom_badge")
        val written = context.mutableClassDefBy(ACCENT_THEME).methods.single { it.name == "customColors" }.code()
        assertEquals(listOf(Opcode.CONST_STRING, Opcode.RETURN_OBJECT), written.take(2).map { it.opcode })
        assertEquals("hushgram_accent_custom_fill_ff hushgram_accent_custom_badge", (written.first() as ReferenceInstruction).reference.toString())

        val none = PatchContexts.of(listOf(activity(), overrides(), util(), extension()))
        none.applyAccentTheme("igds_color_link", "")
        assertEquals(listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT),
            none.classDefBy(ACCENT_THEME).methods.single { it.name == "customColors" }.code().map { it.opcode })

        val quoted = PatchContexts.of(listOf(activity(), overrides(), util(), extension()))
        val failure = assertThrows(PatchException::class.java) { quoted.applyAccentTheme("igds_color_link", "hushgram_accent_custom_fill_ff\"") }
        assertTrue(failure.message, failure.message!!.startsWith("Accent color"))
        assertEquals("the attribute stub untouched too", listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT),
            quoted.classDefBy(ACCENT_THEME).methods.single { it.name == "attributes" }.code().map { it.opcode })
    }

    /**
     * The attribute list goes into the stub and the overrides' every return runs AccentTheme.apply with
     * the activity. Anything else refuses before a change.
     */
    @Test
    fun everyReturnOfTheOverridesAndTheUtilAppliesTheTheme() {
        val context = PatchContexts.of(listOf(activity(), overrides(), util(), extension()))
        context.applyAccentTheme("igds_color_link android:textColorLink")
        assertApplied("stand-in", context.mutableClassDefBy(OVERRIDES).methods.single { it.name == "A04" }, returns = 2)
        assertApplied("stand-in util", context.mutableClassDefBy(UTIL).methods.single { it.name == "A01" }, returns = 1)
        val written = context.mutableClassDefBy(ACCENT_THEME).methods.single { it.name == "attributes" }.code()
        assertEquals(listOf(Opcode.CONST_STRING, Opcode.RETURN_OBJECT), written.take(2).map { it.opcode })
        assertEquals("igds_color_link android:textColorLink", (written.first() as ReferenceInstruction).reference.toString())

        for ((case, classes) in listOf(
            "no activity" to listOf(overrides(), util(), extension()),
            "no overrides class" to listOf(activity(), util(), extension()),
            "a setup applying two styles" to listOf(activity(secondStyle = true), overrides(), util(), extension()),
            "nothing called after the style" to listOf(activity(callsOverrides = false), overrides(), util(), extension()),
            "overrides applying one style" to listOf(activity(), overrides(styles = 1), util(), extension()),
            "overrides writing over the activity" to listOf(activity(), overrides(reuseContext = true), util(), extension()),
            "no theme util" to listOf(activity(), overrides(), extension()),
            "a util applying no style" to listOf(activity(), overrides(), util(applies = false), extension()),
            "two theme utils" to listOf(activity(), overrides(), util(), util(type = "Lfixture/ThemeUtil2;"), extension()),
            "a util writing over the context" to listOf(activity(), overrides(), util(reuseContext = true), extension()),
            "no hook in the extension" to listOf(activity(), overrides(), util()),
            "no stub in the extension" to listOf(activity(), overrides(), util(), extension(stub = false)),
            "no custom color stub in the extension" to listOf(activity(), overrides(), util(), extension(customStub = false)),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).applyAccentTheme("igds_color_link") }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Accent color"))
        }
        for ((case, attributes) in listOf("no list" to "", "a quote in a name" to "igds_color_link\"")) {
            val untouched = PatchContexts.of(listOf(activity(), overrides(), util(), extension()))
            val failure = assertThrows(case, PatchException::class.java) { untouched.applyAccentTheme(attributes) }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Accent color"))
            assertEquals("$case: the stub untouched", listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT),
                untouched.classDefBy(ACCENT_THEME).methods.single { it.name == "attributes" }.code().map { it.opcode })
        }
        val stub = overrides()
        val refused = PatchContexts.of(listOf(activity(), stub, util(reuseContext = true), extension()))
        assertThrows(PatchException::class.java) { refused.applyAccentTheme("igds_color_link") }
        assertEquals("the overrides untouched when the util refuses", stub.methods.single().implementation!!.instructions.map { it.opcode },
            refused.classDefBy(OVERRIDES).methods.single().implementation!!.instructions.map { it.opcode })
    }

    /**
     * Every build of 452: the setup and the overrides are found by their shape, the theme util by the
     * line it logs, and each of their returns applies the theme.
     */
    @Test
    fun everyBuildAppliesTheThemeAfterTheOverridesAndTheUtil() {
        val builds = builds()
        assertEquals("every build of Instagram ${Fixtures.VERSION} has a fixture", Fixtures.BUILDS, builds.size)
        for ((code, bundle) in builds) {
            val activity = FixtureDex.classes(bundle, setOf(IG_FRAGMENT_ACTIVITY)).getValue(IG_FRAGMENT_ACTIVITY)
            val setup = activity.methods.single { method ->
                method.parameterTypes.isEmpty() && method.returnType == "V" &&
                    method.code().count { it.callsNamed(THEME, "applyStyle") } == 1
            }
            val called = setup.code().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .first { it.parameterTypes.map(Any::toString) == listOf(CONTEXT) && it.returnType == "V" }
            val holder = FixtureDex.classes(bundle, setOf(called.definingClass)).getValue(called.definingClass)
            val logging = FixtureDex.classesLoading(bundle, THEME_UTIL_FAILURE).filter { it.type != holder.type && it.type != activity.type }
            val context = PatchContexts.of(listOf(activity, holder, extension()) + logging)
            val found = context.findThemeOverrides()
            assertEquals("$code: the overrides", "${called.definingClass}->${called.name}", "${found.definingClass}->${found.name}")
            val returns = found.code().count { it.opcode == Opcode.RETURN_VOID }
            val util = context.findThemeUtil()
            assertTrue("$code: the util is static, ${util.definingClass}->${util.name}", AccessFlags.STATIC.isSet(util.accessFlags))
            val utilReturns = util.code().count { it.opcode == Opcode.RETURN_VOID }
            context.applyAccentTheme("igds_color_link")
            assertApplied(code, context.mutableClassDefBy(called.definingClass).methods.single { it.name == called.name && it.parameterTypes.size == 1 }, returns)
            assertApplied("$code util", context.mutableClassDefBy(util.definingClass).methods.single { it.name == util.name && it.parameterTypes.size == 1 }, utilReturns)
        }
    }

    /**
     * Home's newer header hands the color it reads for its heart and Create buttons to the extension
     * right before keeping it, and only that color, whether the one over media comes from a helper or
     * from getColor too (#8). No header, the icons' color kept twice or a jump onto the store leave it
     * as it is, and an extension without the hook refuses.
     */
    @Test
    fun homesHeaderHandsTheColorOfItsIconsToTheExtension() {
        for ((case, header) in listOf("over media from a helper" to header(), "over media from getColor" to header(overMediaRead = true))) {
            val context = PatchContexts.of(listOf(header, extension()))
            context.tintHeaderIcons()
            val code = context.mutableClassDefBy(MERGED_LOGO_BAR).methods.single { it.name == "<init>" }.code()
            val hooks = code.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == HEADER_ICONS }
            assertEquals("$case: one hook", 1, hooks.size)
            val at = hooks.single().index
            assertEquals(
                case,
                listOf(Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IPUT),
                code.subList(at - 2, at + 3).map { it.opcode },
            )
            val hook = code[at] as FiveRegisterInstruction
            assertEquals("$case: the hook takes the Context and the color", listOf(2, 0), listOf(hook.registerC, hook.registerD))
            assertEquals("$case: the icons' field", "$MERGED_LOGO_BAR->A0W:I", (code[at + 2] as ReferenceInstruction).reference.toString())
        }

        // 454's outlined read: the static the constructor asks calls getColor on the Context it's handed.
        val outlined = PatchContexts.of(listOf(header(outlined = true), iconColors(), extension()))
        outlined.tintHeaderIcons()
        val outlinedCode = outlined.mutableClassDefBy(MERGED_LOGO_BAR).methods.single { it.name == "<init>" }.code()
        val outlinedAt = outlinedCode.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == HEADER_ICONS }
        assertEquals(
            "outlined read",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IPUT),
            outlinedCode.subList(outlinedAt - 2, outlinedAt + 3).map { it.opcode },
        )
        val outlinedHook = outlinedCode[outlinedAt] as FiveRegisterInstruction
        assertEquals("outlined read: the Context it handed on and the color", listOf(2, 0), listOf(outlinedHook.registerC, outlinedHook.registerD))

        for ((case, classes) in listOf(
            "the icons' color kept twice" to listOf(header(stored = 2), extension()),
            "a jump onto the store" to listOf(header(jumpOnto = true), extension()),
            "a static answering the resource, not its color" to listOf(header(outlined = true), iconColors(getColor = false), extension()),
            "a static the patch can't read" to listOf(header(outlined = true), extension()),
        )) {
            val untouched = PatchContexts.of(classes)
            untouched.tintHeaderIcons()
            val init = untouched.mutableClassDefBy(MERGED_LOGO_BAR).methods.single { it.name == "<init>" }.code()
            assertTrue("$case: no hook", init.none { (it as? ReferenceInstruction)?.reference?.toString() == HEADER_ICONS })
        }
        PatchContexts.of(listOf(extension())).tintHeaderIcons()
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(header(), extension(headerIcons = false))).tintHeaderIcons() }
        assertTrue("no hook in the extension: ${failure.message}", failure.message!!.startsWith("Accent color"))
    }

    /**
     * Every build of Instagram: the header's constructor hands the color it gets from Context.getColor
     * to the extension right before it keeps it in the field the heart is tinted from (#8).
     */
    @Test
    fun everyBuildHandsHomesHeaderIconColorToTheExtension() {
        for ((code, bundle) in builds()) {
            val bar = FixtureDex.classes(bundle, setOf(MERGED_LOGO_BAR)).getValue(MERGED_LOGO_BAR)
            // Some 454 builds outline the getColor into a static the constructor asks
            // (`LX/0BPb;->A03` on 386014704), so the classes its statics live in come along.
            val asked = bar.methods.filter { it.name == "<init>" }.flatMapTo(HashSet()) { method ->
                method.code().filter { it.opcode == Opcode.INVOKE_STATIC }
                    .map { ((it as ReferenceInstruction).reference as MethodReference).definingClass }
            } - MERGED_LOGO_BAR
            val helpers = FixtureDex.classes(bundle, asked).values
            val context = PatchContexts.of(listOf(bar) + helpers + extension())
            context.tintHeaderIcons()
            val hooked = context.mutableClassDefBy(MERGED_LOGO_BAR).methods.filter { it.name == "<init>" }.flatMap { method ->
                val body = method.code()
                body.indices.filter { (body[it] as? ReferenceInstruction)?.reference?.toString() == HEADER_ICONS }.map { body to it }
            }
            assertEquals("$code: one hook", 1, hooked.size)
            val (body, at) = hooked.single()
            val hook = body[at] as FiveRegisterInstruction
            val read = body[at - 2] as FiveRegisterInstruction
            val readCall = (read as ReferenceInstruction).reference as MethodReference
            val getColor = if (read.opcode == Opcode.INVOKE_STATIC) {
                // The outlined read: its body calls Context.getColor on the Context it was handed.
                val outlined = helpers.single { it.type == readCall.definingClass }.methods.single {
                    it.name == readCall.name && it.parameterTypes.map(Any::toString) == listOf(CONTEXT)
                }
                val own = outlined.implementation!!.registerCount - 1
                outlined.code().singleOrNull { (it as? ReferenceInstruction)?.reference?.toString() == "$CONTEXT->getColor(I)I" }
                    ?.takeIf { (it as FiveRegisterInstruction).registerC == own }
            } else {
                read.takeIf { readCall.toString() == "$CONTEXT->getColor(I)I" }
            }
            assertTrue("$code: Context.getColor before it, or in the static it asks: $readCall", getColor != null)
            assertEquals("$code: the color's own Context", read.registerC, hook.registerC)
            assertEquals("$code: the color read", Opcode.MOVE_RESULT, body[at - 1].opcode)
            assertEquals("$code: the hook's answer", listOf(Opcode.MOVE_RESULT, Opcode.IPUT), listOf(body[at + 1].opcode, body[at + 2].opcode))
            assertEquals("$code: written back", hook.registerD, (body[at + 1] as OneRegisterInstruction).registerA)
            assertEquals("$code: and kept", hook.registerD, (body[at + 2] as TwoRegisterInstruction).registerA)
            val field = (body[at + 2] as ReferenceInstruction).reference.toString()
            assertTrue(
                "$code: some method tints with $field",
                bar.methods.any { method -> method.code().any { it.opcode == Opcode.IGET && (it as ReferenceInstruction).reference.toString() == field } },
            )
        }
    }

    /**
     * Home's older header hands each icon it builds from a Context and a resource to the extension
     * right after it's made, with the Context it took (#8). A non-drawable built the same way stays as
     * it is, no header leaves it alone, and an extension without the hook refuses.
     */
    @Test
    fun homesOlderHeaderHandsEachIconItBuildsToTheExtension() {
        val context = PatchContexts.of(listOf(olderHeader(), icon(), icon(NOT_AN_ICON, "Ljava/lang/Object;"), extension()))
        context.tintHeaderIcons()
        for (name in listOf("A07", "A08")) {
            val code = context.mutableClassDefBy(MAIN_FEED_ACTION_BAR).methods.single { it.name == name }.code()
            val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == HEADER_ICON }
            assertEquals("$name: one hook", 1, hooks.size)
            val at = hooks.single()
            val made = code[at - 1] as FiveRegisterInstruction
            assertEquals("$name: right after the icon is made", "$ICON-><init>(${CONTEXT}I)V", (made as ReferenceInstruction).reference.toString())
            val hook = code[at] as FiveRegisterInstruction
            assertEquals("$name: the Context and the icon", listOf(made.registerD, made.registerC), listOf(hook.registerC, hook.registerD))
        }

        PatchContexts.of(listOf(extension(headerIcon = false))).tintHeaderIcons()
        val failure = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(olderHeader(), icon(), extension(headerIcon = false))).tintHeaderIcons()
        }
        assertTrue("no hook in the extension: ${failure.message}", failure.message!!.startsWith("Accent color"))
    }

    /**
     * Every build of Instagram: Home's older header hands its heart and its plain buttons to the
     * extension right after it builds each one from a Context and a resource (#8).
     */
    @Test
    fun everyBuildHandsHomesOlderHeaderIconsToTheExtension() {
        for ((code, bundle) in builds()) {
            val bar = FixtureDex.classes(bundle, setOf(MAIN_FEED_ACTION_BAR)).getValue(MAIN_FEED_ACTION_BAR)
            val made = bar.methods.flatMap { it.code() }.mapNotNull { call ->
                ((call as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf(CONTEXT, "I") }?.definingClass
            }.toSet()
            val built = FixtureDex.classes(bundle, made)
            val context = PatchContexts.of(listOf(bar, extension()) + built.values)
            context.tintHeaderIcons()
            val hooked = context.mutableClassDefBy(MAIN_FEED_ACTION_BAR).methods.flatMap { method ->
                val body = method.code()
                body.indices.filter { (body[it] as? ReferenceInstruction)?.reference?.toString() == HEADER_ICON }.map { method.name to (body to it) }
            }
            assertEquals("$code: the heart and the plain buttons", listOf("A07", "A08"), hooked.map { it.first }.sorted())
            for ((name, site) in hooked) {
                val (body, at) = site
                val init = body[at - 1] as FiveRegisterInstruction
                val type = ((init as ReferenceInstruction).reference as MethodReference).definingClass
                assertEquals("$code $name: a drawable", "Landroid/graphics/drawable/Drawable;", built.getValue(type).superclass)
                val hook = body[at] as FiveRegisterInstruction
                assertEquals("$code $name: the Context and the icon", listOf(init.registerD, init.registerC), listOf(hook.registerC, hook.registerD))
            }
        }
    }

    /**
     * A tab button hands both colors it's told to tint its icon with to the extension at its start and
     * takes the answers in their place, so Reels' white and the text color Instagram resets to after it
     * become the picked icon color (#663). Two such methods, or a jump onto the start, leave the tabs as
     * they are, no tab button changes nothing, and an extension without the hooks refuses.
     */
    @Test
    fun aTabButtonHandsTheColorsOfItsIconToTheExtension() {
        val stock = tabButton().methods.single().code().map { it.opcode }
        val context = PatchContexts.of(listOf(tabButton(), extension()))
        context.tintTabIcons()
        val code = context.mutableClassDefBy(TAB_BUTTON).methods.single().code()
        assertEquals(listOf(TAB_ICONS, TAB_ICONS_BOXED), code.take(4).mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() })
        assertEquals(
            listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT),
            code.take(4).map { it.opcode },
        )
        // Two locals, then this in v2, the color in v3 and the boxed color in v4.
        assertEquals(
            "each color handed over and written back",
            listOf(3, 3, 4, 4),
            listOf(
                (code[0] as RegisterRangeInstruction).startRegister, (code[1] as OneRegisterInstruction).registerA,
                (code[2] as RegisterRangeInstruction).startRegister, (code[3] as OneRegisterInstruction).registerA,
            ),
        )
        assertEquals("one register each", listOf(1, 1), listOf((code[0] as RegisterRangeInstruction).registerCount, (code[2] as RegisterRangeInstruction).registerCount))
        assertEquals("Instagram's own code after", stock, code.drop(4).map { it.opcode })

        for ((case, classes) in listOf(
            "two methods recoloring a tab" to listOf(tabButton(methods = 2), extension()),
            "a jump onto the start" to listOf(tabButton(jumpOnto = true), extension()),
        )) {
            val untouched = PatchContexts.of(classes)
            untouched.tintTabIcons()
            val methods = untouched.mutableClassDefBy(TAB_BUTTON).methods
            assertTrue("$case: no hook", methods.none { method -> method.code().any { (it as? ReferenceInstruction)?.reference?.toString() == TAB_ICONS } })
        }
        PatchContexts.of(listOf(extension(tabIcons = false))).tintTabIcons()
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(tabButton(), extension(tabIcons = false))).tintTabIcons() }
        assertTrue("no hook in the extension: ${failure.message}", failure.message!!.startsWith("Accent color"))
    }

    /**
     * Every build of Instagram: one method tells a tab's icon view its colors, and it hands both to the
     * extension at its start, in the registers they came in, with Instagram's code after (#663).
     */
    @Test
    fun everyBuildHandsItsTabIconColorsToTheExtension() {
        for ((code, bundle) in builds()) {
            val recolors = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == TAB_ICON_VIEW } }) { method ->
                !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                    method.parameterTypes.map(Any::toString) == listOf("I", "Ljava/lang/Integer;") &&
                    method.code().any { it.callsNamed(TAB_ICON_VIEW, "setActiveColor") } &&
                    method.code().any { it.callsNamed(TAB_ICON_VIEW, "setNormalColor") }
            }
            assertEquals("$code: one method recolors a tab's icon, ${recolors.map { "${it.definingClass}->${it.name}" }}", 1, recolors.size)
            val found = recolors.single()
            val context = PatchContexts.of(listOf(FixtureDex.classes(bundle, setOf(found.definingClass)).getValue(found.definingClass), extension()))
            context.tintTabIcons()
            val method = context.mutableClassDefBy(found.definingClass).methods.single {
                it.name == found.name && it.parameterTypes.map(Any::toString) == listOf("I", "Ljava/lang/Integer;")
            }
            val body = method.code()
            assertEquals("$code: the hooks", listOf(TAB_ICONS, TAB_ICONS_BOXED), body.take(4).mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() })
            val color = method.parameterRegisterNumber(0)
            val normal = method.parameterRegisterNumber(1)
            assertEquals(
                "$code: each color handed over and written back",
                listOf(color, color, normal, normal),
                listOf(
                    (body[0] as RegisterRangeInstruction).startRegister, (body[1] as OneRegisterInstruction).registerA,
                    (body[2] as RegisterRangeInstruction).startRegister, (body[3] as OneRegisterInstruction).registerA,
                ),
            )
            assertEquals(
                "$code: Instagram's own code after",
                found.code().map { it.opcode }.filter { it != Opcode.NOP },
                body.drop(4).map { it.opcode }.filter { it != Opcode.NOP },
            )
        }
    }

    /**
     * A reel's button column hands the color it reads to the extension right before its one return, in
     * the register returned, and the color filter each button is built with right before the button's
     * constructor takes it, in that argument's register and at the call's own label, so the look under
     * the video that skips the filter method passes too (#187). The filter method, the render and a call
     * passing the filter's register twice keep Instagram's code. Two filters, a filter reading no color
     * of the column's own, or no button built with a filter leave the column as it is, and an extension
     * without the hooks refuses.
     */
    @Test
    fun aReelsButtonColumnHandsItsColorAndFiltersToTheExtension() {
        val stock = reelColumn().methods.associate { it.name to it.code().map { op -> op.opcode } }
        val context = PatchContexts.of(listOf(reelColumn(), extension()))
        context.tintReelButtons()
        val methods = context.mutableClassDefBy(REEL_COLUMN).methods.associateBy { it.name }

        val color = methods.getValue("A00").code()
        val hook = color.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == REEL_BUTTON_COLOR }
        assertEquals("the color's hook right before its return", color.size - 3, hook)
        assertEquals("the range form", Opcode.INVOKE_STATIC_RANGE, color[hook].opcode)
        val returned = (color.last() as OneRegisterInstruction).registerA
        assertEquals("the color returned handed over", returned, (color[hook] as RegisterRangeInstruction).startRegister)
        assertEquals("and written back", Opcode.MOVE_RESULT, color[hook + 1].opcode)
        assertEquals("into the same register", returned, (color[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("Instagram's color read kept", stock.getValue("A00"), color.filterIndexed { at, _ -> at != hook && at != hook + 1 }.map { it.opcode })

        for ((name, register) in listOf("A0M" to 3, "A0I" to 1)) {
            val body = methods.getValue(name).code()
            val sites = body.indices.filter { (body[it] as? ReferenceInstruction)?.reference?.toString() == REEL_BUTTON_FILTER }
            assertEquals("$name: one filter hook", 1, sites.size)
            val at = sites.single()
            assertEquals("$name: the range form", Opcode.INVOKE_STATIC_RANGE, body[at].opcode)
            assertEquals("$name: the filter argument handed over", register, (body[at] as RegisterRangeInstruction).startRegister)
            assertEquals("$name: written back", Opcode.MOVE_RESULT_OBJECT, body[at + 1].opcode)
            assertEquals("$name: into the same register", register, (body[at + 1] as OneRegisterInstruction).registerA)
            val built = (body[at + 2] as ReferenceInstruction).reference as MethodReference
            assertEquals("$name: right before the button's constructor", "<init>", built.name)
            assertEquals("$name: Instagram's builder kept", stock.getValue(name), body.filterIndexed { index, _ -> index != at && index != at + 1 }.map { it.opcode })
        }
        val gated = methods.getValue("A0M").code()
        val branch = gated.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the branch past the filter method lands on the hook",
            gated.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == REEL_BUTTON_FILTER }, branch.target.location.index)
        for (name in listOf("A01", "A0L", "A0p")) {
            assertEquals("$name: Instagram's code kept", stock.getValue(name), methods.getValue(name).code().map { it.opcode })
        }

        for ((case, classes) in listOf(
            "two filters" to listOf(reelColumn(filters = 2), extension()),
            "a filter reading no color of the column's" to listOf(reelColumn(readsColor = false), extension()),
            "no column" to listOf(reelColumn(render = false), extension()),
            "no button built with a filter" to listOf(reelColumn(buttons = false), extension()),
        )) {
            val untouched = PatchContexts.of(classes)
            untouched.tintReelButtons()
            val hooked = untouched.mutableClassDefBy(REEL_COLUMN).methods.filter { method ->
                method.code().any { (it as? ReferenceInstruction)?.reference?.toString() in setOf(REEL_BUTTON_COLOR, REEL_BUTTON_FILTER) }
            }
            assertTrue("$case: no hook", hooked.isEmpty())
        }
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(reelColumn(), extension(reelButtons = false))).tintReelButtons() }
        assertTrue("no hook in the extension: ${failure.message}", failure.message!!.startsWith("Accent color"))
    }

    /**
     * Every build of Instagram: the one class rendering a reel's button column has one color filter,
     * which reads one color of the column's own. That color goes to the extension at its return, in the
     * register returned, and every button the column builds with a color filter (at least the like,
     * comment, repost, share and save buttons) hands that filter to the extension right before its
     * constructor, in that argument's register, with Instagram's code kept (#187).
     */
    @Test
    fun everyBuildHandsItsReelButtonColorsToTheExtension() {
        for ((code, bundle) in builds()) {
            val columns = FixtureDex.classesLoadingAny(bundle, setOf("more_button")) { classDef ->
                classDef.methods.any { it.loadsLiteral(UFI_ID) }
            }.filter { classDef -> classDef.methods.any { it.parameterTypes.size == 1 && it.loadsLiteral(UFI_ID) } }
                .distinctBy { it.type }
            assertEquals("$code: one class renders the column, ${columns.map { it.type }}", 1, columns.size)
            val column = ImmutableClassDef.of(columns.single())
            val context = PatchContexts.of(listOf(column, extension()))
            val found = context.findReelButtonColors()
            assertTrue("$code: the column's colors found", found != null)
            context.tintReelButtons()
            val patched = context.mutableClassDefBy(column.type).methods

            val method = patched.single { it.returnType == "I" && it.parameterTypes.size == 1 && !AccessFlags.STATIC.isSet(it.accessFlags) }
            val body = method.code()
            val sites = body.indices.filter { (body[it] as? ReferenceInstruction)?.reference?.toString() == REEL_BUTTON_COLOR }
            val ends = body.indices.filter { body[it].opcode == Opcode.RETURN }
            assertEquals("$code: the color hook before each return", ends.map { it - 2 }, sites)
            for (at in sites) {
                val register = (body[at + 2] as OneRegisterInstruction).registerA
                assertEquals("$code: the color hook takes what's returned", register, (body[at] as RegisterRangeInstruction).startRegister)
                assertEquals("$code: and writes it back", register, (body[at + 1] as OneRegisterInstruction).registerA)
            }
            val stockColor = column.methods.single { it.name == method.name && it.returnType == "I" }.code().map { it.opcode }
            assertEquals("$code: Instagram's color read kept", stockColor.filter { it != Opcode.NOP },
                body.filterIndexed { at, _ -> at !in sites && at - 1 !in sites }.map { it.opcode }.filter { it != Opcode.NOP })

            var buttons = 0
            val built = mutableSetOf<String>()
            for (stock in column.methods) {
                if (stock.name == method.name && stock.returnType == "I") continue
                val before = stock.code()
                val constructors = before.filter { instruction ->
                    val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    called?.name == "<init>" && called.parameterTypes.count { it.toString() == FILTER } == 1
                }
                val after = patched.single { it.name == stock.name && it.parameterTypes.map(Any::toString) == stock.parameterTypes.map(Any::toString) }.code()
                val hooks = after.indices.filter { (after[it] as? ReferenceInstruction)?.reference?.toString() == REEL_BUTTON_FILTER }
                assertEquals("$code: ${stock.name} hooks each button it builds with a filter", constructors.size, hooks.size)
                for (at in hooks) {
                    val call = after[at + 2]
                    val called = (call as ReferenceInstruction).reference as MethodReference
                    val parameters = called.parameterTypes.map(Any::toString)
                    val slot = parameters.subList(0, parameters.indexOf(FILTER)).fold(1) { total, type -> total + if (type == "J" || type == "D") 2 else 1 }
                    val register = when (call) {
                        is RegisterRangeInstruction -> call.startRegister + slot
                        else -> (call as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG)[slot] }
                    }
                    assertEquals("$code: ${stock.name} hands over the filter argument", register, (after[at] as RegisterRangeInstruction).startRegister)
                    assertEquals("$code: ${stock.name} writes it back", register, (after[at + 1] as OneRegisterInstruction).registerA)
                    built += called.definingClass
                }
                buttons += hooks.size
                assertEquals("$code: ${stock.name} keeps Instagram's code", before.map { it.opcode }.filter { it != Opcode.NOP },
                    after.filterIndexed { at, _ -> at !in hooks && at - 1 !in hooks }.map { it.opcode }.filter { it != Opcode.NOP })
            }
            assertTrue("$code: the like, comment, repost, share and save buttons and more get the filter, $buttons calls of ${built.size} buttons",
                buttons >= 7 && built.size >= 5)
        }
    }

    /**
     * Litho's resource resolver hands the Context it keeps the theme of to the extension right before
     * its one return, once the theme is kept, so a tree built on a context the accent never reached
     * still gets the icon style before it reads its icon colors (#724). The branch to the throw keeps
     * no hook and Instagram's code is kept. Two such constructors or none leave Litho as it is, a
     * constructor reusing the Context's register or an extension without the hook refuses.
     */
    @Test
    fun lithosResolverHandsTheContextItKeepsTheThemeOfToTheExtension() {
        val stock = holder().methods.single().code()
        val context = PatchContexts.of(listOf(holder(), extension()))
        context.tintLithoIcons()
        val code = context.mutableClassDefBy(HOLDER).methods.single().code()
        val at = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == LITHO_ICONS }
        assertEquals("one hook", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == LITHO_ICONS })
        assertEquals("right after the theme is kept", Opcode.IPUT_OBJECT, code[at - 1].opcode)
        assertEquals("right before the return", Opcode.RETURN_VOID, code[at + 1].opcode)
        val hook = code[at] as RegisterRangeInstruction
        // Two locals, then this in v2 and the Context in v3.
        assertEquals("the Context, one register", listOf(3, 1), listOf(hook.startRegister, hook.registerCount))
        assertEquals("Instagram's code kept", stock.map { it.opcode }, code.filterIndexed { index, _ -> index != at }.map { it.opcode })

        for ((case, classes) in listOf(
            "two resolvers" to listOf(holder(), holder(type = "Lfixture/LithoResources2;"), extension()),
            "the theme not kept" to listOf(holder(keepsTheme = false), extension()),
        )) {
            val untouched = PatchContexts.of(classes)
            untouched.tintLithoIcons()
            val hooked = classes.filter { it.type != ACCENT_THEME }.flatMap { untouched.mutableClassDefBy(it.type).methods }
                .filter { method -> method.code().any { (it as? ReferenceInstruction)?.reference?.toString() == LITHO_ICONS } }
            assertTrue("$case: no hook", hooked.isEmpty())
        }
        PatchContexts.of(listOf(extension(lithoIcons = false))).tintLithoIcons()
        for ((case, classes) in listOf(
            "the Context's register reused" to listOf(holder(reuseContext = true), extension()),
            "no hook in the extension" to listOf(holder(), extension(lithoIcons = false)),
        )) {
            val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).tintLithoIcons() }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Accent color"))
        }
    }

    /**
     * Every build of Instagram: one constructor takes a Context first and keeps its theme, Litho's
     * resource resolver, and it hands that Context to the extension right before its return, with
     * Instagram's code around it (#724).
     */
    @Test
    fun everyBuildHandsLithosThemeContextToTheExtension() {
        for ((code, bundle) in builds()) {
            val keeps = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == THEME } }) { method ->
                method.name == "<init>" && method.parameterTypes.firstOrNull()?.toString() == CONTEXT &&
                    method.code().any { it.callsNamed(CONTEXT, "getTheme") } &&
                    method.code().any { instruction ->
                        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                        instruction.opcode == Opcode.IPUT_OBJECT && field != null && field.definingClass == method.definingClass && field.type == THEME
                    }
            }
            assertEquals("$code: one constructor keeps a Context's theme, ${keeps.map { it.definingClass }}", 1, keeps.size)
            val found = keeps.single()
            val context = PatchContexts.of(listOf(FixtureDex.classes(bundle, setOf(found.definingClass)).getValue(found.definingClass), extension()))
            context.tintLithoIcons()
            val method = context.mutableClassDefBy(found.definingClass).methods.single { it.name == "<init>" && it.parameterTypes.size == found.parameterTypes.size }
            val body = method.code()
            val hooks = body.indices.filter { (body[it] as? ReferenceInstruction)?.reference?.toString() == LITHO_ICONS }
            assertEquals("$code: one hook", 1, hooks.size)
            val at = hooks.single()
            assertEquals("$code: right before the return", Opcode.RETURN_VOID, body[at + 1].opcode)
            assertEquals("$code: the Context", method.parameterRegisterNumber(0), (body[at] as RegisterRangeInstruction).startRegister)
            assertEquals(
                "$code: Instagram's own code kept",
                found.code().map { it.opcode }.filter { it != Opcode.NOP },
                body.filterIndexed { index, _ -> index != at }.map { it.opcode }.filter { it != Opcode.NOP },
            )
        }
    }

    /**
     * The declared build decoded and encoded as Manager does: the link and primary button attributes
     * are among the uses, every resource keeps its number, and the patched build gains just the added
     * names, which read back as written.
     */
    @Test
    fun theDeclaredBuildGainsTheThemeResourcesAndKeepsEveryNumber() {
        val (code, bundle) = builds().entries.first { it.value.extension == "apks" }
        val work = Files.createTempDirectory("hushgram-accent-$code").toFile()
        try {
            val apk = File(work, "base.apk").also { base ->
                ZipFile(bundle).use { zip -> zip.getInputStream(zip.getEntry("base.apk")).use { input -> base.outputStream().use(input::copyTo) } }
            }
            val patched = File(work, "patched.apk")
            var listed = ""
            val before = decoded(apk, File(work, "patch")) { context ->
                val ids = ids(context.get("res"))
                listed = context.addAccentTheme()
                encoded(context).copyTo(patched)
                ids
            }
            val attributes = listed.split(" ")
            assertTrue("$code: the list, $listed", "igds_color_link" in attributes && "igds_color_primary_button_indigo" in attributes)
            assertEquals("$code: the list is one the stub takes", listed, requireAccentAttributes(listed))
            decoded(patched, File(work, "check")) { context ->
                val res = context.get("res")
                val after = ids(res)
                val added = (after.keys - before.keys).map { after.getValue(it) }.toSet()
                assertTrue("$code: numbers gone", (before.keys - after.keys).isEmpty())
                assertTrue("$code: no string added, ${added.filter { it.startsWith("string/") }}", added.none { it.startsWith("string/") })
                assertTrue("$code: a palette", "style/${paletteStyle(ACCENT_PALETTES.first())}" in added)
                assertTrue("$code: the link's dark style", "style/HushGramAccent_igds_color_link_link_on_dark_ff" in added)
                assertTrue("$code: the link's light style", "style/HushGramAccent_igds_color_link_link_on_light_ff" in added)
                assertTrue("$code: the Follow button's style", "style/HushGramAccent_igds_color_primary_button_indigo_fill_ff" in added)
                assertTrue("$code: only HushGram's names, ${added.take(5)}", added.all { it.substringAfter('/').lowercase().startsWith("hushgram") })
                val style = parse(File(res, "values/styles.xml").readText()).elements("style")
                    .single { it.getAttribute("name") == "HushGramAccent_igds_color_link_link_on_dark_ff" }
                assertEquals(
                    listOf("igds_color_link" to "?attr/hushgram_accent_link_on_dark_ff"),
                    style.elements("item").map { it.getAttribute("name") to it.textContent.trim() },
                )
                assertTrue("$code: the badge style", "style/${badgeStyle(ACCENT_PALETTES.first())}" in added)
                val badge = parse(File(res, "values/styles.xml").readText()).elements("style")
                    .single { it.getAttribute("name") == badgeStyle(ACCENT_PALETTES.first()) }
                assertEquals(
                    listOf(BADGE_ATTRIBUTE to "@color/hushgram_accent_purple_badge"),
                    badge.elements("item").map { it.getAttribute("name") to it.textContent.trim() },
                )
                // Material You's palette survives the encode, its colors naming the system palette on Android 12.
                assertTrue("$code: the wallpaper palette", "style/$WALLPAPER_PALETTE_STYLE" in added)
                assertTrue("$code: the wallpaper badge", "style/$WALLPAPER_BADGE_STYLE" in added)
                // Instagram declares its icon attribute, so every color gets its icon styles (#8).
                assertTrue("$code: the dark icons", "style/${iconStyle("PURPLE", IconTheme.DARK)}" in added)
                assertTrue("$code: the light icons", "style/${iconStyle("PURPLE", IconTheme.LIGHT)}" in added)
                assertTrue("$code: the wallpaper icons", "style/${iconStyle(WALLPAPER, IconTheme.DARK)}" in added)
                // And its primary text attributes, so every color gets its text styles too (#187).
                assertTrue("$code: the dark text", "style/${textStyle("PURPLE", IconTheme.DARK)}" in added)
                assertTrue("$code: the wallpaper text", "style/${textStyle(WALLPAPER, IconTheme.LIGHT)}" in added)
                val text = parse(File(res, "values/styles.xml").readText()).elements("style")
                    .single { it.getAttribute("name") == textStyle("PURPLE", IconTheme.DARK) }
                assertEquals(
                    "$code: the dark text's attributes",
                    TEXT_ATTRIBUTES.map { it to "@color/hushgram_accent_purple_icon_dark" }.toSet(),
                    text.elements("item").map { it.getAttribute("name") to it.textContent.trim() }.toSet(),
                )
                // And both gray text attributes, so every color gets its gray text styles (#8).
                assertTrue("$code: the dark gray text", "style/${subtextStyle("PURPLE", IconTheme.DARK)}" in added)
                assertTrue("$code: the wallpaper gray text", "style/${subtextStyle(WALLPAPER, IconTheme.LIGHT)}" in added)
                val subtext = parse(File(res, "values/styles.xml").readText()).elements("style")
                    .single { it.getAttribute("name") == subtextStyle("PURPLE", IconTheme.DARK) }
                assertEquals(
                    "$code: the dark gray text's attributes",
                    SUBTEXT_ATTRIBUTES.map { it to "@color/hushgram_accent_purple_subtext_dark" }.toSet(),
                    subtext.elements("item").map { it.getAttribute("name") to it.textContent.trim() }.toSet(),
                )
                // It declares the glyph attributes too, so the icon styles reach the heart, the action bars and the post buttons.
                // The encode puts a style's items in attribute number order.
                val icons = parse(File(res, "values/styles.xml").readText()).elements("style")
                    .single { it.getAttribute("name") == iconStyle("PURPLE", IconTheme.DARK) }
                assertEquals(
                    "$code: the dark icons' attributes",
                    (listOf(ICON_ATTRIBUTE) + GLYPH_ATTRIBUTES).map { it to "@color/hushgram_accent_purple_icon_dark" }.toSet(),
                    icons.elements("item").map { it.getAttribute("name") to it.textContent.trim() }.toSet(),
                )
                val system = parse(File(res, "values-v31/colors.xml").readText()).elements("color")
                    .associate { it.getAttribute("name") to it.textContent.trim() }
                assertEquals("$code: the wallpaper's fill", "@android:color/system_accent1_500", system["hushgram_accent_wallpaper_fill_ff"])
                assertEquals("$code: the wallpaper's dark link", "@android:color/system_accent1_200", system["hushgram_accent_wallpaper_link_on_dark_ff"])
                assertEquals("$code: the wallpaper's dark icons", "@android:color/system_accent1_200", system[iconColor(WALLPAPER, IconTheme.DARK)])
                // It declares the story gradient and the backgrounds, so every color gets those styles (#187).
                assertTrue("$code: the wallpaper rings", "style/${ringStyle(WALLPAPER)}" in added)
                assertTrue("$code: the light backgrounds", "style/${backgroundStyle("PURPLE", IconTheme.LIGHT)}" in added)
                assertTrue("$code: the wallpaper's backgrounds", "style/${backgroundStyle(WALLPAPER, IconTheme.DARK)}" in added)
                val written = parse(File(res, "values/styles.xml").readText()).elements("style")
                fun items(name: String) = written.single { it.getAttribute("name") == name }.elements("item")
                    .map { it.getAttribute("name") to it.textContent.trim() }.toSet()
                assertEquals(
                    "$code: the rings' attributes",
                    setOf(
                        "igds_color_gradient_yellow" to "@color/hushgram_accent_purple_ring_link_on_dark",
                        "igds_color_gradient_orange" to "@color/hushgram_accent_purple_ring_link_on_dark",
                        "igds_color_gradient_red" to "@color/hushgram_accent_purple_ring_fill",
                        "igds_color_gradient_pink" to "@color/hushgram_accent_purple_ring_fill",
                        "igds_color_gradient_purple" to "@color/hushgram_accent_purple_ring_pressed",
                        // The default story ring's deep end on 454 (#519).
                        "igds_color_gradient_lavender" to "@color/hushgram_accent_purple_ring_pressed",
                    ),
                    items(ringStyle("PURPLE")),
                )
                assertEquals(
                    "$code: the solid rings' attributes (#519)",
                    RING_ATTRIBUTES.keys.map { it to "@color/hushgram_accent_purple_ring_fill" }.toSet(),
                    items(solidRingStyle("PURPLE")),
                )
                assertTrue("$code: the wallpaper's solid rings", "style/${solidRingStyle(WALLPAPER)}" in added)
                assertEquals(
                    "$code: every background it was meant to reach",
                    BACKGROUND_ATTRIBUTES.map { (attribute, step) -> attribute to "@color/${backgroundColor("PURPLE", IconTheme.DARK, step)}" }.toSet(),
                    items(backgroundStyle("PURPLE", IconTheme.DARK)),
                )
                assertEquals("$code: the wallpaper's background", "@android:color/system_neutral2_900", system[backgroundColor(WALLPAPER, IconTheme.DARK, BackgroundStep.BASE)])
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun assertApplied(what: String, method: MutableMethod, returns: Int) {
        val code = method.code()
        val context = method.implementation!!.registerCount - 1
        val hooks = code.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == APPLY_THEME }
        assertEquals("$what: a hook per return", returns, hooks.size)
        for ((at, hook) in hooks) {
            assertEquals("$what: the hook's form", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
            assertEquals("$what: the hook passes the activity", context, (hook as RegisterRangeInstruction).startRegister)
            assertEquals("$what: a return follows the hook", Opcode.RETURN_VOID, code[at + 1].opcode)
        }
    }

    private fun builds(): Map<String, File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        return (declared + Fixtures.otherBuilds()).associateBy { file ->
            (if (file.extension == "apks") file.nameWithoutExtension else file.parentFile.name).substringAfterLast('-')
        }.toSortedMap()
    }

    private fun <T> decoded(apk: File, workspace: File, block: (ResourcePatchContext) -> T): T {
        val config = PatcherConfig(apkFile = apk, temporaryFilesPath = workspace)
        PatcherConfig::class.java.getMethod("setResourceMode\$morphe_patcher", ResourceMode::class.java)
            .invoke(config, ResourceMode.FULL)
        return Patcher(config).use { patcher ->
            val context = PatcherContext::class.java.getMethod("getResourceContext\$morphe_patcher")
                .invoke(patcher.context) as ResourcePatchContext
            ResourcePatchContext::class.java.getMethod("decodeResources\$morphe_patcher", ResourceMode::class.java)
                .invoke(context, ResourceMode.FULL)
            block(context)
        }
    }

    private fun encoded(context: ResourcePatchContext): File {
        val result = ResourcePatchContext::class.java.getMethod("get").invoke(context) as PatcherResult.PatchedResources
        return requireNotNull(result.resourcesApk) { "the patcher encoded no resources APK" }
    }

    private fun ids(res: File): Map<String, String> =
        parse(File(res, "values/public.xml").readText()).elements("public")
            .associate { "${it.getAttribute("type")}@${it.getAttribute("id")}" to "${it.getAttribute("type")}/${it.getAttribute("name")}" }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.trimIndent().byteInputStream())

    private fun Document.elements(tag: String): List<Element> = documentElement.elements(tag)

    private fun Element.elements(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.callsNamed(definingClass: String, name: String): Boolean =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == definingClass && it.name == name } == true

    private companion object {
        const val CONTEXT = "Landroid/content/Context;"
        const val THEME = "Landroid/content/res/Resources\$Theme;"
        const val OVERRIDES = "Lfixture/ThemeOverrides;"
        const val UTIL = "Lfixture/ThemeUtil;"

        /** IgFragmentActivity's theme setup: one style, then the overrides on a static instance. */
        fun activity(secondStyle: Boolean = false, callsOverrides: Boolean = true): ClassDef {
            val body = buildString {
                appendLine("const v2, 0x7f1402ae")
                appendLine("invoke-virtual { p0 }, $CONTEXT->getTheme()$THEME")
                appendLine("move-result-object v1")
                appendLine("const/4 v0, 0x1")
                appendLine("invoke-virtual { v1, v2, v0 }, $THEME->applyStyle(IZ)V")
                if (secondStyle) appendLine("invoke-virtual { v1, v2, v0 }, $THEME->applyStyle(IZ)V")
                if (callsOverrides) {
                    appendLine("sget-object v0, $OVERRIDES->A00:$OVERRIDES")
                    appendLine("invoke-virtual { v0, p0 }, $OVERRIDES->A04($CONTEXT)V")
                }
                appendLine("return-void")
            }
            return classOf(IG_FRAGMENT_ACTIVITY, method(IG_FRAGMENT_ACTIVITY, "A1j", emptyList(), 4, body, static = false))
        }

        /** Prism's overrides: [styles] styles, a branch to a second return-void, and the context read throughout. */
        fun overrides(styles: Int = 3, reuseContext: Boolean = false): ClassDef {
            val body = buildString {
                appendLine("const/4 v2, 0x1")
                repeat(styles) {
                    appendLine("const v1, 0x7f14029${it}")
                    appendLine("invoke-virtual { p1 }, $CONTEXT->getTheme()$THEME")
                    appendLine("move-result-object v0")
                    appendLine("invoke-virtual { v0, v1, v2 }, $THEME->applyStyle(IZ)V")
                }
                if (reuseContext) appendLine("const/4 p1, 0x0")
                appendLine("if-eqz v2, :done")
                appendLine("return-void")
                appendLine(":done")
                appendLine("return-void")
            }
            return classOf(OVERRIDES, method(OVERRIDES, "A04", listOf(CONTEXT), 3, body, static = false))
        }

        /** Instagram's theme util: its styles onto the context's theme, or the line it logs, then one return a goto reaches. */
        fun util(type: String = UTIL, applies: Boolean = true, reuseContext: Boolean = false): ClassDef {
            val body = buildString {
                appendLine("const/4 v2, 0x1")
                appendLine("const v1, 0x7f1402a0")
                appendLine("if-eqz v1, :missing")
                if (reuseContext) appendLine("const/4 p0, 0x0")
                appendLine("invoke-virtual { p0 }, $CONTEXT->getTheme()$THEME")
                appendLine("move-result-object v0")
                if (applies) appendLine("invoke-virtual { v0, v1, v2 }, $THEME->applyStyle(IZ)V")
                appendLine("goto :done")
                appendLine(":missing")
                appendLine("const-string v0, \"IgThemeUtil\"")
                appendLine("const-string v1, \"$THEME_UTIL_FAILURE\"")
                appendLine("invoke-static { v0, v1 }, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I")
                appendLine(":done")
                appendLine("return-void")
            }
            return classOf(type, method(type, "A01", listOf(CONTEXT), 3, body, static = true))
        }

        /** AccentTheme's hooks, and the attribute and custom color stubs as javac and D8 leave `return null`. */
        fun extension(
            stub: Boolean = true,
            headerIcons: Boolean = true,
            headerIcon: Boolean = true,
            customStub: Boolean = true,
            tabIcons: Boolean = true,
            lithoIcons: Boolean = true,
            reelButtons: Boolean = true,
        ): ClassDef = classOf(
            ACCENT_THEME,
            *listOfNotNull(
                method(ACCENT_THEME, "apply", listOf(CONTEXT), 0, "return-void", static = true),
                if (stub) method(ACCENT_THEME, "attributes", emptyList(), 1, "const/4 v0, 0x0\nreturn-object v0", static = true, returns = "Ljava/lang/String;") else null,
                if (customStub) method(ACCENT_THEME, "customColors", emptyList(), 1, "const/4 v0, 0x0\nreturn-object v0", static = true, returns = "Ljava/lang/String;") else null,
                if (headerIcons) method(ACCENT_THEME, "headerIcons", listOf(CONTEXT, "I"), 0, "return p1", static = true, returns = "I") else null,
                if (headerIcon) method(ACCENT_THEME, "headerIcon", listOf(CONTEXT, DRAWABLE), 0, "return-void", static = true) else null,
                if (tabIcons) method(ACCENT_THEME, "tabIcons", listOf("I"), 0, "return p0", static = true, returns = "I") else null,
                if (tabIcons) method(ACCENT_THEME, "tabIcons", listOf(BOXED), 0, "return-object p0", static = true, returns = BOXED) else null,
                if (lithoIcons) method(ACCENT_THEME, "lithoIcons", listOf(CONTEXT), 0, "return-void", static = true) else null,
                if (reelButtons) method(ACCENT_THEME, "reelButtons", listOf("I"), 0, "return p0", static = true, returns = "I") else null,
                if (reelButtons) method(ACCENT_THEME, "reelButtons", listOf(FILTER), 0, "return-object p0", static = true, returns = FILTER) else null,
            ).toTypedArray(),
        )

        const val FILTER = "Landroid/graphics/ColorFilter;"
        const val REEL_COLUMN = "Lfixture/ReelColumn;"
        const val REEL_SCOPE = "Lfixture/ReelScope;"

        /**
         * A reel's button column as 456.1's `LX/0FBY` has it: its render loading the share count's id,
         * the color read that picks the theme's text color on media or, in the look under the video, its
         * text and icon color, the filter built from that color in that look and none over the video, and
         * with [buttons] three builders: A0M asking the filter only in that other look and building its
         * button with the range form, the filter its second argument and the branch past the filter method
         * landing on the call, A0I asking the filter every time and passing it first, and A0L passing one
         * null register as both the filter and another argument. [filters] such filters, with [readsColor]
         * false one asking another class's color, and with [render] false no render loading the id.
         */
        fun reelColumn(filters: Int = 1, readsColor: Boolean = true, render: Boolean = true, buttons: Boolean = true): ClassDef {
            val renderBody = if (render) "const v0, 0x${UFI_ID.toString(16)}\nconst/4 v0, 0x0\nreturn-object v0" else "const/4 v0, 0x0\nreturn-object v0"
            val color = """
                iget-boolean v0, p0, $REEL_COLUMN->A0A:Z
                const v1, 0x7f040866
                if-eqz v0, :read
                const v1, 0x7f0408ce
                :read
                invoke-static { p1, v1 }, Lfixture/ReelColors;->A06(Ljava/lang/Object;I)I
                move-result v0
                return v0
            """.trimIndent()
            val asks = if (readsColor) "$REEL_COLUMN->A00($REEL_SCOPE)I" else "Lfixture/OtherColumn;->A00($REEL_SCOPE)I"
            val filter = """
                iget-boolean v0, p0, $REEL_COLUMN->A0A:Z
                if-eqz v0, :none
                invoke-virtual { p0, p1 }, $asks
                move-result v0
                invoke-static { v0 }, Lfixture/ReelFilters;->A00(I)$FILTER
                move-result-object v0
                return-object v0
                :none
                const/4 v0, 0x0
                return-object v0
            """.trimIndent()
            val share = """
                new-instance v1, Lfixture/ShareButton;
                const/4 v2, 0x0
                const/4 v3, 0x0
                const-wide/16 v4, 0x0
                iget-boolean v0, p0, $REEL_COLUMN->A0A:Z
                if-eqz v0, :build
                invoke-virtual { p0, p1 }, $REEL_COLUMN->A01($REEL_SCOPE)$FILTER
                move-result-object v3
                :build
                invoke-direct/range { v1 .. v5 }, Lfixture/ShareButton;-><init>(Landroid/animation/Animator;${FILTER}J)V
                return-object v1
            """.trimIndent()
            val comment = """
                invoke-virtual { p0, p1 }, $REEL_COLUMN->A01($REEL_SCOPE)$FILTER
                move-result-object v1
                new-instance v0, Lfixture/CommentButton;
                invoke-direct { v0, v1 }, Lfixture/CommentButton;-><init>($FILTER)V
                return-object v0
            """.trimIndent()
            val save = """
                const/4 v1, 0x0
                new-instance v0, Lfixture/SaveButton;
                invoke-direct { v0, v1, v1 }, Lfixture/SaveButton;-><init>(${FILTER}Ljava/lang/Object;)V
                return-object v0
            """.trimIndent()
            val methods = listOf(
                method(REEL_COLUMN, "A0p", listOf(REEL_SCOPE), 1, renderBody, static = false, returns = "Ljava/lang/Object;"),
                method(REEL_COLUMN, "A00", listOf(REEL_SCOPE), 2, color, static = false, returns = "I"),
            ) + (0 until filters).map { method(REEL_COLUMN, "A0" + (1 + it), listOf(REEL_SCOPE), 1, filter, static = false, returns = FILTER) } +
                if (buttons) listOf(
                    method(REEL_COLUMN, "A0M", listOf(REEL_SCOPE), 6, share, static = false, returns = "Ljava/lang/Object;"),
                    method(REEL_COLUMN, "A0I", listOf(REEL_SCOPE), 2, comment, static = false, returns = "Ljava/lang/Object;"),
                    method(REEL_COLUMN, "A0L", listOf(REEL_SCOPE), 2, save, static = false, returns = "Ljava/lang/Object;"),
                ) else emptyList()
            return classOf(REEL_COLUMN, *methods.toTypedArray())
        }

        const val HOLDER = "Lfixture/LithoResources;"
        const val CONFIG = "Lfixture/LithoConfig;"
        const val RESOURCES = "Landroid/content/res/Resources;"

        /**
         * Litho's resource resolver as 456's `LX/00b9` has it: the Context and the config kept, then the
         * Context's Resources and, with [keepsTheme], its theme, each checked for null on the way to the
         * one return. With [reuseContext] the Context's register is written over before the return.
         */
        fun holder(type: String = HOLDER, keepsTheme: Boolean = true, reuseContext: Boolean = false): ClassDef {
            val body = buildString {
                appendLine("invoke-direct { p0 }, Ljava/lang/Object;-><init>()V")
                appendLine("iput-object p1, p0, $type->A00:$CONTEXT")
                appendLine("iput-object p2, p0, $type->A03:$CONFIG")
                appendLine("invoke-virtual { p1 }, $CONTEXT->getResources()$RESOURCES")
                appendLine("move-result-object v0")
                appendLine("if-eqz v0, :missing")
                appendLine("iput-object v0, p0, $type->A02:$RESOURCES")
                appendLine("invoke-virtual { p1 }, $CONTEXT->getTheme()$THEME")
                appendLine("move-result-object v0")
                appendLine("if-eqz v0, :missing")
                if (reuseContext) appendLine("const/4 p1, 0x0")
                if (keepsTheme) appendLine("iput-object v0, p0, $type->A01:$THEME")
                appendLine("return-void")
                appendLine(":missing")
                appendLine("const-string v0, \"INVOKE_RETURN\"")
                appendLine("new-instance v1, Ljava/lang/IllegalStateException;")
                appendLine("invoke-direct { v1, v0 }, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V")
                appendLine("throw v1")
            }
            val fields = listOf("A00" to CONTEXT, "A01" to THEME, "A02" to RESOURCES, "A03" to CONFIG).map { (name, fieldType) ->
                ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)
            }
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields,
                listOf(method(type, "<init>", listOf(CONTEXT, CONFIG), 2, body, static = false)),
            )
        }

        const val BOXED = "Ljava/lang/Integer;"
        const val TAB_BUTTON = "Lfixture/TabButton;"

        /**
         * A tab button's recolor, as 454's `LX/02a0;->A0A` has it: the icon view found and told the int
         * selected, then the boxed color's value unselected when there is one. [methods] of them, and with
         * [jumpOnto] a branch back to the start.
         */
        fun tabButton(methods: Int = 1, jumpOnto: Boolean = false): ClassDef {
            val body = buildString {
                if (jumpOnto) appendLine(":start")
                appendLine("const v0, 0x7f0b4058")
                appendLine("invoke-virtual { p0, v0 }, Landroid/view/View;->findViewById(I)Landroid/view/View;")
                appendLine("move-result-object v1")
                appendLine("check-cast v1, $TAB_ICON_VIEW")
                appendLine("invoke-virtual { v1, p1 }, $TAB_ICON_VIEW->setActiveColor(I)V")
                appendLine("if-eqz p2, :done")
                appendLine("invoke-virtual { p2 }, Ljava/lang/Number;->intValue()I")
                appendLine("move-result v0")
                appendLine("invoke-virtual { v1, v0 }, $TAB_ICON_VIEW->setNormalColor(I)V")
                if (jumpOnto) appendLine("if-eqz v0, :start")
                appendLine(":done")
                appendLine("return-void")
            }
            val recolors = (0 until methods).map { method(TAB_BUTTON, "A0" + ('A' + it), listOf("I", BOXED), 2, body, static = false) }
            return classOf(TAB_BUTTON, *recolors.toTypedArray(), superclass = "Landroid/view/View;")
        }

        const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
        const val ICON = "Lfixture/HeaderIcon;"
        const val NOT_AN_ICON = "Lfixture/HeaderState;"

        /** Instagram's header icon, a drawable built from a Context and a resource, or [type] built the same way. */
        fun icon(type: String = ICON, superclass: String = DRAWABLE): ClassDef = classOf(
            type,
            method(type, "<init>", listOf(CONTEXT, "I"), 0, "invoke-direct { p0 }, $superclass-><init>()V\nreturn-void", static = false),
            superclass = superclass,
        )

        /**
         * Home's older header: its heart built in A07, and in A08 a state built from the same Context and
         * resource, then either the Create button's layers or a plain button's icon, which jumps back to
         * the one setImageDrawable.
         */
        fun olderHeader(): ClassDef {
            val heart = """
                invoke-virtual { p1 }, Landroid/view/View;->getContext()$CONTEXT
                move-result-object v1
                new-instance v0, $ICON
                invoke-direct { v0, v1, p2 }, $ICON-><init>(${CONTEXT}I)V
                invoke-virtual { p1, v0 }, Landroid/widget/ImageView;->setImageDrawable($DRAWABLE)V
                return-void
            """.trimIndent()
            val buttons = """
                new-instance v1, $NOT_AN_ICON
                invoke-direct { v1, p1, p3 }, $NOT_AN_ICON-><init>(${CONTEXT}I)V
                if-eqz p3, :icon
                invoke-static { p1 }, Lfixture/Layers;->A00($CONTEXT)Landroid/graphics/drawable/LayerDrawable;
                move-result-object v0
                :set
                invoke-virtual { p2, v0 }, Landroid/widget/ImageView;->setImageDrawable($DRAWABLE)V
                return-void
                :icon
                new-instance v0, $ICON
                invoke-direct { v0, p1, p3 }, $ICON-><init>(${CONTEXT}I)V
                goto :set
            """.trimIndent()
            return classOf(
                MAIN_FEED_ACTION_BAR,
                method(MAIN_FEED_ACTION_BAR, "A07", listOf("Landroid/widget/ImageView;", "I"), 2, heart, static = false),
                method(MAIN_FEED_ACTION_BAR, "A08", listOf(CONTEXT, "Landroid/widget/ImageView;", "I"), 2, buttons, static = false),
            )
        }

        /**
         * Home's newer header: its constructor keeps the icons' color from Context.getColor, [stored]
         * times, and the color over media from a helper, or from getColor too with [overMediaRead] as
         * 32-bit builds do. Its tint reads the color over media on the fall through of its test of the
         * state and the icons' color where that test lands, then goes back to one setColorFilter.
         */
        fun header(stored: Int = 1, overMediaRead: Boolean = false, jumpOnto: Boolean = false, outlined: Boolean = false): ClassDef {
            val init = buildString {
                appendLine("invoke-direct { p0 }, Ljava/lang/Object;-><init>()V")
                if (jumpOnto) appendLine("if-eqz p2, :store")
                repeat(stored) {
                    if (outlined) {
                        appendLine("invoke-static { p1 }, $ICON_COLORS->A03($CONTEXT)I")
                    } else {
                        appendLine("const v0, 0x7f060001")
                        appendLine("invoke-virtual { p1, v0 }, $CONTEXT->getColor(I)I")
                    }
                    appendLine("move-result v0")
                    if (it == 0) appendLine(":store")
                    appendLine("iput v0, p0, $MERGED_LOGO_BAR->A0W:I")
                }
                if (overMediaRead) {
                    appendLine("const v0, 0x7f060002")
                    appendLine("invoke-virtual { p1, v0 }, $CONTEXT->getColor(I)I")
                } else {
                    appendLine("invoke-static { p1 }, Lfixture/Colors;->A0X($CONTEXT)I")
                }
                appendLine("move-result v0")
                appendLine("iput v0, p0, $MERGED_LOGO_BAR->A0X:I")
                appendLine("return-void")
            }
            val tint = """
                if-eqz p2, :icons
                iget v0, p0, $MERGED_LOGO_BAR->A0X:I
                :tint
                invoke-virtual { p1, v0 }, Landroid/widget/ImageView;->setColorFilter(I)V
                return-void
                :icons
                iget v0, p0, $MERGED_LOGO_BAR->A0W:I
                goto :tint
            """.trimIndent()
            return classOf(
                MERGED_LOGO_BAR,
                method(MERGED_LOGO_BAR, "<init>", listOf(CONTEXT, "Landroid/util/AttributeSet;", "I"), 1, init, static = false),
                method(MERGED_LOGO_BAR, "A07", listOf("Landroid/widget/ImageView;", "I"), 1, tint, static = false),
            )
        }

        const val ICON_COLORS = "Lfixture/IconColors;"

        /**
         * The static 454 outlines the header's color read into (`LX/0BPb;->A03` on 386014704): it asks
         * another static for the resource on its Context and answers Context.getColor of it, or with
         * [getColor] false the resource itself.
         */
        fun iconColors(getColor: Boolean = true): ClassDef {
            val read = if (getColor) "invoke-virtual { p0, v0 }, $CONTEXT->getColor(I)I\nmove-result v0\n" else ""
            val body = "invoke-static { p0 }, $ICON_COLORS->A0T($CONTEXT)I\nmove-result v0\n${read}return v0"
            return classOf(ICON_COLORS, method(ICON_COLORS, "A03", listOf(CONTEXT), 1, body, static = true, returns = "I"))
        }

        fun method(type: String, name: String, parameters: List<String>, locals: Int, body: String, static: Boolean, returns: String = "V"): Method {
            val registers = locals + parameters.size + if (static) 0 else 1
            return ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                        AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trim()) },
            )
        }

        fun classOf(type: String, vararg methods: Method, superclass: String = "Ljava/lang/Object;"): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, methods.toList(),
        )
    }
}
