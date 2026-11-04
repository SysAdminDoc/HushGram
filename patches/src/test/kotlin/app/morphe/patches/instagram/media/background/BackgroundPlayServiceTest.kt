/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.patcher.patch.PatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class BackgroundPlayServiceTest {
    /** Instagram asks for both permissions already, so only the service goes in, under the application. */
    @Test
    fun instagramsManifestGetsOnlyTheService() {
        val manifest = manifest(SERVICE_PERMISSIONS.joinToString("") { """<uses-permission android:name="$it"/>""" })

        addBackgroundPlayService(manifest)

        assertEquals(listOf("android.permission.INTERNET") + SERVICE_PERMISSIONS, permissions(manifest))
        val service = ours(manifest).single()
        assertEquals("application", (service.parentNode as Element).tagName)
        assertEquals(BACKGROUND_PLAY_SERVICE, service.getAttribute("android:name"))
        assertEquals("not for other apps", "false", service.getAttribute("android:exported"))
        assertEquals("mediaPlayback", service.getAttribute("android:foregroundServiceType"))
        assertEquals("Instagram's own service stays", 2, (service.parentNode as Element).childNodes.length)
    }

    /** A manifest without the permissions gets both, ahead of the application. */
    @Test
    fun missingPermissionsGoInBeforeTheApplication() {
        val manifest = manifest("")

        addBackgroundPlayService(manifest)

        assertEquals(listOf("android.permission.INTERNET") + SERVICE_PERMISSIONS, permissions(manifest))
        val tags = elements(manifest.documentElement).map { it.tagName }
        assertEquals(listOf("uses-permission", "uses-permission", "uses-permission", "application"), tags)
    }

    /** Run twice it adds nothing more. */
    @Test
    fun theServiceGoesInOnce() {
        val manifest = manifest("")

        addBackgroundPlayService(manifest)
        addBackgroundPlayService(manifest)

        assertEquals(1, ours(manifest).size)
        assertEquals(3, permissions(manifest).size)
    }

    private fun ours(manifest: Document): List<Element> {
        val nodes = manifest.getElementsByTagName("service")
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("android:name") == BACKGROUND_PLAY_SERVICE }
    }

    /** A manifest with no application, or two, is refused before it changes. */
    @Test
    fun oneApplicationOnly() {
        for (applications in listOf("", "<application/><application/>")) {
            val xml = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.instagram.android">""" +
                "$applications</manifest>"
            val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
            try {
                addBackgroundPlayService(manifest)
                fail("took a manifest with ${applications.length} characters of applications")
            } catch (refused: PatchException) {
                assertTrue(refused.message.orEmpty(), refused.message.orEmpty().contains("expected one application"))
            }
            assertEquals(0, manifest.getElementsByTagName("uses-permission").length)
        }
    }

    private fun permissions(manifest: Document): List<String> {
        val nodes = manifest.getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }
    }

    private fun elements(parent: Element): List<Element> =
        (0 until parent.childNodes.length).map { parent.childNodes.item(it) }.filterIsInstance<Element>()

    private fun manifest(permissions: String): Document {
        val xml = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.instagram.android">""" +
            """<uses-permission android:name="android.permission.INTERNET"/>$permissions""" +
            """<application><service android:name="com.instagram.Other"/></application></manifest>"""
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }
}
