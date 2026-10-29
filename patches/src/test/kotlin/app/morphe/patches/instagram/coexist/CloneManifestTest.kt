/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.coexist

import app.morphe.patcher.patch.PatchException
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class CloneManifestTest {
    private val clone = "com.hush.second"

    private fun Document.elements(tag: String = "*"): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Document.values(attribute: String, tag: String = "*"): List<String> =
        elements(tag).map { it.getAttribute(attribute) }.filter { it.isNotEmpty() }

    /** Every element and attribute, in order, so two readings show whether anything changed. */
    private fun Document.text(): String = buildString {
        fun dump(element: Element, depth: Int) {
            append("  ".repeat(depth)).append(element.tagName)
            val attributes = element.attributes
            (0 until attributes.length).map { attributes.item(it) }.sortedBy { it.nodeName }
                .forEach { append(' ').append(it.nodeName).append("=\"").append(it.nodeValue).append('"') }
            append('\n')
            val children = element.childNodes
            (0 until children.length).map { children.item(it) }.filterIsInstance<Element>().forEach { dump(it, depth + 1) }
        }
        dump(documentElement, 0)
    }

    private fun applicationAuthorities(manifest: Document) = manifest.elements("application").single()
        .getElementsByTagName("provider").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
        .flatMap { it.getAttribute("android:authorities").split(';') }

    /**
     * Each build's manifest moves whole: package, launcher names, every provider
     * authority, Instagram's three permissions by full name and their uses, every task affinity,
     * the push category and the custom tab host. Actions, meta-data names, the Pages app's provider
     * Instagram queries and a permission Instagram uses but doesn't declare stay.
     */
    @Test fun everyBuildsManifestMovesWhole() {
        for ((build, file) in CloneFixtures.builds()) {
            val manifest = CloneFixtures.manifest(file)
            val facts = readCloneManifest(manifest)
            val label = facts.label
            assertEquals("[$build] authorities", 24, facts.authorities.size)
            assertEquals("[$build] permissions", setOf(
                "com.instagram.android.permission.SYSTEM_ONLY",
                "com.instagram.android.permission.CROSS_PROCESS_BROADCAST_MANAGER",
                "com.instagram.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ), facts.permissions)
            val actions = manifest.values("android:name", "action")
            val metadata = manifest.values("android:name", "meta-data")
            val queried = manifest.elements("queries").flatMap { it.getElementsByTagName("provider").let { nodes ->
                (0 until nodes.length).map { at -> (nodes.item(at) as Element).getAttribute("android:authorities") } } }
            val before = applicationAuthorities(manifest)
            val providerClasses = manifest.values("android:name", "provider")

            val renamed = renameManifest(manifest, clone, "Second")

            assertEquals("[$build]", ManifestRename(labels = 20, authorities = 25, permissions = 3, permissionUses = 3,
                affinities = 19, categories = 2, hosts = 1, processes = 0, classNames = 0), renamed)
            assertEquals(clone, manifest.documentElement.getAttribute("package"))
            assertEquals("@string/$CLONE_LABEL_RESOURCE", manifest.elements("application").single().getAttribute("android:label"))
            assertTrue("[$build] the old label is gone", label !in manifest.values("android:label"))
            assertEquals("[$build] each authority moves in place", before.map { cloneAuthority(it, clone) }, applicationAuthorities(manifest))
            assertTrue("[$build] every authority is under the copy's package", applicationAuthorities(manifest).all { it.startsWith("$clone.") })
            assertEquals("[$build] the Pages app's provider", queried, manifest.elements("queries").flatMap { it.getElementsByTagName("provider")
                .let { nodes -> (0 until nodes.length).map { at -> (nodes.item(at) as Element).getAttribute("android:authorities") } } })
            assertEquals("[$build] declared permissions", setOf(
                "$clone.permission.SYSTEM_ONLY", "$clone.permission.CROSS_PROCESS_BROADCAST_MANAGER",
                "$clone.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", ".permission.RECEIVE_ADM_MESSAGE",
            ), manifest.values("android:name", "permission").toSet())
            // Some providers are named by their class, which stays: only a provider's class name may match.
            assertEquals("[$build] provider classes stay", providerClasses, manifest.values("android:name", "provider"))
            val everyName = manifest.elements().flatMap { element ->
                (0 until element.attributes.length).map { element.attributes.item(it) }
                    .filterNot { element.tagName == "provider" && it.nodeName == "android:name" }
                    .map { it.nodeValue }
            }
            assertTrue("[$build] no permission Instagram declares is named any more", everyName.none { it in facts.permissions })
            assertTrue("[$build] no authority Instagram declares is named any more", everyName.none { it in facts.authorities })
            assertTrue("[$build] the undeclared shortcut permission stays",
                "com.instagram.android.permission.CREATE_SHORTCUT" in manifest.values("android:permission"))
            assertTrue("[$build] every task affinity is the copy's", manifest.values("android:taskAffinity").all { it.startsWith("$clone.") })
            assertEquals("[$build] push category", listOf(clone, clone),
                manifest.values("android:name", "category").filter { it == clone || it == INSTAGRAM })
            assertTrue("[$build] custom tab host", "cct.$clone" in manifest.values("android:host", "data"))
            assertEquals("[$build] actions stay", actions, manifest.values("android:name", "action"))
            assertEquals("[$build] meta-data names stay", metadata, manifest.values("android:name", "meta-data"))
        }
    }

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())

    private fun small(
        packageName: String = INSTAGRAM,
        permission: String = "com.instagram.android.permission.X",
        application: String = """<application android:label="@string/app">
            <activity android:name=".Main" android:label="@string/app" android:taskAffinity="com.instagram.android.Main"
                android:permission="com.instagram.android.permission.X"/>
            <activity android:name="com.instagram.Other" android:label="@string/other" android:process=":mqtt"/>
            <provider android:name="a.P" android:authorities="com.instagram.android.p;com.instagram.fileprovider"/>
        </application>""",
        extra: String = "",
    ) = parse("""<manifest package="$packageName"><permission android:name="$permission"/>
        <uses-permission android:name="$permission"/>$extra$application</manifest>""")

    @Test fun aSmallManifestMovesAndARelativeClassNameIsWrittenOut() {
        val manifest = small()
        assertEquals(ManifestRename(labels = 2, authorities = 2, permissions = 1, permissionUses = 2, affinities = 1,
            categories = 0, hosts = 0, processes = 0, classNames = 1), renameManifest(manifest, clone, "Second"))
        val main = manifest.elements("activity").first()
        assertEquals("com.instagram.android.Main", main.getAttribute("android:name"))
        assertEquals("$clone.Main", main.getAttribute("android:taskAffinity"))
        assertEquals("$clone.permission.X", main.getAttribute("android:permission"))
        assertEquals("@string/other", manifest.elements("activity")[1].getAttribute("android:label"))
        assertEquals(":mqtt", manifest.elements("activity")[1].getAttribute("android:process"))
        assertEquals("$clone.p;$clone.com.instagram.fileprovider", manifest.elements("provider").single().getAttribute("android:authorities"))
    }

    @Test fun theAppAndLauncherLabelsResolveToAStringResource() {
        for (label in listOf(DEFAULT_LABEL, "Second  Instagram", "사진 & Instagram")) {
            val manifest = small()
            renameManifest(manifest, clone, label)
            val strings = parse("""<resources><string name="other">Unchanged</string></resources>""")
            addCloneLabel(strings, label)
            val resource = strings.elements("string").last()
            val reference = "@string/${resource.getAttribute("name")}"
            assertEquals(reference, manifest.elements("application").single().getAttribute("android:label"))
            assertEquals(reference, manifest.elements("activity").first().getAttribute("android:label"))
            assertEquals("false", resource.getAttribute("translatable"))
            assertEquals("\"$label\"", resource.textContent)
            assertEquals("Unchanged", strings.elements("string").first().textContent)
            assertEquals("@string/other", manifest.elements("activity")[1].getAttribute("android:label"))
        }
    }

    @Test fun aManifestThisCantMoveWholeIsRefusedUntouched() {
        val refused = mapOf(
            "the package" to small(packageName = "com.instagram.barcelona"),
            "a permission outside Instagram's package" to small(permission = "com.facebook.permission.X"),
            "no application" to small(application = ""),
            "no label" to small(application = """<application><provider android:name="a.P" android:authorities="a.b"/></application>"""),
            "no provider" to small(application = """<application android:label="@string/app"/>"""),
            "a provider without an authority" to small(application = """<application android:label="@string/app"><provider android:name="a.P"/></application>"""),
            "a permission group" to small(extra = """<permission-group android:name="com.instagram.android.group"/>"""),
        )
        for ((why, manifest) in refused) {
            val before = manifest.text()
            assertThrows(why, PatchException::class.java) { renameManifest(manifest, clone, "Second") }
            assertEquals("$why: refused after a change", before, manifest.text())
        }
        for ((name, label) in listOf("nodots" to "Second", INSTAGRAM to "Second", clone to "@string/app")) {
            val manifest = small()
            val before = manifest.text()
            assertThrows("$name, $label", PatchException::class.java) { renameManifest(manifest, name, label) }
            assertEquals(before, manifest.text())
        }
    }
}
