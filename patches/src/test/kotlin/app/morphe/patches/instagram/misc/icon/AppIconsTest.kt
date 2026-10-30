/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.icon

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherContext
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.resource.ResourceMode
import app.morphe.patches.instagram.coexist.CloneFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pick the app icon (#284): an alias per HushGram icon next to Instagram's launcher alias, each off and
 * left alone by Instagram's component manager, and an adaptive icon per alias in the folder Instagram's
 * own lives in.
 */
class AppIconsTest {
    @get:Rule val folder = TemporaryFolder()

    /** A white glyph the way the decoder writes Instagram's. */
    private val GLYPH = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#ffffffff" android:pathData="M54,42a12,12 0 1,0 0.1,0z" />
    <path android:fillColor="#ffffffff" android:pathData="M66,41a3,3 0 1,0 0.1,0z" />
</vector>"""

    private val manifest = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.instagram.android">
            <application android:icon="@mipmap/icon">
                <activity android:name="com.instagram.mainactivity.InstagramMainActivity" />
                <activity-alias android:name=".activity.MainTabActivity" android:targetActivity="com.instagram.mainactivity.InstagramMainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                    <meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts" />
                </activity-alias>
                <activity-alias android:name="com.instagram.android.activity.MainTabActivity.ig_icon_dark" android:enabled="false" android:targetActivity="com.instagram.mainactivity.InstagramMainActivity" />
            </application>
        </manifest>
    """

    @Test
    fun eachIconIsACopyOfInstagramsAliasThatsOffAndLeftAlone() {
        val document = parse(manifest)
        addIconAliases(document)
        val aliases = document.elements("activity-alias")
        assertEquals(
            "right after Instagram's, in order",
            listOf(".activity.MainTabActivity") + HUSHGRAM_ICONS.map { it.alias } + "com.instagram.android.activity.MainTabActivity.ig_icon_dark",
            aliases.map { it.getAttribute("android:name") },
        )
        assertEquals("Instagram's is left as it was", "", aliases[0].getAttribute("android:enabled"))
        assertEquals(listOf("android.app.shortcuts"), aliases[0].elements("meta-data").map { it.getAttribute("android:name") })
        for ((icon, alias) in HUSHGRAM_ICONS.zip(aliases.subList(1, 1 + HUSHGRAM_ICONS.size))) {
            assertEquals(icon.key, "false", alias.getAttribute("android:enabled"))
            assertEquals(icon.key, "@mipmap/hushgram_icon_${icon.key}", alias.getAttribute("android:icon"))
            assertEquals(icon.key, "", alias.getAttribute("android:roundIcon"))
            assertEquals(icon.key, "com.instagram.mainactivity.InstagramMainActivity", alias.getAttribute("android:targetActivity"))
            assertEquals(icon.key, "true", alias.getAttribute("android:exported"))
            assertEquals(
                icon.key,
                listOf("default-state" to "false", "cmp_manager.persist_state" to "true", "android.app.shortcuts" to ""),
                alias.elements("meta-data").map { it.getAttribute("android:name") to it.getAttribute("android:value") },
            )
            val filter = alias.elements("intent-filter").single()
            assertEquals(icon.key, "android.intent.category.LAUNCHER", filter.elements("category").single().getAttribute("android:name"))
        }
    }

    @Test
    fun aRoundIconIsSwappedToo() {
        val document = parse(manifest.replace("android:exported=\"true\">", "android:exported=\"true\" android:roundIcon=\"@mipmap/icon_round\">"))
        addIconAliases(document)
        val alias = document.elements("activity-alias").single { it.getAttribute("android:name") == HUSHGRAM_ICONS[1].alias }
        assertEquals("@mipmap/hushgram_icon_dark", alias.getAttribute("android:roundIcon"))
    }

    @Test
    fun withoutInstagramsAliasOrWithHushGramsAlreadyThereItRefuses() {
        refuses("found 0", manifest.replace(".activity.MainTabActivity\"", ".activity.SomethingElse\""))
        refuses("found 2", manifest.replace(".ig_icon_dark\"", "\""))
        val twice = parse(manifest).also(::addIconAliases)
        val again = assertThrows(PatchException::class.java) { addIconAliases(twice) }
        assertTrue(again.message, again.message.orEmpty().contains("already has ${HUSHGRAM_ICONS[0].alias}"))
    }

    @Test
    fun theApplicationsIconIsReadAsTypeAndName() {
        assertEquals("mipmap/icon", applicationIcon(parse(manifest)))
        val refusal = assertThrows(PatchException::class.java) { applicationIcon(parse(manifest.replace("@mipmap/icon", "@android:mipmap/sym_def_app_icon"))) }
        assertTrue(refusal.message, refusal.message.orEmpty().contains("isn't one of the app's resources"))
    }

    @Test
    fun instagramsAdaptiveIconAndItsGlyphAreFound() {
        val res = res(
            "mipmap-anydpi/icon.xml" to adaptiveXml("@mipmap/ig_launcher_background", "@mipmap/ig_launcher_foreground", "@drawable/ig_launcher_monochrome"),
            "mipmap-xxhdpi/icon.xml" to "<bitmap xmlns:android=\"http://schemas.android.com/apk/res/android\" android:src=\"@drawable/x\" />",
            "drawable/icon.xml" to adaptiveXml("@color/a", "@color/b", "@color/c"),
        )
        val icon = readInstagramIcon(res, "mipmap/icon")
        assertEquals("mipmap-anydpi", icon.folder)
        assertEquals("@drawable/ig_launcher_monochrome", icon.glyph)
    }

    @Test
    fun twoAdaptiveIconsOrNoGlyphIsRefused() {
        val two = res(
            "mipmap-anydpi/icon.xml" to adaptiveXml("@color/a", "@color/b", "@drawable/c"),
            "mipmap-anydpi-v26/icon.xml" to adaptiveXml("@color/a", "@color/b", "@drawable/c"),
        )
        assertTrue(assertThrows(PatchException::class.java) { readInstagramIcon(two, "mipmap/icon") }.message.orEmpty().contains("found 2"))
        val plain = res("mipmap-anydpi/icon.xml" to adaptiveXml("@color/a", "@color/b", "@drawable/c").replace(Regex("\\s*<monochrome[^>]*/>"), ""))
        assertTrue(assertThrows(PatchException::class.java) { readInstagramIcon(plain, "mipmap/icon") }.message.orEmpty().contains("no single monochrome"))
        val framework = res("mipmap-anydpi/icon.xml" to adaptiveXml("@color/a", "@color/b", "@android:drawable/c"))
        assertTrue(assertThrows(PatchException::class.java) { readInstagramIcon(framework, "mipmap/icon") }.message.orEmpty().contains("isn't one of the app's drawables"))
    }

    /**
     * HushGram's icon is its mark on the gradient, the next two are Instagram's glyph on a color, and the
     * light one a black copy of that glyph on white (#353), its themed layer still Instagram's glyph.
     */
    @Test
    fun eachIconHasItsBackgroundAndItsFront() {
        val files = iconFiles(InstagramIcon("mipmap-anydpi", "@drawable/ig_launcher_monochrome"), GLYPH)
        assertEquals(
            setOf(
                "drawable/$ICON_MARK.xml", "drawable/$ICON_MARK_MONO.xml",
                "drawable/hushgram_icon_hushgram_background.xml", "drawable/hushgram_icon_dark_background.xml", "drawable/hushgram_icon_plum_background.xml",
                "drawable/hushgram_icon_light_background.xml", "drawable/hushgram_icon_light_glyph.xml",
                "mipmap-anydpi/hushgram_icon_hushgram.xml", "mipmap-anydpi/hushgram_icon_dark.xml", "mipmap-anydpi/hushgram_icon_plum.xml",
                "mipmap-anydpi/hushgram_icon_light.xml",
            ),
            files.keys,
        )
        fun layers(key: String) = parse(files.getValue("mipmap-anydpi/hushgram_icon_$key.xml")).let { icon ->
            listOf("background", "foreground", "monochrome").map { icon.elements(it).single().getAttribute("android:drawable") }
        }
        assertEquals(listOf("@drawable/hushgram_icon_hushgram_background", "@drawable/$ICON_MARK", "@drawable/$ICON_MARK_MONO"), layers("hushgram"))
        assertEquals(listOf("@drawable/hushgram_icon_dark_background", "@drawable/ig_launcher_monochrome", "@drawable/ig_launcher_monochrome"), layers("dark"))
        assertEquals(listOf("@drawable/hushgram_icon_plum_background", "@drawable/ig_launcher_monochrome", "@drawable/ig_launcher_monochrome"), layers("plum"))
        assertEquals(listOf("@drawable/hushgram_icon_light_background", "@drawable/hushgram_icon_light_glyph", "@drawable/ig_launcher_monochrome"), layers("light"))
        assertEquals("#FFFFFFFF", parse(files.getValue("drawable/hushgram_icon_light_background.xml")).elements("solid").single().getAttribute("android:color"))
        val black = parse(files.getValue("drawable/hushgram_icon_light_glyph.xml"))
        assertEquals("vector", black.documentElement.tagName)
        assertEquals("#FF000000", black.documentElement.getAttribute("android:tint"))
        assertEquals("the glyph's own shape", parse(GLYPH).elements("path").map { it.getAttribute("android:pathData") },
            black.elements("path").map { it.getAttribute("android:pathData") })
        assertEquals("linear", parse(files.getValue("drawable/hushgram_icon_hushgram_background.xml")).elements("gradient").single().getAttribute("android:type"))
        assertEquals("#FF000000", parse(files.getValue("drawable/hushgram_icon_dark_background.xml")).elements("solid").single().getAttribute("android:color"))
        val mark = parse(files.getValue("drawable/$ICON_MARK.xml"))
        assertEquals("108dp", mark.documentElement.getAttribute("android:width"))
        assertEquals(3, mark.elements("path").size)
        assertEquals(setOf("#FFFFFFFF"), parse(files.getValue("drawable/$ICON_MARK_MONO.xml")).elements("path").map { it.getAttribute("android:fillColor") }.toSet())
    }

    @Test
    fun theGlyphIsReadOnlyWhenItsOneVector() {
        assertEquals(GLYPH, readGlyph(res("drawable-anydpi/ig_launcher_monochrome.xml" to GLYPH), "@drawable/ig_launcher_monochrome"))
        val two = res("drawable/g.xml" to GLYPH, "drawable-v26/g.xml" to GLYPH)
        assertTrue(assertThrows(PatchException::class.java) { readGlyph(two, "@drawable/g") }.message.orEmpty().contains("found 2"))
        val shape = res("drawable/g.xml" to "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" />")
        assertTrue(assertThrows(PatchException::class.java) { readGlyph(shape, "@drawable/g") }.message.orEmpty().contains("isn't a vector"))
    }

    /**
     * Every build's manifest has Instagram's alias once, an icon of the app's own, and takes HushGram's.
     * The fixture reads the binary manifest, so the icon is its resource id here; the encoder test reads
     * it by name as Manager hands it over.
     */
    @Test
    fun eachBuildsManifestTakesTheAliases() {
        for ((code, build) in CloneFixtures.builds()) {
            val manifest = CloneFixtures.manifest(build)
            val icon = manifest.elements("application").single().getAttribute("android:icon")
            assertTrue("$code: the application's icon is $icon", icon.startsWith("@0x7f"))
            addIconAliases(manifest)
            val names = manifest.elements("activity-alias").map { it.getAttribute("android:name") }
            val at = names.indexOfFirst { it == INSTAGRAM_ALIAS || it == ".activity.MainTabActivity" }
            assertEquals("$code: after Instagram's", HUSHGRAM_ICONS.map { it.alias }, names.subList(at + 1, at + 1 + HUSHGRAM_ICONS.size))
        }
    }

    /**
     * On the declared build, decoded and encoded as Manager does: the manifest gains the three aliases,
     * the res folder the icons, and every resource keeps its number.
     */
    @Test
    fun theDeclaredBuildTakesTheIconsThroughTheEncoder() {
        val build = CloneFixtures.builds().values.single { it.extension == "apks" }
        val work = folder.newFolder("work")
        val apk = File(work, "base.apk").also { base ->
            ZipFile(build).use { bundle -> bundle.getInputStream(bundle.getEntry("base.apk")).use { input -> base.outputStream().use(input::copyTo) } }
        }
        val patched = File(work, "patched.apk")
        val before = decoded(apk, File(work, "patch")) { context ->
            val ids = ids(context.get("res"))
            context.addAppIcons()
            encoded(context).copyTo(patched)
            ids
        }
        decoded(patched, File(work, "check")) { context ->
            val res = context.get("res")
            val lost = before - ids(res)
            assertTrue("numbers gone, ${lost.take(5)}", lost.isEmpty())
            val manifest = parse(context.get("AndroidManifest.xml").readText())
            val ours = manifest.elements("activity-alias").filter { it.getAttribute("android:name").startsWith(HUSHGRAM_ALIAS) }
            assertEquals(HUSHGRAM_ICONS.map { it.alias }, ours.map { it.getAttribute("android:name") })
            assertEquals(HUSHGRAM_ICONS.map { "@mipmap/${it.icon}" }, ours.map { it.getAttribute("android:icon") })
            val icons = res.listFiles()!!.single { File(it, "icon.xml").isFile && parse(File(it, "icon.xml").readText()).documentElement.tagName == "adaptive-icon" }
            for (icon in HUSHGRAM_ICONS) {
                val adaptive = parse(File(icons, "${icon.icon}.xml").readText())
                assertEquals(icon.key, "@drawable/${icon.backgroundName}", adaptive.elements("background").single().getAttribute("android:drawable"))
                assertTrue(icon.key, File(res, "drawable/${icon.backgroundName}.xml").isFile)
            }
            assertEquals("vector", parse(File(res, "drawable/$ICON_MARK.xml").readText()).documentElement.tagName)
            val black = parse(File(res, "drawable/hushgram_icon_light_glyph.xml").readText())
            assertEquals("Instagram's glyph, tinted", "vector", black.documentElement.tagName)
            assertEquals("#ff000000", black.documentElement.getAttribute("android:tint").lowercase())
            assertTrue("with its paths", black.elements("path").isNotEmpty())
        }
    }

    private fun refuses(reason: String, xml: String) {
        val document = parse(xml)
        val refusal = assertThrows(PatchException::class.java) { addIconAliases(document) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        assertTrue("an alias went in", document.elements("activity-alias").none { it.getAttribute("android:name").startsWith(HUSHGRAM_ALIAS) })
    }

    private fun res(vararg files: Pair<String, String>): File {
        val res = folder.newFolder()
        for ((path, xml) in files) File(res, path).apply { parentFile.mkdirs() }.writeText(xml)
        return res
    }

    /** [apk]'s resources decoded in full into [workspace], as LaunchScreenPatchTest reaches them. */
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

    private fun ids(res: File): Set<String> =
        parse(File(res, "values/public.xml").readText()).elements("public").mapTo(HashSet()) { "${it.getAttribute("type")}@${it.getAttribute("id")}" }

    private fun encoded(context: ResourcePatchContext): File {
        val result = ResourcePatchContext::class.java.getMethod("get").invoke(context) as PatcherResult.PatchedResources
        return requireNotNull(result.resourcesApk) { "the patcher encoded no resources APK" }
    }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.trimIndent().byteInputStream())

    private fun Document.elements(tag: String): List<Element> = documentElement.elements(tag).let {
        if (documentElement.tagName == tag) listOf(documentElement) + it else it
    }

    private fun Element.elements(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }
}
