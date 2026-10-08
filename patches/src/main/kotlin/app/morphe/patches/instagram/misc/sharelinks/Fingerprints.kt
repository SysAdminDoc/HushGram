/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.sharelinks

import app.morphe.patcher.Fingerprint

/**
 * The server's answer to "copy link" on a post or reel: the link is its [PERMALINK_FIELD] field,
 * and the model its parser fills is named by its GraphQL type, [PERMALINK_TYPE]. Parser classes are
 * Redex names; the parser's method name, unsafeParseFromJson, is an interface method, so it's kept.
 */
internal const val PERMALINK_FIELD = "permalink"

internal const val PERMALINK_TYPE = "XDTPermalinkResponse"

/** The field of a story's share link, read by its own parser, which names [STORY_SHARE_URL_TYPE]. */
internal const val STORY_SHARE_URL_FIELD = "story_item_to_share_url"

internal const val STORY_SHARE_URL_TYPE = "XDTStoryItemThirdPartySharingUrlResponse"

/**
 * The main app's handler for the messages Instagram's in-app browser sends it. The browser's own
 * code sits in a split the patcher doesn't rewrite, but its menu's Share and Copy link each send the
 * page's address here, and the handler logs one of these two lines for a message that carries none.
 */
internal object BrowserMenuHandlerFingerprint : Fingerprint(
    name = "handleMessage",
    returnType = "V",
    parameters = listOf("Landroid/os/Message;"),
    strings = listOf(BROWSER_SHARE_FAILURE, BROWSER_COPY_FAILURE),
)

internal const val BROWSER_SHARE_FAILURE =
    "failed to retrieve shareUrl, Message object is not a String. Defaulting to empty url"

internal const val BROWSER_COPY_FAILURE =
    "failed to retrieve copyUrl, Message object is not a String. Defaulting to empty url"
