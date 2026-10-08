/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.originalName
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The name Instagram's build keeps on its Whitehat settings screen. */
internal const val WHITEHAT_SCREEN = "WhitehatSettingsFragment"

/** What the screen's switch writes: the trust itself, and when it was turned on and for how long. */
internal const val USER_CERTS = "debug_allow_user_certs"
internal const val USER_CERTS_TTL = "debug_allow_user_certs_ttl"

/** The toast the switch shows, since the network stack reads the choice only as it starts. */
internal const val USER_CERTS_RESTART = "Restart app for changes to take effect"

/** 24 hours in milliseconds: how long the switch says the trust lasts. */
internal const val USER_CERTS_DAY = 86_400_000

private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
private const val MAIN_HOST = "Lcom/instagram/mainactivity/InstagramMainActivity;"
private const val MODAL_HOST = "Lcom/instagram/modal/ModalActivity;"
private const val IG_HOST = "Lcom/instagram/base/activity/IgFragmentActivity;"
private const val SIGNED_IN = "Lcom/instagram/common/session/UserSession;"

/**
 * Finds Instagram's own Whitehat settings screen and proves it still works the native way before
 * HushGram offers to open it: its switch is the handler that writes [USER_CERTS] with a
 * [USER_CERTS_DAY] lifetime and asks for a restart, and one check elsewhere turns the trust off
 * once that day has passed. HushGram never answers that check itself, so the trust stays a
 * choice made on Instagram's screen, and it ends when Instagram says it does.
 *
 * Answers the screen's type. Changes nothing.
 */
internal fun BytecodePatchContext.findWhitehatScreen(): String {
    val switches = mutableListOf<Method>()
    classesHolding(USER_CERTS_TTL, USER_CERTS_RESTART).forEach { clazz ->
        clazz.methods.filterTo(switches) { it.isWhitehatSwitch() }
    }
    val switch = switches.singleOrNull()
        ?: whitehatRefuse("expected one Whitehat switch writing $USER_CERTS for a day, found ${switches.size}")
    val handler = switch.definingClass

    val screens = classesCalling(handler, "<init>").filter { it.originalName() == WHITEHAT_SCREEN }
    val screen = screens.singleOrNull()
        ?: whitehatRefuse("expected one $WHITEHAT_SCREEN building that switch, found ${screens.size}")
    if (!AccessFlags.PUBLIC.isSet(screen.accessFlags) || AccessFlags.ABSTRACT.isSet(screen.accessFlags) ||
        AccessFlags.INTERFACE.isSet(screen.accessFlags)
    ) whitehatRefuse("$WHITEHAT_SCREEN isn't a public class that can be built")
    if (screen.methods.count {
            it.name == "<init>" && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } != 1) whitehatRefuse("$WHITEHAT_SCREEN has no public empty constructor")
    val view = screen.methods.singleOrNull {
        it.name == "onViewCreated" && it.returnType == "V" && !AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf("Landroid/view/View;", "Landroid/os/Bundle;")
    } ?: whitehatRefuse("$WHITEHAT_SCREEN has no onViewCreated")
    if (view.implementation?.instructions?.none {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == handler
        } != false) whitehatRefuse("$WHITEHAT_SCREEN doesn't build its switch when its view is made")
    requireFragment(screen.type)

    val checks = mutableListOf<Method>()
    classesHolding(USER_CERTS_TTL, USER_CERTS).forEach { clazz ->
        clazz.methods.filterTo(checks) { it.isExpiryCheck() }
    }
    if (checks.size != 1) {
        whitehatRefuse("expected one check that turns $USER_CERTS off after its day, found ${checks.size}")
    }
    return screen.type
}

/** Whether [type] is an AndroidX fragment, by its chain of superclasses. */
private fun BytecodePatchContext.requireFragment(type: String) {
    var current: String? = type
    val seen = mutableSetOf<String>()
    while (current != null && current != FRAGMENT) {
        if (!seen.add(current)) whitehatRefuse("$WHITEHAT_SCREEN's superclasses loop")
        current = classDefByOrNull(current)?.superclass
            ?: whitehatRefuse("$WHITEHAT_SCREEN doesn't reach $FRAGMENT")
    }
    if (current == null) whitehatRefuse("$WHITEHAT_SCREEN isn't a fragment")
}

/** The switch's handler: on a check change it writes both keys, the day, and the restart toast. */
private fun Method.isWhitehatSwitch(): Boolean {
    if (name != "onCheckedChanged" || returnType != "V" || AccessFlags.STATIC.isSet(accessFlags) ||
        parameterTypes.map(Any::toString) != listOf("Landroid/widget/CompoundButton;", "Z")
    ) return false
    val code = implementation?.instructions ?: return false
    return whitehatStrings().containsAll(listOf(USER_CERTS, USER_CERTS_TTL, USER_CERTS_RESTART)) &&
        code.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == USER_CERTS_DAY }
}

/** The check: no arguments, a boolean answer, both keys, and the clock to compare the day with. */
private fun Method.isExpiryCheck(): Boolean {
    if (returnType != "Z" || parameterTypes.isNotEmpty()) return false
    val code = implementation?.instructions ?: return false
    return whitehatStrings().containsAll(listOf(USER_CERTS, USER_CERTS_TTL)) && code.any {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        call?.definingClass == "Ljava/lang/System;" && call.name == "currentTimeMillis"
    }
}

private fun Method.whitehatStrings(): Set<String> = implementation?.instructions?.mapNotNull { instruction ->
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
    else ((instruction as ReferenceInstruction).reference as StringReference).string
}?.toSet().orEmpty()

private fun BytecodePatchContext.whitehatStub() = (mutableClassDefByOrNull(OVERRIDE_BRIDGE)
    ?: whitehatRefuse("extension has no Whitehat bridge class")).methods.singleOrNull {
    it.name == "openWhitehatNative" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
        it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags)
} ?: whitehatRefuse("extension has no unique Whitehat bridge")

/**
 * Assembles the Whitehat bridge's body: the same host and session checks as the MetaConfig editor,
 * then Instagram's own navigation to a new [screen], which its navigator gives the session. The
 * screen needs no arguments. Changes nothing.
 */
internal fun BytecodePatchContext.prepareWhitehatScreen(editor: OverrideEditor, screen: String): PreparedStubs =
    prepareStubs(listOf(whitehatStub()), listOf(3 to """
            instance-of v0, p0, $MAIN_HOST
            if-nez v0, :session
            instance-of v0, p0, $MODAL_HOST
            if-eqz v0, :unavailable
            :session
            check-cast p0, $IG_HOST
            invoke-virtual { p0 }, ${editor.getter}
            move-result-object v0
            instance-of v1, v0, $SIGNED_IN
            if-eqz v1, :unavailable
            invoke-static { p0, v0 }, ${editor.factory}
            move-result-object v1
            new-instance v0, $screen
            invoke-direct { v0 }, $screen-><init>()V
            invoke-static { v0, v1 }, ${editor.present}
            const/4 v0, 0x1
            return v0
            :unavailable
            const/4 v0, 0x0
            return v0
        """.trimIndent()), ::whitehatRefuse)

private fun whitehatRefuse(detail: String): Nothing = throw PatchException("Open developer options: $detail")
