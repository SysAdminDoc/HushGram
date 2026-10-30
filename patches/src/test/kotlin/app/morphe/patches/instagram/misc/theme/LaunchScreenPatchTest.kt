/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherContext
import app.morphe.patcher.PatcherResult
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.resource.ResourceMode
import app.morphe.patches.shared.compat.AppCompatibilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class LaunchScreenPatchTest {
    private val launcher = """
        <style name="Base.Theme.Launcher" parent="@style/Theme.Instagram">
            <item name="android:windowBackground">@drawable/instagram_splash_screen</item>
        </style>
    """

    /** The theme added is Instagram's launch theme with HushGram's mark and no branding, and Instagram's is left alone. */
    @Test
    fun theThemeIsInstagramsWithHushGramsMark() {
        val styles = parse("<resources><style name=\"Theme.Instagram\" />$launcher</resources>")

        addLaunchTheme(styles)

        val themes = styles.elements("style")
        assertEquals(listOf("Theme.Instagram", INSTAGRAM_LAUNCH_THEME, LAUNCH_THEME), themes.map { it.getAttribute("name") })
        val added = themes.last()
        assertEquals("@style/$INSTAGRAM_LAUNCH_THEME", added.getAttribute("parent"))
        assertEquals(
            listOf(SPLASH_ICON to "@drawable/$LAUNCH_MARK", SPLASH_BRANDING to "@null"),
            added.elements("item").map { it.getAttribute("name") to it.textContent },
        )
        assertEquals(
            listOf("android:windowBackground" to "@drawable/instagram_splash_screen"),
            themes[1].elements("item").map { it.getAttribute("name") to it.textContent },
        )
    }

    @Test
    fun theThemeRefusesWithoutInstagramsOrWithAnotherOfItsName() {
        val missing = assertThrows(PatchException::class.java) {
            addLaunchTheme(parse("<resources><style name=\"Theme.Instagram\" /></resources>"))
        }
        assertTrue(missing.message, missing.message!!.contains("has no $INSTAGRAM_LAUNCH_THEME"))
        val twice = parse("<resources>$launcher</resources>")
        addLaunchTheme(twice)
        val again = assertThrows(PatchException::class.java) { addLaunchTheme(twice) }
        assertTrue(again.message, again.message!!.contains("already has a $LAUNCH_THEME"))
    }

    /** Only Instagram's launch theme naming an icon counts, not another theme naming one. */
    @Test
    fun onlyInstagramsLaunchThemeNamingAnIconCounts() {
        assertTrue(namesSplashIcon(parse("""
            <resources>
                <style name="Base.Theme.Launcher" parent="@style/Theme.Instagram">
                    <item name="android:windowSplashScreenBackground">@color/ig_splash_screen_background</item>
                    <item name="android:windowSplashScreenAnimatedIcon">@drawable/instagram_padded</item>
                </style>
            </resources>
        """)))
        assertFalse(namesSplashIcon(parse("""
            <resources>
                <style name="Base.Theme.Launcher" parent="@style/Theme.Instagram">
                    <item name="android:windowSplashScreenBackground">@color/ig_splash_screen_background</item>
                </style>
                <style name="Theme.DeepLinkSplashScreen">
                    <item name="android:windowSplashScreenAnimatedIcon">@drawable/instagram_padded</item>
                </style>
            </resources>
        """)))
    }

    /** The mark is a tile and the H drawn from vectors, each 64dp and centered, as Instagram's glyph is. */
    @Test
    fun theMarkIsATileAndAVectorCentered() {
        val mark = parse(LAUNCH_MARK_XML)
        assertEquals("layer-list", mark.documentElement.tagName)
        val items = mark.documentElement.elements("item")
        assertEquals(2, items.size)
        items.forEach { item ->
            assertEquals("center", item.getAttribute("android:gravity"))
            assertEquals("64dp", item.getAttribute("android:width"))
            assertEquals("64dp", item.getAttribute("android:height"))
        }
        assertEquals(1, items[0].elements("gradient").size)
        val vector = items[1].elements("vector").single()
        assertEquals("100", vector.getAttribute("android:viewportWidth"))
        assertEquals(3, vector.elements("path").size)
    }

    /**
     * On every build of Instagram 452, decoded and encoded as Manager does: the launcher wears
     * Instagram's launch theme, the build's Android 12 styles name its icon, and the patched build
     * keeps every resource's number and gains just the theme and the mark, both as written.
     */
    @Test
    fun eachBuildGainsTheThemeAndTheMarkAndKeepsEveryResourceNumber() {
        val builds = builds()
        assertEquals("every build of Instagram ${Fixtures.VERSION} has a fixture", Fixtures.BUILDS, builds.size)
        for ((code, build) in builds) {
            val work = Files.createTempDirectory("hushgram-launch-$code").toFile()
            try {
                val apk = if (build.extension == "apks") File(work, "base.apk").also { base ->
                    ZipFile(build).use { bundle ->
                        bundle.getInputStream(bundle.getEntry("base.apk")).use { input -> base.outputStream().use(input::copyTo) }
                    }
                } else build
                val patched = File(work, "patched.apk")
                val before = decoded(apk, File(work, "patch")) { context ->
                    val manifest = parse(context.get("AndroidManifest.xml").readText())
                    assertEquals("$code: the launchers' theme", setOf("@style/$INSTAGRAM_LAUNCH_THEME"), launcherThemes(manifest))
                    val ids = ids(context.get("res"))
                    context.addLaunchScreen()
                    encoded(context).copyTo(patched)
                    ids
                }
                decoded(patched, File(work, "check")) { context ->
                    val res = context.get("res")
                    val after = ids(res)
                    assertEquals(
                        "$code: added",
                        setOf("style/$LAUNCH_THEME", "drawable/$LAUNCH_MARK"),
                        (after.keys - before.keys).map { after.getValue(it) }.toSet(),
                    )
                    val lost = before.keys - after.keys
                    assertTrue("$code: numbers gone, ${lost.take(5)}", lost.isEmpty())
                    // The patcher writes Instagram's inline drawables ($name__0) back without their
                    // $, whatever resource patch runs. Every other name stays as it was.
                    val renamed = before.filter { (id, name) -> after.getValue(id) != name.replace("/\$", "/") }
                    assertTrue("$code: renamed, ${renamed.entries.take(5)}", renamed.isEmpty())

                    val theme = parse(File(res, "values/styles.xml").readText()).elements("style")
                        .single { it.getAttribute("name") == LAUNCH_THEME }
                    assertEquals("$code: parent", "@style/$INSTAGRAM_LAUNCH_THEME", theme.getAttribute("parent"))
                    assertEquals(
                        "$code: items",
                        listOf(SPLASH_ICON to "@drawable/$LAUNCH_MARK", SPLASH_BRANDING to "@null"),
                        theme.elements("item").map { it.getAttribute("name") to it.textContent.trim() },
                    )
                    val mark = parse(File(res, "drawable/$LAUNCH_MARK.xml").readText()).documentElement
                    assertEquals("$code: the mark", "layer-list", mark.tagName)
                    assertEquals("$code: the mark's H", 3, mark.elements("path").size)
                }
            } finally {
                work.deleteRecursively()
            }
        }
    }

    /** Each build by its version code: the declared one's bundle and the other builds' base APKs. */
    private fun builds(): Map<String, File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        return (declared + Fixtures.otherBuilds()).associateBy { file ->
            (if (file.extension == "apks") file.nameWithoutExtension else file.parentFile.name).substringAfterLast('-')
        }.toSortedMap()
    }

    /**
     * [apk]'s resources decoded in full into [workspace], as Manager decodes them for a resource
     * patch. The patcher keeps the resource context and its decode internal, so they're reached by
     * their JVM names, pinned to morphe-patcher 1.15.1.
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

    /** The resources APK the patcher encodes from [context]'s workspace, as Manager has it encoded after the patches run. */
    private fun encoded(context: ResourcePatchContext): File {
        val result = ResourcePatchContext::class.java.getMethod("get").invoke(context) as PatcherResult.PatchedResources
        return requireNotNull(result.resourcesApk) { "the patcher encoded no resources APK" }
    }

    /** Every resource's type and name by its type and number, from the decoded public.xml. */
    private fun ids(res: File): Map<String, String> {
        val entries = parse(File(res, "values/public.xml").readText()).elements("public")
        val ids = entries.associate { "${it.getAttribute("type")}@${it.getAttribute("id")}" to "${it.getAttribute("type")}/${it.getAttribute("name")}" }
        assertEquals("a number named twice in ${res.parentFile}", entries.size, ids.size)
        return ids
    }

    /**
     * The themes of the activities and aliases that open from the home screen. 450 has fifteen, one
     * for each of its app icons, all aliases of InstagramMainActivity.
     */
    private fun launcherThemes(manifest: Document): Set<String> {
        val application = manifest.elements("application").single()
        val entries = application.elements("activity") + application.elements("activity-alias")
        val launchers = entries.filter { entry ->
            entry.elements("intent-filter").any { filter ->
                filter.elements("action").any { it.getAttribute("android:name") == "android.intent.action.MAIN" } &&
                    filter.elements("category").any { it.getAttribute("android:name") == "android.intent.category.LAUNCHER" }
            }
        }
        return launchers.mapTo(HashSet()) { launcher ->
            val target = launcher.getAttribute("android:targetActivity").ifEmpty { null }
                ?.let { name -> entries.single { it.tagName == "activity" && it.getAttribute("android:name") == name } }
            launcher.getAttribute("android:theme").ifEmpty { target?.getAttribute("android:theme").orEmpty() }
        }
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
