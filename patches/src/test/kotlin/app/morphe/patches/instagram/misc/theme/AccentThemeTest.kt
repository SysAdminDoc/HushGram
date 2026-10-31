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
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
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
            .filter { it[0] != "BLUE" && it[0] != WALLPAPER }
            .map { (name, fill, light, dark) -> listOf(name, fill, light, dark) }
            .toList()
        assertEquals(
            hues,
            ACCENT_PALETTES.map { palette ->
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
        assertTrue("AccentHue lists the wallpaper", Regex("""^\s+$WALLPAPER\(0, 0, 0\);""", RegexOption.MULTILINE)
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

    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val declared = app.morphe.ExtensionDex.classDef(ACCENT_THEME).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .associateBy { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$APPLY_THEME is not in the extension: ${declared.keys}", APPLY_THEME in declared)
        val stub = declared[ATTRIBUTES_STUB]
        assertTrue("$ATTRIBUTES_STUB is not in the extension: ${declared.keys}", stub != null)
        assertTrue("the stub has a register to write into", stub!!.implementation!!.registerCount >= 1)
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

        /** AccentTheme's hook, and the attribute stub as javac and D8 leave `return null`. */
        fun extension(stub: Boolean = true): ClassDef = classOf(
            ACCENT_THEME,
            *listOfNotNull(
                method(ACCENT_THEME, "apply", listOf(CONTEXT), 0, "return-void", static = true),
                if (stub) method(ACCENT_THEME, "attributes", emptyList(), 1, "const/4 v0, 0x0\nreturn-object v0", static = true, returns = "Ljava/lang/String;") else null,
            ).toTypedArray(),
        )

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

        fun classOf(type: String, vararg methods: Method): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList(),
        )
    }
}
