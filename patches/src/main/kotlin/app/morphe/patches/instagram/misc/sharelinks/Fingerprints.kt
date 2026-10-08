/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.sharelinks

import app.morphe.patcher.Fingerprint

/**
 * The parser of the server's answer to "copy link" on a post or reel. The link is the answer's
 * "permalink" field, and the model it fills is named by its GraphQL type, "XDTPermalinkResponse".
 * Parser classes are Redex names; the method name is an interface method, so it's kept.
 */
internal object PermalinkParserFingerprint : Fingerprint(
    name = "unsafeParseFromJson",
    returnType = "Ljava/lang/Object;",
    strings = listOf("permalink", PERMALINK_TYPE),
)

internal const val PERMALINK_TYPE = "XDTPermalinkResponse"

/**
 * The same for a story's share link, the [STORY_SHARE_URL_FIELD] field. 450 asks a pool of shared
 * strings for the field's name, so the patch checks that name once it has the parser.
 */
internal object StoryShareUrlParserFingerprint : Fingerprint(
    name = "unsafeParseFromJson",
    returnType = "Ljava/lang/Object;",
    strings = listOf(STORY_SHARE_URL_TYPE),
)

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
