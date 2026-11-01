/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherContext
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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

private const val FAKE_THEME = "Lfixture/Theme;"

class PureBlackTest {
    private val bottomTabTint = "Lfixture/BottomTabTint;"
    private val extension = "Lapp/hushgram/extension/instagram/settings/Colors;"

    /**
     * A background Prism's black fills goes to the stock pure black; the light theme's text, another
     * color and the sheet's gray, which blackenSheets owns, stay.
     */
    @Test
    fun theStylesGetPureBlack() {
        val styles = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            """
                <resources>
                    <style name="IgdsPrismGrayOverridesDark" parent="IgdsPrismSemanticColorsDark">
                        <item name="igds_color_primary_background">@color/igds_prism_black</item>
                        <item name="status_bar_background">@color/igds_prism_black</item>
                        <item name="igds_color_elevated_background">@color/igds_prism_gray_1500</item>
                    </style>
                    <style name="IgdsPrismSemanticColorsLight">
                        <item name="igds_color_primary_text">@color/igds_prism_black</item>
                        <item name="igds_color_media_background">@color/igds_prism_black</item>
                    </style>
                    <style name="IgdsSemanticColorsLight">
                        <item name="igds_color_primary_background">@color/bds_white</item>
                        <item name="igds_color_form_field_background_focussed_color">@color/igds_prism_black</item>
                    </style>
                </resources>
            """.trimIndent().byteInputStream(),
        )

        assertEquals(listOf("IgdsPrismGrayOverridesDark", "IgdsPrismSemanticColorsLight"), blackenStyles(styles))

        val items = styles.getElementsByTagName("item").let { list ->
            (0 until list.length).map { list.item(it) as Element }.map { it.getAttribute("name") to it.textContent }
        }
        assertEquals(
            listOf(
                "igds_color_primary_background" to PURE_BLACK_COLOR,
                "status_bar_background" to PURE_BLACK_COLOR,
                "igds_color_elevated_background" to "@color/igds_prism_gray_1500",
                "igds_color_primary_text" to PRISM_BLACK_COLOR,
                "igds_color_media_background" to PURE_BLACK_COLOR,
                "igds_color_primary_background" to "@color/bds_white",
                "igds_color_form_field_background_focussed_color" to PRISM_BLACK_COLOR,
            ),
            items,
        )
        assertEquals("a second pass finds nothing", emptyList<String>(), blackenStyles(styles))
    }

    /**
     * The sheet's surface goes pure black in each dark style that sets it (#171). What sits on a
     * sheet, the surfaces Instagram keeps dark in both themes, the light theme's white sheet and
     * another screen's dark style stay.
     */
    @Test
    fun theDarkSheetsGetPureBlack() {
        val styles = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            """
                <resources>
                    <style name="IgdsPrismSemanticColorsExperimentDark" parent="IgdsSemanticColorsDark">
                        <item name="igds_color_elevated_background">@color/igds_prism_gray_10</item>
                        <item name="igds_color_secondary_background">@color/igds_prism_gray_09</item>
                    </style>
                    <style name="IgdsPrismGrayOverridesDark">
                        <item name="igds_color_elevated_background">@color/igds_prism_gray_1500</item>
                        <item name="igds_color_elevated_highlight_background">@color/igds_prism_gray_08</item>
                        <item name="igds_color_elevated_separator">@color/igds_prism_gray_08</item>
                        <item name="igds_color_secondary_background_on_media">@color/igds_prism_gray_1500</item>
                    </style>
                    <style name="IgdsElevatedBackgroundFixDark">
                        <item name="igds_color_elevated_background">@color/igds_prism_gray_1500</item>
                        <item name="igds_color_elevated_background_dark">@color/igds_prism_gray_1500</item>
                        <item name="igds_color_elevated_background_dark_pressed">@color/igds_prism_gray_09</item>
                    </style>
                    <style name="IgdsElevatedBackgroundFixLight">
                        <item name="igds_color_elevated_background_dark">@color/igds_prism_gray_1500</item>
                    </style>
                    <style name="IgdsPrismSemanticColorsExperimentLight">
                        <item name="igds_color_elevated_background">@color/abc_decor_view_status_guard_light</item>
                    </style>
                    <style name="StoryCommentColorsDark" parent="IgdsSemanticColorsDark">
                        <item name="igds_color_elevated_background">@color/bds_grey_9_95_transparent</item>
                    </style>
                </resources>
            """.trimIndent().byteInputStream(),
        )

        assertEquals(DARK_SHEETS, blackenSheets(styles))

        val items = styles.getElementsByTagName("item").let { list ->
            (0 until list.length).map { list.item(it) as Element }.map { it.getAttribute("name") to it.textContent }
        }
        assertEquals(
            listOf(
                "igds_color_elevated_background" to PURE_BLACK_COLOR,
                "igds_color_secondary_background" to "@color/igds_prism_gray_09",
                "igds_color_elevated_background" to PURE_BLACK_COLOR,
                "igds_color_elevated_highlight_background" to "@color/igds_prism_gray_08",
                "igds_color_elevated_separator" to "@color/igds_prism_gray_08",
                "igds_color_secondary_background_on_media" to "@color/igds_prism_gray_1500",
                "igds_color_elevated_background" to PURE_BLACK_COLOR,
                "igds_color_elevated_background_dark" to "@color/igds_prism_gray_1500",
                "igds_color_elevated_background_dark_pressed" to "@color/igds_prism_gray_09",
                "igds_color_elevated_background_dark" to "@color/igds_prism_gray_1500",
                "igds_color_elevated_background" to "@color/abc_decor_view_status_guard_light",
                "igds_color_elevated_background" to "@color/bds_grey_9_95_transparent",
            ),
            items,
        )
        assertEquals("a second pass finds nothing", emptyList<String>(), blackenSheets(styles))
    }

    /**
     * The story viewer's gray goes pure black in both dark styles that set it (#279). The light
     * theme's style, a story's other grays and the sheets, which blackenSheets owns, stay.
     */
    @Test
    fun theDarkStoriesGetPureBlack() {
        val styles = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            """
                <resources>
                    <style name="IgdsPrismGrayOverridesDark" parent="IgdsPrismSemanticColorsDark">
                        <item name="igds_color_stories_loading_background">@color/igds_prism_gray_09</item>
                        <item name="igds_color_elevated_background">@color/igds_prism_gray_1500</item>
                    </style>
                    <style name="IgdsPrismSemanticColorsExperimentDark" parent="IgdsSemanticColorsDark">
                        <item name="igds_color_creation_tools_grey_09">@color/igds_prism_gray_09</item>
                        <item name="igds_color_stories_loading_background">@color/igds_prism_gray_09</item>
                    </style>
                    <style name="IgdsPrismSemanticColorsExperimentLight">
                        <item name="igds_color_stories_loading_background">@color/igds_prism_gray_09</item>
                    </style>
                    <style name="IgdsSemanticColorsDark">
                        <item name="igds_color_stories_loading_background">@color/bds_grey_9</item>
                    </style>
                </resources>
            """.trimIndent().byteInputStream(),
        )

        assertEquals(listOf("IgdsPrismGrayOverridesDark", "IgdsPrismSemanticColorsExperimentDark"), blackenStories(styles))

        val items = styles.getElementsByTagName("item").let { list ->
            (0 until list.length).map { list.item(it) as Element }.map { it.getAttribute("name") to it.textContent }
        }
        assertEquals(
            listOf(
                "igds_color_stories_loading_background" to PURE_BLACK_COLOR,
                "igds_color_elevated_background" to "@color/igds_prism_gray_1500",
                "igds_color_creation_tools_grey_09" to "@color/igds_prism_gray_09",
                "igds_color_stories_loading_background" to PURE_BLACK_COLOR,
                "igds_color_stories_loading_background" to "@color/igds_prism_gray_09",
                "igds_color_stories_loading_background" to "@color/bds_grey_9",
            ),
            items,
        )
        assertEquals("a second pass finds nothing", emptyList<String>(), blackenStories(styles))
    }

    /**
     * A Likes row's normal fill goes pure black (#248), in the form a decoded file may spell it, and
     * its pressed fill keeps its own attribute so a press still shows.
     */
    @Test
    fun theLikesRowGetsPureBlackAndKeepsItsPress() {
        for (spelled in listOf("?$ROW_BACKGROUND", "?attr/$ROW_BACKGROUND")) {
            val row = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                """
                    <selector xmlns:android="http://schemas.android.com/apk/res/android">
                        <item android:state_pressed="true">
                            <shape><solid android:color="?${ROW_BACKGROUND}_pressed"/></shape>
                        </item>
                        <item>
                            <shape><solid android:color="$spelled"/></shape>
                        </item>
                    </selector>
                """.trimIndent().byteInputStream(),
            )

            assertEquals(1, blackenRow(row))

            val solids = row.getElementsByTagName("solid").let { list ->
                (0 until list.length).map { (list.item(it) as Element).getAttribute("android:color") }
            }
            assertEquals(listOf("?${ROW_BACKGROUND}_pressed", PURE_BLACK_COLOR), solids)
            assertEquals("a second pass finds nothing", 0, blackenRow(row))
        }
    }

    /**
     * The dark launch theme's gray goes pure black wherever it names the splash color (#410), its
     * icon and branding stay, and another style naming the same color is left alone.
     */
    @Test
    fun theDarkLaunchScreenGetsPureBlack() {
        val styles = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            """
                <resources>
                    <style name="$INSTAGRAM_LAUNCH_THEME" parent="Theme.Instagram">
                        <item name="android:windowSplashScreenBackground">$SPLASH_BACKGROUND_COLOR</item>
                        <item name="android:windowSplashScreenAnimatedIcon">@drawable/instagram_padded</item>
                        <item name="android:windowSplashScreenBrandingImage">@drawable/from_meta_gradient_padded</item>
                        <item name="android:windowSplashScreenIconBackgroundColor">$SPLASH_BACKGROUND_COLOR</item>
                    </style>
                    <style name="Theme.Instagram.Other">
                        <item name="android:windowBackground">$SPLASH_BACKGROUND_COLOR</item>
                    </style>
                </resources>
            """.trimIndent().byteInputStream(),
        )

        assertEquals(2, blackenLaunchScreen(styles))

        val items = styles.getElementsByTagName("item").let { list ->
            (0 until list.length).map { list.item(it) as Element }.map { it.textContent }
        }
        assertEquals(
            listOf(PURE_BLACK_COLOR, "@drawable/instagram_padded", "@drawable/from_meta_gradient_padded", PURE_BLACK_COLOR, SPLASH_BACKGROUND_COLOR),
            items,
        )
        assertEquals("a second pass finds nothing", 0, blackenLaunchScreen(styles))
        assertTrue(isNightValues("values-night") && isNightValues("values-night-v31"))
        assertTrue("the light theme's folders", listOf("values", "values-v31", "values-ne").none(::isNightValues))
    }

    /**
     * On every build of Instagram 452, decoded as Manager decodes it: the three dark styles hold
     * a sheet's gray (on the two overlays the #ff191c1f a reel's menu showed, #171) and lose it to
     * pure black, and every other style that sets a sheet's color, the light theme's among them,
     * keeps the one it had. The two dark styles that set the story viewer's #ff25292e (#279) lose
     * it the same way, and the light theme's stories keep it.
     */
    @Test
    fun eachBuildsDarkSheetsGoPureBlackAndEveryOtherSheetStays() {
        val dark = DARK_SHEETS.map { "values/$it" }
        val darkStories = DARK_STORIES.map { "values/$it" }
        val light = "values/IgdsPrismSemanticColorsExperimentLight"
        val builds = resourceBuilds()
        assertEquals("every build of Instagram ${Fixtures.VERSION} has a fixture", Fixtures.BUILDS, builds.size)
        for ((code, build) in builds) {
            val work = Files.createTempDirectory("hushgram-black-$code").toFile()
            try {
                val apk = if (build.extension == "apks") File(work, "base.apk").also { base ->
                    ZipFile(build).use { bundle ->
                        bundle.getInputStream(bundle.getEntry("base.apk")).use { input -> base.outputStream().use(input::copyTo) }
                    }
                } else build
                decoded(apk, File(work, "patch")) { context ->
                    val res = context.get("res")
                    assertTrue(
                        "$code: the sheet's gray is #ff191c1f",
                        Regex("""<color name="igds_prism_gray_1500">#(ff)?191c1f</color>""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(File(res, "values/colors.xml").readText()),
                    )
                    assertTrue(
                        "$code: the story viewer's gray is #ff25292e",
                        Regex("""<color name="igds_prism_gray_09">#(ff)?25292e</color>""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(File(res, "values/colors.xml").readText()),
                    )
                    val before = attributeColors(res, SHEET_BACKGROUND)
                    val storiesBefore = attributeColors(res, STORY_BACKGROUND)
                    assertEquals(
                        "$code: the dark stories' gray",
                        darkStories.associateWith { "@color/igds_prism_gray_09" },
                        storiesBefore.filterKeys { it in darkStories },
                    )
                    assertEquals("$code: the light stories' gray", "@color/igds_prism_gray_09", storiesBefore[light])
                    assertEquals(
                        "$code: the dark sheets' grays",
                        mapOf(
                            "values/IgdsPrismSemanticColorsExperimentDark" to "@color/igds_prism_gray_10",
                            "values/IgdsPrismGrayOverridesDark" to "@color/igds_prism_gray_1500",
                            "values/IgdsElevatedBackgroundFixDark" to "@color/igds_prism_gray_1500",
                        ),
                        before.filterKeys { it in dark },
                    )
                    assertTrue("$code: the light theme sets a sheet's color", light in before)
                    val darkLaunch = "values-night-v31/$INSTAGRAM_LAUNCH_THEME"
                    val lightLaunch = "values-v31/$INSTAGRAM_LAUNCH_THEME"
                    val launchBefore = attributeColors(res, "windowSplashScreenBackground")
                    assertEquals("$code: the launch screen's color", SPLASH_BACKGROUND_COLOR, launchBefore[darkLaunch])
                    assertEquals("$code: the light launch screen's color", SPLASH_BACKGROUND_COLOR, launchBefore[lightLaunch])

                    context.blackenThemes()

                    val launchAfter = attributeColors(res, "windowSplashScreenBackground")
                    assertEquals("$code: the dark launch screen (#410)", PURE_BLACK_COLOR, launchAfter[darkLaunch])
                    assertEquals("$code: the light launch screen", SPLASH_BACKGROUND_COLOR, launchAfter[lightLaunch])
                    assertEquals(
                        "$code: the dark launch icon's backdrop",
                        PURE_BLACK_COLOR,
                        attributeColors(res, "windowSplashScreenIconBackgroundColor")[darkLaunch],
                    )

                    val after = attributeColors(res, SHEET_BACKGROUND)
                    assertEquals("$code: the dark sheets", dark.associateWith { PURE_BLACK_COLOR }, after.filterKeys { it in dark })
                    assertEquals("$code: every other sheet", before - dark, after - dark)
                    assertNotEquals("$code: the light sheet", PURE_BLACK_COLOR, after[light])
                    val storiesAfter = attributeColors(res, STORY_BACKGROUND)
                    assertEquals(
                        "$code: the dark stories",
                        darkStories.associateWith { PURE_BLACK_COLOR },
                        storiesAfter.filterKeys { it in darkStories },
                    )
                    assertEquals("$code: every other story style", storiesBefore - darkStories, storiesAfter - darkStories)
                    val row = File(res, "drawable/$ROW_DRAWABLE.xml").readText()
                    assertTrue("$code: the Likes row goes black (#248)\n$row", PURE_BLACK_COLOR in row)
                    assertTrue("$code: its press keeps its gray\n$row", "${ROW_BACKGROUND}_pressed" in row)
                    assertTrue("$code: its normal fill is gone\n$row", !Regex("""$ROW_BACKGROUND\b""").containsMatchIn(row))
                }
            } finally {
                work.deleteRecursively()
            }
        }
    }

    /**
     * Both literal forms go pure black on their own registers in each palette; other colors, a tab
     * tint and the extension's own stay.
     */
    @Test
    fun theLiteralsGetPureBlack() {
        val (first, second) = COMPOSE_PALETTES
        val patch = PatchContexts.of(listOf(
            classDef(first, """
                const v0, 0xff0c1014
                const-wide v2, 0xff0c1014L
                const v4, 0xff25292e
                const-wide v2, 0xff25292eL
                return-void
            """),
            classDef(second, """
                const-wide v0, 0xff0c1014L
                const-wide v2, 0xffa2aab4L
                return-void
            """),
            classDef(bottomTabTint, """
                const v0, 0xff0c1014
                return-void
            """),
            classDef(extension, """
                const v0, 0xff0c1014
                return-void
            """),
        ))

        assertEquals(setOf("$first->colors", "$second->colors"), patch.blackenLiterals().toSet())

        val code = patch.code(first)
        assertEquals(Opcode.CONST, code[0].opcode)
        assertEquals(0, (code[0] as OneRegisterInstruction).registerA)
        assertEquals(PURE_BLACK.toInt(), (code[0] as WideLiteralInstruction).wideLiteral.toInt())
        assertEquals(Opcode.CONST_WIDE, code[1].opcode)
        assertEquals(2, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(PURE_BLACK, (code[1] as WideLiteralInstruction).wideLiteral)
        assertEquals(0xff25292e.toInt(), (code[2] as WideLiteralInstruction).wideLiteral.toInt())
        assertEquals(0xff25292eL, (code[3] as WideLiteralInstruction).wideLiteral)
        val v2 = patch.code(second)
        assertEquals(Opcode.CONST_WIDE, v2[0].opcode)
        assertEquals(0, (v2[0] as OneRegisterInstruction).registerA)
        assertEquals(PURE_BLACK, (v2[0] as WideLiteralInstruction).wideLiteral)
        assertEquals(0xffa2aab4L, (v2[1] as WideLiteralInstruction).wideLiteral)
        assertTrue("bottom tab tint must keep Prism black", patch.code(bottomTabTint).any(::isPrismBlack))
        assertTrue("the extension's own color", patch.code(extension).any(::isPrismBlack))
    }

    /**
     * The getColor the story animator stores in its black backdrop field loads black instead (#360);
     * a getColor into another field and the same shape in a class without the animator's tag stay.
     */
    @Test
    fun theStoryBackdropGetsPureBlack() {
        val story = "Lfixture/StoryAnimator;"
        val other = "Lfixture/OtherViewer;"
        fun body(type: String, tag: Boolean) = """
            ${if (tag) "const-string v0, \"$STORY_ANIMATOR_TAG\"" else "const/4 v0, 0x0"}
            const/high16 v0, -0x1000000
            iput v0, v5, $type->backdrop:I
            const v0, 0x7f0602c1
            invoke-virtual { v4, v0 }, Landroid/content/Context;->getColor(I)I
            move-result v0
            iput v0, v5, $type->backdrop:I
            invoke-virtual { v4, v0 }, Landroid/content/Context;->getColor(I)I
            move-result v1
            iput v1, v5, $type->tint:I
            return-void
        """
        val patch = PatchContexts.of(listOf(classDef(story, body(story, true)), classDef(other, body(other, false))))

        val found = patch.findStoryBackdrops()
        assertEquals(listOf(5), found.map { it.index })
        patch.blackenStoryBackdrops(found)

        val code = patch.code(story)
        assertEquals(Opcode.INVOKE_VIRTUAL, code[4].opcode)
        assertEquals(Opcode.CONST_HIGH16, code[5].opcode)
        assertEquals(0, (code[5] as OneRegisterInstruction).registerA)
        assertEquals(PURE_BLACK.toInt(), (code[5] as WideLiteralInstruction).wideLiteral.toInt())
        assertEquals(Opcode.IPUT, code[6].opcode)
        assertEquals("the tint keeps its color", Opcode.MOVE_RESULT, code[8].opcode)
        assertEquals("a class without the tag", Opcode.MOVE_RESULT, patch.code(other)[5].opcode)
    }

    /** No getColor into the black field means an update the patch hasn't seen, so it fails. */
    @Test
    fun aStoryAnimatorWithoutItsGetColorFails() {
        val story = "Lfixture/StoryAnimator;"
        val patch = PatchContexts.of(listOf(classDef(story, """
            const-string v0, "$STORY_ANIMATOR_TAG"
            const/high16 v0, -0x1000000
            iput v0, v5, $story->backdrop:I
            return-void
        """)))

        val failure = assertThrows(PatchException::class.java) { patch.findStoryBackdrops() }
        assertTrue(failure.message!!, "no getColor reaches $story->backdrop" in failure.message!!)
    }

    /**
     * Each read of the theme's new row tint in a method with a Notifications label goes through the
     * hook (#374). Another color in the same method and the same tint in a comment row stay.
     */
    @Test
    fun aNewRowTintReadGoesThroughTheHook() {
        val avatar = "Lfixture/Avatar;"
        val row = "Lfixture/Row;"
        val comment = "Lfixture/CommentRow;"
        val patch = newRowContext(
            classDef(avatar, """
                const-string v0, "com.instagram.newsfeed.compose.ui.StartAvatar.<anonymous> (NewsfeedStoryRow.kt:359)"
                iget-wide v1, v5, $FAKE_THEME->tint:J
                return-void
            """),
            classDef(row, """
                const-string v0, "com.instagram.newsfeed.fragment.NewsfeedRow.<anonymous> (NewsfeedYouComposeFragment.kt:3634)"
                iget-wide v1, v5, $FAKE_THEME->tint:J
                iget-wide v3, v5, $FAKE_THEME->f0:J
                iget-wide v0, v5, $FAKE_THEME->tint:J
                return-void
            """),
            classDef(comment, """
                const-string v0, "com.instagram.comments.CommentRow (CommentRow.kt:292)"
                iget-wide v1, v5, $FAKE_THEME->tint:J
                return-void
            """),
        )

        val found = patch.findNewRowTintReads()
        assertEquals(listOf(avatar to 1, row to 1, row to 3), found.map { it.type to it.index })
        patch.blackenNewRowTints(found)

        patch.code(avatar).let { code ->
            assertEquals(5, code.size)
            assertHooked(code, 1, 1)
        }
        patch.code(row).let { code ->
            assertEquals(9, code.size)
            assertHooked(code, 1, 1)
            assertEquals("another color stays", Opcode.IGET_WIDE, code[4].opcode)
            assertEquals("another color stays", Opcode.IGET_WIDE, code[5].opcode)
            assertHooked(code, 5, 0)
        }
        assertEquals("a comment row keeps its tint", 3, patch.code(comment).size)
    }

    /** A pooled Notifications label counts, since which strings Redex pools differs per build (#77). */
    @Test
    fun aPooledNotificationsLabelCounts() {
        val pool = "Lfixture/Pool;"
        val avatar = "Lfixture/Avatar;"
        val poolClass = classDef(pool, """
            packed-switch v0, :cases
            const/4 v0, 0x0
            return-object v0
            :name
            const-string v0, "com.instagram.newsfeed.compose.ui.StartAvatar.<anonymous> (NewsfeedStoryRow.kt:359)"
            return-object v0
            :cases
            .packed-switch 0x7
                :name
            .end packed-switch
        """, parameters = listOf("I"), returns = "Ljava/lang/String;", name = "A00")
        val patch = newRowContext(poolClass, classDef(avatar, """
            const/4 v0, 0x7
            invoke-static { v0 }, $pool->A00(I)Ljava/lang/String;
            iget-wide v1, v5, $FAKE_THEME->tint:J
            return-void
        """))

        assertEquals(listOf(avatar to 2), patch.findNewRowTintReads().map { it.type to it.index })
    }

    /** No avatar means no way to tell which of the theme's colors is the tint, so it fails. */
    @Test
    fun notificationsWithoutTheAvatarFail() {
        val patch = newRowContext(classDef("Lfixture/Row;", """
            const-string v0, "com.instagram.newsfeed.fragment.NewsfeedRow.<anonymous> (NewsfeedYouComposeFragment.kt:3634)"
            iget-wide v1, v5, $FAKE_THEME->tint:J
            return-void
        """))

        val failure = assertThrows(PatchException::class.java) { patch.findNewRowTintReads() }
        assertTrue(failure.message!!, "Notifications avatar" in failure.message!!)
    }

    /** An avatar reading two of the theme's colors doesn't say which is the tint, so it fails too. */
    @Test
    fun anAvatarReadingTwoColorsFails() {
        val patch = newRowContext(classDef("Lfixture/Avatar;", """
            const-string v0, "com.instagram.newsfeed.compose.ui.StartAvatar.<anonymous> (NewsfeedStoryRow.kt:359)"
            iget-wide v1, v5, $FAKE_THEME->tint:J
            iget-wide v3, v5, $FAKE_THEME->f0:J
            return-void
        """))

        val failure = assertThrows(PatchException::class.java) { patch.findNewRowTintReads() }
        assertTrue(failure.message!!, "color" in failure.message!!)
    }

    /** No class making a theme from Prism's tint means an update the patch hasn't seen. */
    @Test
    fun noThemeFails() {
        val patch = PatchContexts.of(listOf(classDef("Lfixture/Palette;", """
            sget-wide v0, $NEW_ROW_PALETTE->$NEW_ROW_PRISM_TINT:J
            return-void
        """)))

        val failure = assertThrows(PatchException::class.java) { patch.findNewRowTintReads() }
        assertTrue(failure.message!!, "theme" in failure.message!!)
    }

    /** The theme class of these fake pages, its palette and [classes], in one patch context. */
    private fun newRowContext(vararg classes: ClassDef): app.morphe.patcher.patch.BytecodePatchContext {
        val fields = (listOf("tint") + (0 until NEW_ROW_THEME_COLORS).map { "f$it" }).map { name ->
            ImmutableField(FAKE_THEME, name, "J", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)
        }
        val theme = ImmutableClassDef(FAKE_THEME, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, emptyList())
        val palette = classDef("Lfixture/DarkTheme;", """
            sget-wide v0, $NEW_ROW_PALETTE->$NEW_ROW_PRISM_TINT:J
            new-instance v0, $FAKE_THEME
            return-void
        """)
        return PatchContexts.of(listOf(theme, palette) + classes)
    }

    private fun assertHooked(code: List<Instruction>, read: Int, register: Int) {
        assertEquals(Opcode.IGET_WIDE, code[read].opcode)
        val call = code[read + 1] as Instruction3rc
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals(NEW_ROW_TINT, call.reference.toString())
        assertEquals(register, call.startRegister)
        assertEquals(2, call.registerCount)
        assertEquals(Opcode.MOVE_RESULT_WIDE, code[read + 2].opcode)
        assertEquals(register, (code[read + 2] as OneRegisterInstruction).registerA)
    }

    /**
     * In each build the Notifications page reads Prism's tint in its avatar, its row and the row's
     * two swipe backgrounds, all four go through the hook, and nothing else that reads the tint does
     * (#374). Prism's dark theme fills the field with BasePrismColors' white at 15 percent, the value
     * the extension looks for, so a build where that moved fails here instead of staying gray.
     */
    private fun newRowTintsGoBlack(bundle: File, label: String, tinters: List<ClassDef>) {
        val created = tinters.flatMap { classDef ->
            classDef.methods.flatMap { method ->
                method.implementation?.instructions?.filter { it.opcode == Opcode.NEW_INSTANCE }.orEmpty()
                    .map { ((it as ReferenceInstruction).reference as TypeReference).type }
            }
        }.toSet()
        val theme = FixtureDex.classes(bundle, created).values.single { classDef ->
            classDef.instanceFields.count { it.type == "J" } >= NEW_ROW_THEME_COLORS
        }
        val readers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.typeSection.none { it == theme.type }) return@forEach
            for (classDef in dex.classes) {
                val reads = classDef.methods.any { method ->
                    method.implementation?.instructions?.any { it.opcode == Opcode.IGET_WIDE && it.fieldOf() == theme.type } == true
                }
                if (reads) readers += ImmutableClassDef.of(classDef)
            }
        }
        val patch = PatchContexts.of(FixtureDex.withStringPools(bundle, readers.filter { it.type != theme.type }) + tinters + theme)

        val found = patch.findNewRowTintReads()

        assertEquals("${label}: the tint's reads on Notifications", 4, found.size)
        assertEquals("${label}: the methods reading it", 3, found.map { it.type to it.method.name }.toSet().size)
        val tint = ((found.first().method.implementation!!.instructions.toList()[found.first().index] as ReferenceInstruction).reference as FieldReference).name
        val before = found.associate { read ->
            (read.type to read.method.name) to read.method.implementation!!.instructions.count { it.opcode != Opcode.NOP }
        }
        assertEquals("${label}: the theme's field Prism fills with white at 15 percent",
            true, tint in prismTintFields(theme, tinters))

        patch.blackenNewRowTints(found)

        for ((key, size) in before) {
            val method = patch.classDefBy(key.first).methods.single { it.name == key.second && it.parameterTypes.size == found.first { read -> read.type == key.first && read.method.name == key.second }.method.parameterTypes.size }
            val code = method.implementation!!.instructions.filter { it.opcode != Opcode.NOP }
            val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == NEW_ROW_TINT }
            assertEquals("${label}: ${key.first} grew by its hooks", size + hooks.size * 2, code.size)
            hooks.forEach { at ->
                val read = code[at - 1]
                assertEquals("${label}: the hook follows the read", Opcode.IGET_WIDE, read.opcode)
                assertEquals(tint, (((read as ReferenceInstruction).reference) as FieldReference).name)
                assertEquals((read as TwoRegisterInstruction).registerA, (code[at] as Instruction3rc).startRegister)
                assertEquals(Opcode.MOVE_RESULT_WIDE, code[at + 1].opcode)
                assertEquals(read.registerA, (code[at + 1] as OneRegisterInstruction).registerA)
            }
        }
        val hookSites = readers.sumOf { reader ->
            patch.classDefBy(reader.type).methods.sumOf { method ->
                method.implementation?.instructions?.count { (it as? ReferenceInstruction)?.reference?.toString() == NEW_ROW_TINT } ?: 0
            }
        }
        assertEquals("${label}: every hook in the app", 4, hookSites)
    }

    private fun Instruction.fieldOf() = ((this as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass

    /**
     * The fields of [theme] Prism's dark theme fills with BasePrismColors' white at 15 percent, read
     * from the constructor call: each field's argument, and what the caller last loaded for it.
     */
    private fun prismTintFields(theme: ClassDef, tinters: List<ClassDef>): Set<String> {
        val prism = tinters.single { it.type == NEW_ROW_PALETTE }
        val constant = prism.methods.single { it.name == "<clinit>" }.implementation!!.instructions.toList()
        val store = constant.indexOfFirst { it.opcode == Opcode.SPUT_WIDE && (it as ReferenceInstruction).reference.let { field -> field is FieldReference && field.name == NEW_ROW_PRISM_TINT } }
        assertEquals("white at 15 percent", 0x26ffffffL,
            constant.subList(0, store).last { it.opcode == Opcode.CONST_WIDE_32 && (it as OneRegisterInstruction).registerA == (constant[store] as OneRegisterInstruction).registerA }
                .let { (it as WideLiteralInstruction).wideLiteral })

        val constructor = theme.methods.single { it.name == "<init>" && it.parameterTypes.size >= NEW_ROW_THEME_COLORS }
        val longs = constructor.parameterTypes.count { it.toString() == "J" }
        val firstLong = constructor.implementation!!.registerCount - longs * 2
        val body = constructor.implementation!!.instructions.toList()
        val argumentOf = body.indices.filter { body[it].opcode == Opcode.IPUT_WIDE }.associate { at ->
            val put = body[at] as TwoRegisterInstruction
            val moved = body.getOrNull(at - 1)?.takeIf { it.opcode == Opcode.MOVE_WIDE_FROM16 && (it as TwoRegisterInstruction).registerA == put.registerA }
            val source = (moved as? TwoRegisterInstruction)?.registerB ?: put.registerA
            ((body[at] as ReferenceInstruction).reference as FieldReference).name to (source - firstLong) / 2
        }

        val dark = tinters.first { classDef -> classDef.type != NEW_ROW_PALETTE && classDef.methods.any { method ->
            method.name == "<clinit>" && method.implementation!!.instructions.any { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == theme.type }
        } }
        val made = dark.methods.single { it.name == "<clinit>" }.implementation!!.instructions.toList()
        val call = made.indexOfFirst { it.opcode == Opcode.INVOKE_DIRECT_RANGE && ((it as ReferenceInstruction).reference as MethodReference).definingClass == theme.type }
        val start = (made[call] as Instruction3rc).startRegister
        return argumentOf.filter { (_, argument) ->
            val register = start + 2 + argument * 2
            val load = made.subList(0, call).last { (it as? OneRegisterInstruction)?.registerA == register }
            val field = (load as? ReferenceInstruction)?.reference as? FieldReference
            load.opcode == Opcode.SGET_WIDE && field?.definingClass == NEW_ROW_PALETTE && field.name == NEW_ROW_PRISM_TINT
        }.keys
    }

    /**
     * In each declared build, both Compose palettes lose Prism's black without touching unrelated
     * owners. 449's second palette (GRAY_1600) colors the Direct inbox and Activity (#51, #58).
     */
    @Test
    fun eachDeclaredBuildChangesOnlyTheComposePalettes() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildGoesPureBlackToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val classes = mutableListOf<ClassDef>()
        val animators = mutableListOf<ClassDef>()
        val tinters = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { it.implementation?.instructions?.any(::isPrismBlack) == true }) {
                    classes += ImmutableClassDef.of(classDef)
                }
                if (classDef.methods.any { it.implementation?.instructions?.any(::logsStoryAnimatorTag) == true }) {
                    animators += ImmutableClassDef.of(classDef)
                }
                if (classDef.methods.any { it.implementation?.instructions?.any(::touchesPrismTint) == true }) {
                    tinters += ImmutableClassDef.of(classDef)
                }
            }
        }
        storyBackdropsGoBlack(animators, label)
        newRowTintsGoBlack(bundle, label, tinters)
        val patch = PatchContexts.of(classes)

        val changed = patch.blackenLiterals()

        assertEquals("${label}: the Compose palettes", COMPOSE_PALETTES.toSet(),
            changed.map { it.substringBefore("->") }.toSet())
        COMPOSE_PALETTES.forEach { palette ->
            assertTrue("${label}: $palette keeps no Prism black",
                patch.classDefBy(palette).methods.none { method ->
                    method.implementation?.instructions?.any(::isPrismBlack) == true
                })
        }
        classes.filterNot { it.type in COMPOSE_PALETTES }.forEach { classDef ->
            assertTrue("${label}: ${classDef.type} keeps Prism black",
                patch.classDefBy(classDef.type).methods.any { method ->
                    method.implementation?.instructions?.any(::isPrismBlack) == true
                })
        }
    }

    private fun logsStoryAnimatorTag(instruction: Instruction) =
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == STORY_ANIMATOR_TAG

    private fun touchesPrismTint(instruction: Instruction) =
        ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.let {
            it.definingClass == NEW_ROW_PALETTE && it.name == NEW_ROW_PRISM_TINT
        } == true

    /**
     * The build's story animator stores two getColor results in its black backdrop field (the
     * viewer's two binds), and both load black once patched (#360).
     */
    private fun storyBackdropsGoBlack(animators: List<ClassDef>, label: String) {
        val patch = PatchContexts.of(animators)
        val found = patch.findStoryBackdrops()
        assertEquals("${label}: the story animator's getColor backdrops", 2, found.size)
        patch.blackenStoryBackdrops(found)
        found.forEach { backdrop ->
            val code = patch.classDefBy(backdrop.type).methods.single {
                it.name == backdrop.method.name && it.parameterTypes.map(Any::toString) == backdrop.method.parameterTypes.map(Any::toString)
            }.implementation!!.instructions.toList()
            assertEquals("${label}: ${backdrop.method.name} loads black", Opcode.CONST_HIGH16, code[backdrop.index].opcode)
            assertEquals(PURE_BLACK.toInt(), (code[backdrop.index] as WideLiteralInstruction).wideLiteral.toInt())
        }
    }

    /** Each build by its version code: the declared one's bundle and the other builds' base APKs. */
    private fun resourceBuilds(): Map<String, File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        return (declared + Fixtures.otherBuilds()).associateBy { file ->
            (if (file.extension == "apks") file.nameWithoutExtension else file.parentFile.name).substringAfterLast('-')
        }.toSortedMap()
    }

    /**
     * [apk]'s resources decoded in full into [workspace], as Manager decodes them for a resource
     * patch. The patcher keeps the resource context and its decode internal, so they're reached by
     * their JVM names, pinned to morphe-patcher 1.15.1 (LaunchScreenPatchTest reads them the same way).
     */
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

    /** Each style that sets [attribute], as its values folder and its name, with the color it sets. */
    private fun attributeColors(res: File, attribute: String): Map<String, String> {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val found = sortedMapOf<String, String>()
        res.listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty().forEach { directory ->
            val file = File(directory, "styles.xml")
            if (!file.isFile || attribute !in file.readText()) return@forEach
            val styles = builder.parse(file).getElementsByTagName("style")
            for (style in (0 until styles.length).map { styles.item(it) as Element }) {
                val items = style.getElementsByTagName("item")
                (0 until items.length).map { items.item(it) as Element }
                    .filter { it.getAttribute("name").substringAfter(':') == attribute }
                    .forEach { found["${directory.name}/${style.getAttribute("name")}"] = it.textContent.trim() }
            }
        }
        return found
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.code(type: String): List<Instruction> =
        classDefBy(type).methods.single().implementation!!.instructions.toList()

    private fun classDef(
        type: String,
        body: String,
        parameters: List<String> = emptyList(),
        returns: String = "V",
        name: String = "colors",
    ): ClassDef {
        val mutable = MutableMethod(
            ImmutableMethod(
                type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(6, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        val method: Method = ImmutableMethod.of(mutable)
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(method))
    }
}
