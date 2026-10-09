/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide group buttons on the share sheet"
internal const val HIDE_GROUP_BUTTON = "$EXTENSION_PACKAGE/share/ShareSheet;->hideGroupButton()Z"
internal const val HIDE_GROUP_ACTION = "$EXTENSION_PACKAGE/share/ShareSheet;->hideGroupAction()Z"
internal const val HIDE_GROUP_SEND = "$EXTENSION_PACKAGE/share/ShareSheet;->hideGroupSend()Z"

/** The share sheet, and the fields it keeps its New group button and its search box in. All kept names. */
internal const val SHARE_SHEET = "Linstagram/features/direct/fragment/sharesheet/DirectShareSheetFragment;"
internal const val GROUP_BUTTON = "createGroupButton"
internal const val STICKY_SEARCH_BOX = "stickySearchBox"

/** The search box, and its kept setters for the action it can show at its end. */
internal const val SEARCH_BOX = "Lcom/instagram/igds/components/search/IgdsInlineSearchBox;"
internal const val ACTION_VISIBLE = "setVisibilityOfCustomActionButton"
internal const val ACTION_ENABLED = "setCustomActionEnabled"

/** The bar under the sheet once you pick people, and the button in it that sends to them as a group. Kept names. */
internal const val COMPOSER = "Lcom/instagram/direct/fragment/sharesheet/view/DirectShareSheetFragmentMessageComposerViewBinder;"
internal const val GROUP_SEND_BUTTON = "Lcom/instagram/direct/fragment/sharesheet/groupsendbutton/shared/GroupSendButton;"
internal const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

private const val VIEW = "Landroid/view/View;"
private const val VIEW_STUB = "Landroid/view/ViewStub;"
private const val LAYOUT_PARAMS = "Landroid/view/ViewGroup\$LayoutParams;"

/**
 * Takes the group buttons off the share sheet. In the default selection with its switch off:
 * they're buttons some people use, and asked to go by others, so leaving them out is the user's pick.
 */
@Suppress("unused")
val hideShareSheetGroupPatch = bytecodePatch(
    name = "Hide group buttons on the share sheet",
    description = "Takes the New group button away from beside the share sheet's search bar, and the button that " +
        "sends to the people you picked as one group, so they get it one by one. You can still start a group from " +
        "your messages. Off until you turn it on. Switch: HushGram settings > Sharing.",
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("shareSheet")
        val sheet = findShareSheet()
        val send = findGroupSend()
        guardGroupButton(sheet.build)
        guardGroupAction(sheet.reveal)
        guardGroupSend(send)
        enableStatus("shareSheet")
    }
}

/** A method by its class, name and parameter types. */
internal class SheetMethod(val type: String, val name: String, val parameters: List<String>)

/** Where the share sheet builds its New group button from a stub, and where it reveals the button. */
internal class ShareSheetSites(val build: SheetMethod, val reveal: SheetMethod)

/**
 * Finds the share sheet's two New group methods by the [GROUP_BUTTON] field. The build is the one
 * method writing it: it inflates a stub it takes and keeps what comes out. The reveal is the one
 * no-argument method reading it that, when there's no button, shows the search box's action with
 * [ACTION_VISIBLE] instead. Checks the search box field and both setters the guard calls are there.
 * Fails when any of that isn't so, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findShareSheet(): ShareSheetSites {
    val sheet = classDefByOrNull(SHARE_SHEET) ?: refuse("$SHARE_SHEET isn't in this Instagram")
    if (sheet.fields.none { it.name == GROUP_BUTTON && it.type == VIEW }) refuse("$SHARE_SHEET keeps no $GROUP_BUTTON view")
    if (sheet.fields.none { it.name == STICKY_SEARCH_BOX && it.type == SEARCH_BOX && !AccessFlags.STATIC.isSet(it.accessFlags) }) {
        refuse("$SHARE_SHEET keeps no $STICKY_SEARCH_BOX")
    }
    val box = classDefByOrNull(SEARCH_BOX) ?: refuse("$SEARCH_BOX isn't in this Instagram")
    for (setter in listOf(ACTION_VISIBLE, ACTION_ENABLED)) {
        box.methods.singleOrNull {
            it.name == setter && it.parameterTypes.map(CharSequence::toString) == listOf("Z") && it.returnType == "V" &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: refuse("$SEARCH_BOX has no public $setter(Z)V")
    }

    val builds = sheet.methods.filter { it.touchesGroupButton(Opcode.IPUT_OBJECT) }
    val build = builds.singleOrNull() ?: refuse("$SHARE_SHEET keeps its $GROUP_BUTTON in ${builds.size} methods, expected one")
    val buildAt = "$SHARE_SHEET->${build.name}"
    if (build.isStatic() || build.returnType != "V" || VIEW_STUB !in build.parameters() ||
        build.implementation!!.instructions.none { it.calls(VIEW_STUB, "inflate") }
    ) {
        refuse("$buildAt isn't a build of the button from a stub")
    }
    if (build.locals() < 3) refuse("$buildAt has fewer than three registers of its own")
    // this sits just past the locals, and the guard reads a field of it, which reaches v15 at most.
    if (build.locals() > 15) refuse("$buildAt keeps this past v15, out of reach of a field read")

    val reveals = sheet.methods.filter {
        !it.isStatic() && it.returnType == "V" && it.parameterTypes.isEmpty() &&
            it.touchesGroupButton(Opcode.IGET_OBJECT) && it.implementation!!.instructions.any { call -> call.calls(SEARCH_BOX, ACTION_VISIBLE) }
    }
    val reveal = reveals.singleOrNull()
        ?: refuse("expected one method of $SHARE_SHEET revealing its $GROUP_BUTTON or the search box's action, found ${reveals.size}")
    if (reveal.locals() !in 2..15) refuse("$SHARE_SHEET->${reveal.name} has fewer than two registers of its own, or keeps this past v15")

    return ShareSheetSites(build.site(SHARE_SHEET), reveal.site(SHARE_SHEET))
}

/** Where the bar under the sheet shows its send-as-group button, the session's parameter register there, and where it hides the button. */
internal class GroupSendSites(val show: SheetMethod, val session: Int, val hide: SheetMethod)

/**
 * Finds [COMPOSER]'s two send-as-group methods. The show is the one method calling a method of
 * [GROUP_SEND_BUTTON], which sets the button up for the people picked and makes it visible; it
 * takes the session. The hide is the one other method taking just the session and reading a
 * [GROUP_SEND_BUTTON] field, which Instagram calls when the pick no longer gets the button.
 */
internal fun BytecodePatchContext.findGroupSend(): GroupSendSites {
    val composer = classDefByOrNull(COMPOSER) ?: refuse("$COMPOSER isn't in this Instagram")
    val shows = composer.methods.filter { method ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == GROUP_SEND_BUTTON
        } == true
    }
    val show = shows.singleOrNull() ?: refuse("expected one method of $COMPOSER setting up its $GROUP_SEND_BUTTON, found ${shows.size}")
    val showAt = "$COMPOSER->${show.name}"
    if (show.isStatic() || show.returnType != "V" || show.parameters().count { it == USER_SESSION } != 1) {
        refuse("$showAt isn't an instance method taking the session once")
    }
    if (show.locals() < 2) refuse("$showAt has fewer than two registers of its own")
    // A parameter's p-register: this is p0, and each parameter before it takes one, or two for a long or a double.
    val session = 1 + show.parameters().takeWhile { it != USER_SESSION }.sumOf { if (it == "J" || it == "D") 2 else 1 }

    val hides = composer.methods.filter { method ->
        method.name != show.name && !method.isStatic() && method.returnType == "V" && method.parameters() == listOf(USER_SESSION) &&
            method.implementation?.instructions?.any {
                it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == GROUP_SEND_BUTTON
            } == true
    }
    val hide = hides.singleOrNull() ?: refuse("expected one method of $COMPOSER hiding its $GROUP_SEND_BUTTON, found ${hides.size}")
    return GroupSendSites(show.site(COMPOSER), session, hide.site(COMPOSER))
}

/**
 * Asks [HIDE_GROUP_BUTTON] first thing in the build, and on a yes returns before the stub is
 * inflated, so it takes no room. Beside a button of its own the search box is a small square, so it
 * gets the full width first, as Instagram gives it where New group sits inside the box instead.
 */
internal fun BytecodePatchContext.guardGroupButton(build: SheetMethod) {
    mutableMethod(build).addInstructions(
        0,
        """
            invoke-static { }, $HIDE_GROUP_BUTTON
            move-result v0
            if-eqz v0, :build
            iget-object v0, p0, $SHARE_SHEET->$STICKY_SEARCH_BOX:$SEARCH_BOX
            if-eqz v0, :hidden
            invoke-virtual { v0 }, $VIEW->getLayoutParams()$LAYOUT_PARAMS
            move-result-object v1
            if-eqz v1, :hidden
            const/4 v2, -0x1
            iput v2, v1, $LAYOUT_PARAMS->width:I
            invoke-virtual { v0, v1 }, $VIEW->setLayoutParams($LAYOUT_PARAMS)V
            :hidden
            return-void
            :build
            nop
        """,
    )
}

/**
 * Asks [HIDE_GROUP_ACTION] first thing in the reveal, and on a yes hides and turns off the search
 * box's action in its place and returns. Where the sheet put New group in the search box, it gave
 * the box that action before the reveal runs, and the box shows its action again whenever it
 * refreshes while the action is on, so hiding it alone isn't enough.
 */
internal fun BytecodePatchContext.guardGroupAction(reveal: SheetMethod) {
    mutableMethod(reveal).addInstructions(
        0,
        """
            invoke-static { }, $HIDE_GROUP_ACTION
            move-result v0
            if-eqz v0, :reveal
            iget-object v0, p0, $SHARE_SHEET->$STICKY_SEARCH_BOX:$SEARCH_BOX
            if-eqz v0, :hidden
            const/4 v1, 0x0
            invoke-virtual { v0, v1 }, $SEARCH_BOX->$ACTION_VISIBLE(Z)V
            invoke-virtual { v0, v1 }, $SEARCH_BOX->$ACTION_ENABLED(Z)V
            :hidden
            return-void
            :reveal
            nop
        """,
    )
}

/**
 * Asks [HIDE_GROUP_SEND] first thing in the show, and on a yes calls the hide with the same session
 * and returns, so the button is put away rather than left as it was. The registers are moved low
 * first, since the show's parameters sit past v15 where a plain invoke can't reach them.
 */
internal fun BytecodePatchContext.guardGroupSend(send: GroupSendSites) {
    mutableMethod(send.show).addInstructions(
        0,
        """
            invoke-static { }, $HIDE_GROUP_SEND
            move-result v0
            if-eqz v0, :show
            move-object/from16 v0, p0
            move-object/from16 v1, p${send.session}
            invoke-virtual { v0, v1 }, $COMPOSER->${send.hide.name}($USER_SESSION)V
            return-void
            :show
            nop
        """,
    )
}

private fun BytecodePatchContext.mutableMethod(site: SheetMethod) = mutableClassDefBy(site.type).methods.single {
    it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
}

private fun Method.site(type: String) = SheetMethod(type, name, parameters())

private fun Method.parameters() = parameterTypes.map(CharSequence::toString)

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

/** The registers a method has besides its parameters and `this`. */
private fun Method.locals(): Int {
    val parameters = parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } + if (isStatic()) 0 else 1
    return (implementation?.registerCount ?: 0) - parameters
}

private fun Method.touchesGroupButton(opcode: Opcode) = implementation?.instructions?.any {
    it.opcode == opcode && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
        field.definingClass == SHARE_SHEET && field.name == GROUP_BUTTON && field.type == VIEW
    }
} == true

private fun Instruction.calls(type: String, name: String) =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == type && it.name == name } == true

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
