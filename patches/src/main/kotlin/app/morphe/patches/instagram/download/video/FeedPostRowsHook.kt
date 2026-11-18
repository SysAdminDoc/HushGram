/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.reel.BLOCK_HELPER_ANCHOR
import app.morphe.patches.instagram.download.reel.BlockHelper
import app.morphe.patches.instagram.download.reel.blockHelpers
import app.morphe.patches.instagram.download.reel.code
import app.morphe.patches.instagram.download.reel.rewriteStub
import app.morphe.patches.instagram.feed.hold.SPEED_RESET_TAG
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val FEED_POST_ROWS = "$EXTENSION_PACKAGE/download/FeedPostRows;"
internal const val OFFER_POST_ROWS = "$FEED_POST_ROWS->offer(Ljava/lang/Object;Ljava/util/ArrayList;)V"
internal const val POST_BLOCK_OPTION = "$FEED_POST_ROWS->blockOption()Ljava/lang/Object;"
internal const val BLOCK_POST = "$FEED_POST_ROWS->block(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val POST_SPEED_OPTION = "$FEED_POST_ROWS->speedOption()Ljava/lang/Object;"
internal const val PICK_POST_SPEED = "$FEED_POST_ROWS->speed(Ljava/lang/Object;Ljava/lang/Object;Landroid/app/Activity;)V"
internal const val FEED_PLAYER_MADE = "$FEED_POST_ROWS->controllerMade(Ljava/lang/Object;)V"

private const val MENU_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val FUNCTION_ZERO = "Lkotlin/jvm/functions/Function0;"
private const val FUNCTION_INVOKE = "$FUNCTION_ZERO->invoke()Ljava/lang/Object;"

/**
 * What Playback speed in a feed post's menu needs of Instagram's hold for 2x. Its controller's
 * [made] constructor is where the extension learns of it. The controller's [function] answers the
 * feed's player manager, of type [manager], whose [playing] says which post it has loaded. The
 * manager's [player] holds [inner], and [setSpeed] on that is the call the hold itself makes.
 */
internal class FeedPlayerSite(
    val made: Method,
    val function: FieldReference,
    val manager: String,
    val playing: MethodReference,
    val player: FieldReference,
    val inner: FieldReference,
    val setSpeed: MethodReference,
)

/** What Block in a feed post's menu needs: Instagram's sheet, and the menu's [activity] and [session]. */
internal class FeedBlockSheet(val helper: BlockHelper, val activity: String, val session: String)

/** Logs why [row] won't show and answers null. The rest of Download any video goes on without it. */
private fun leftOut(row: String, detail: String): Nothing? {
    patchLog.warning("Download any video: $row in a feed post's menu is left out, $detail")
    return null
}

private val Instruction.field get() = (this as? ReferenceInstruction)?.reference as? FieldReference
private val Instruction.call get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private val ClassDef.isPublic get() = AccessFlags.PUBLIC.isSet(accessFlags)

private fun BytecodePatchContext.reachable(field: FieldReference): Boolean {
    val owner = classDefByOrNull(field.definingClass) ?: return false
    return owner.isPublic && owner.fields.any { it.name == field.name && it.type == field.type && AccessFlags.PUBLIC.isSet(it.accessFlags) }
}

private fun BytecodePatchContext.reachable(call: MethodReference): Boolean {
    val owner = classDefByOrNull(call.definingClass) ?: return false
    return owner.isPublic && owner.methods.any {
        it.name == call.name && it.returnType == call.returnType && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
    }
}

/**
 * Finds [FeedPlayerSite] by walking the method that ends a hold for 2x, the one static `(controller, Z)Z`
 * loading [SPEED_RESET_TAG]. It asks the controller's function for the manager, casts it, and goes
 * through two fields to the `(F)V` that puts the speed back. Answers null, having logged why, when
 * anything is missing or the extension couldn't reach it, so the row just isn't offered.
 */
internal fun BytecodePatchContext.findFeedPlayer(): FeedPlayerSite? {
    val row = "Playback speed"
    val resets = classesLoadingString(SPEED_RESET_TAG).flatMap { owner ->
        owner.methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
                it.parameterTypes.map(Any::toString) == listOf(owner.type, "Z") && loadsString(it, SPEED_RESET_TAG)
        }
    }
    val reset = resets.singleOrNull() ?: return leftOut(row, "expected one method ending a hold for 2x, found ${resets.size}")
    val controller = classDefByOrNull(reset.definingClass) ?: return leftOut(row, "${reset.definingClass} is missing")
    val code = reset.code()

    val asked = code.indexOfFirst { it.opcode == Opcode.INVOKE_INTERFACE && it.call?.toString() == FUNCTION_INVOKE }
    if (asked < 0) return leftOut(row, "the end of a hold never asks for the player")
    val function = code.take(asked).lastOrNull { it.opcode == Opcode.IGET_OBJECT }?.field
        ?.takeIf { it.definingClass == controller.type && it.type == FUNCTION_ZERO }
        ?: return leftOut(row, "no function field is read before the player is asked for")
    fun after(index: Int, wanted: (Instruction) -> Boolean) = (index + 1 until code.size).firstOrNull { wanted(code[it]) }

    val castAt = after(asked) { it.opcode == Opcode.CHECK_CAST } ?: return leftOut(row, "the player's manager is never cast")
    val manager = ((code[castAt] as ReferenceInstruction).reference as TypeReference).type
    val playerAt = after(castAt) { it.opcode == Opcode.IGET_OBJECT && it.field?.definingClass == manager }
        ?: return leftOut(row, "$manager's player is never read")
    val player = code[playerAt].field!!
    val innerAt = after(playerAt) { it.opcode == Opcode.IGET_OBJECT && it.field?.definingClass == player.type }
        ?: return leftOut(row, "${player.type}'s inner player is never read")
    val inner = code[innerAt].field!!
    val setAt = after(innerAt) {
        it.opcode == Opcode.INVOKE_VIRTUAL && it.call?.definingClass == inner.type && it.call?.returnType == "V" &&
            it.call?.parameterTypes?.map(Any::toString) == listOf("F")
    } ?: return leftOut(row, "the speed is never set on ${inner.type}")
    val setSpeed = code[setAt].call!!

    val managerClass = classDefByOrNull(manager) ?: return leftOut(row, "$manager is missing")
    val playing = managerClass.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.returnType == MEDIA &&
            it.parameterTypes.map(Any::toString) == listOf(manager)
    }.singleOrNull() ?: return leftOut(row, "expected one public getter of the post $manager has loaded")
    val made = controller.methods.singleOrNull { it.name == "<init>" }
        ?: return leftOut(row, "expected one constructor of ${controller.type}")
    if (made.code().count { it.opcode == Opcode.RETURN_VOID } != 1) return leftOut(row, "expected ${controller.type}'s constructor to return once")

    if (!managerClass.isPublic || !reachable(function) || !reachable(player) || !reachable(inner) || !reachable(setSpeed)) {
        return leftOut(row, "the extension can't reach the feed's player")
    }
    return FeedPlayerSite(made, function, manager, playing, player, inner, setSpeed)
}

/**
 * Finds [FeedBlockSheet]: the one block sheet helper, and the feed menu's public [activity] and one
 * public session field. Answers null, having logged why, when one is missing.
 */
internal fun BytecodePatchContext.findFeedBlockSheet(menu: ClassDef, activity: Field): FeedBlockSheet? {
    val row = "Block"
    val helpers = blockHelpers()
    val helper = helpers.singleOrNull()
        ?: return leftOut(row, "expected one helper class loading \"$BLOCK_HELPER_ANCHOR\", found ${helpers.size}")
    val session = menu.fields.singleOrNull { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == MENU_SESSION }
        ?.takeIf { AccessFlags.PUBLIC.isSet(it.accessFlags) }
        ?: return leftOut(row, "expected one public session field in ${menu.type}")
    if (!menu.isPublic || !AccessFlags.PUBLIC.isSet(activity.accessFlags)) return leftOut(row, "the extension can't reach ${menu.type}")
    return FeedBlockSheet(
        helper,
        "${menu.type}->${activity.name}:${activity.type}",
        "${menu.type}->${session.name}:${session.type}",
    )
}

/**
 * Playback speed and Block in a feed post's menu (#743). A tap on either goes to the extension, ahead
 * of the rest of the menu's handler. The menu's builder asks the extension for the rows, which
 * [offerDownloadOnEveryVideo] has it do, and the bridges written here are how it reaches Instagram.
 *
 * Neither row is worth stopping Download any video for. Each is checked on its own, before anything
 * of it changes, and one that can't work on this build is left as the extension's stubs have it,
 * which never offer the row.
 */
internal fun BytecodePatchContext.addFeedPostRows(page: PageIndex) {
    val helper = classDefBy(page.menuState.definingClass)
    val tap = findMenuHandler(helper)
    val type = helper.type
    val post = "$type->${tap.media.name}($type)$MEDIA"
    val sheet = findFeedBlockSheet(helper, tap.activity)
    val player = findFeedPlayer()

    val menu = mutable(tap.handler)
    menu.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p1
            invoke-static {}, $POST_BLOCK_OPTION
            move-result-object v1
            if-eqz v1, :speed
            if-ne v0, v1, :speed
            move-object/from16 v0, p0
            invoke-static { v0 }, $post
            move-result-object v1
            invoke-static { v1, v0 }, $BLOCK_POST
            return-void
            :speed
            invoke-static {}, $POST_SPEED_OPTION
            move-result-object v1
            if-eqz v1, :next
            if-ne v0, v1, :next
            move-object/from16 v0, p0
            invoke-static { v0 }, $post
            move-result-object v1
            iget-object v2, v0, $type->${tap.activity.name}:${tap.activity.type}
            iget-object v0, v0, ${page.menuState}
            invoke-static { v1, v0, v2 }, $PICK_POST_SPEED
            return-void
        """,
        ExternalLabel("next", menu.getInstruction(0)),
    )

    val rows = mutableClassDefBy(FEED_POST_ROWS)
    fun stub(name: String) = rows.methods.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }

    if (sheet != null) {
        rewriteStub(rows, stub("blockReady"), 1, "const/4 v0, 0x1\nreturn v0")
        // The same call as the Block icon beside a reel: the activity twice, as the context and the
        // activity, then the session, the user, the screen's module and the user's id.
        rewriteStub(
            rows, stub("blockRun"), 7,
            """
                check-cast p0, $type
                check-cast p1, $USER
                sget-object v0, ${sheet.helper.instance}
                iget-object v1, p0, ${sheet.activity}
                move-object v2, v1
                iget-object v3, p0, ${sheet.session}
                move-object v4, p1
                move-object v5, p2
                move-object v6, p3
                invoke-virtual/range { v0 .. v6 }, ${sheet.helper.open}
                const/4 v0, 0x0
                return-object v0
            """,
        )
    }

    if (player != null) {
        rewriteStub(
            rows, stub("managerRun"), 0,
            """
                check-cast p0, ${player.function.definingClass}
                iget-object p0, p0, ${player.function}
                invoke-interface { p0 }, $FUNCTION_INVOKE
                move-result-object p0
                return-object p0
            """,
        )
        rewriteStub(
            rows, stub("mediaRun"), 0,
            """
                check-cast p0, ${player.manager}
                invoke-static { p0 }, ${player.playing}
                move-result-object p0
                return-object p0
            """,
        )
        rewriteStub(
            rows, stub("playerRun"), 0,
            """
                check-cast p0, ${player.manager}
                iget-object p0, p0, ${player.player}
                if-eqz p0, :none
                iget-object p0, p0, ${player.inner}
                return-object p0
                :none
                return-object p0
            """,
        )
        rewriteStub(
            rows, stub("speedRun"), 0,
            """
                check-cast p0, ${player.inner.type}
                invoke-virtual { p0, p1 }, ${player.setSpeed}
                return-void
            """,
        )
        val made = mutable(player.made)
        made.addInstructionsAtControlFlowLabel(
            made.code().indexOfFirst { it.opcode == Opcode.RETURN_VOID },
            "invoke-static/range { p0 .. p0 }, $FEED_PLAYER_MADE",
        )
    }
}
