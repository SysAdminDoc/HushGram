/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class PlayerQueriesTest {
    /** The query goes in Instagram's own queries, after what's there, and nothing there moves. */
    @Test
    fun theQueryJoinsInstagramsQueries() {
        val manifest = manifest(
            """<queries><intent><action android:name="android.intent.action.VIEW"/><data android:scheme="*"/></intent>""" +
                """<package android:name="com.whatsapp"/></queries>""",
        )

        queryForPlayers(manifest)

        assertEquals(1, manifest.getElementsByTagName("queries").length)
        assertEquals("Instagram's package query stays", 1, manifest.getElementsByTagName("package").length)
        assertEquals(listOf("*", PLAYER_SCHEME), attributes(manifest, "data", "android:scheme"))
        assertEquals(listOf("", PLAYER_TYPES), attributes(manifest, "data", "android:mimeType"))
        assertEquals(listOf(PLAYER_ACTION, PLAYER_ACTION), attributes(manifest, "action", "android:name"))
        val added = manifest.getElementsByTagName("intent").item(1) as Element
        assertEquals("queries", (added.parentNode as Element).tagName)
    }

    /** A manifest with no queries gets them, under the manifest itself. */
    @Test
    fun aManifestWithoutQueriesGetsThem() {
        val manifest = manifest("")

        queryForPlayers(manifest)

        val queries = manifest.getElementsByTagName("queries")
        assertEquals(1, queries.length)
        assertEquals("manifest", (queries.item(0).parentNode as Element).tagName)
        assertEquals(listOf(PLAYER_SCHEME), attributes(manifest, "data", "android:scheme"))
    }

    /** Run twice, or on a manifest that has the query, it adds nothing. */
    @Test
    fun theQueryGoesInOnce() {
        val manifest = manifest("")

        queryForPlayers(manifest)
        queryForPlayers(manifest)

        assertEquals(1, manifest.getElementsByTagName("intent").length)
    }

    private fun manifest(inside: String): Document {
        val xml = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.instagram.android">""" +
            """<uses-permission android:name="android.permission.INTERNET"/>$inside<application/></manifest>"""
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }

    private fun attributes(manifest: Document, tag: String, name: String): List<String> {
        val list = manifest.getElementsByTagName(tag)
        return (0 until list.length).map { (list.item(it) as Element).getAttribute(name) }
    }
}
