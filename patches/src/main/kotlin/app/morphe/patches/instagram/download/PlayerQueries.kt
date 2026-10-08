/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The action, scheme and types of the view Open in another player hands a player. */
internal const val PLAYER_ACTION = "android.intent.action.VIEW"
internal const val PLAYER_SCHEME = "https"
internal const val PLAYER_TYPES = "video/*"

private fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

/** Whether [intent], an intent the manifest queries for, is the view of a video at a web address. */
private fun isPlayerView(intent: Element): Boolean =
    intent.children("action").any { it.getAttribute("android:name") == PLAYER_ACTION } &&
        intent.children("data").any {
            it.getAttribute("android:scheme") == PLAYER_SCHEME && it.getAttribute("android:mimeType") == PLAYER_TYPES
        }

/**
 * Lets Instagram see the phone's video players, so Open in another player can tell when there are
 * none before it opens Android's chooser. From Android 11 an app sees only the apps its manifest
 * queries for, and Instagram's own view query names no type, which leaves out a player that only
 * takes video. [manifest] gets one query for the view of a video at a web address, in its queries
 * element, made when it has none. A manifest that has the query already is left alone.
 */
internal fun queryForPlayers(manifest: Document) {
    val root = manifest.documentElement
    val queries = root.children("queries")
    if (queries.any { block -> block.children("intent").any(::isPlayerView) }) return
    val block = queries.firstOrNull() ?: manifest.createElement("queries").also(root::appendChild)
    val intent = manifest.createElement("intent")
    intent.appendChild(manifest.createElement("action").apply { setAttribute("android:name", PLAYER_ACTION) })
    intent.appendChild(
        manifest.createElement("data").apply {
            setAttribute("android:scheme", PLAYER_SCHEME)
            setAttribute("android:mimeType", PLAYER_TYPES)
        },
    )
    block.appendChild(intent)
}

/**
 * A resource patch, so Manager decodes the manifest and writes the edited copy back, as Remove the
 * advertising ID's permission patch does. The reel and feed video downloads depend on it, since
 * both offer Open in another player.
 */
internal val playerQueriesPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use(::queryForPlayers)
    }
}
