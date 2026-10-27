/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tablayout

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Tab bar layout"
internal const val TAB_BAR_LAYOUT = "$EXTENSION_PACKAGE/reels/TabBarLayout;"
internal const val NEW_TAB_BAR = "$TAB_BAR_LAYOUT->newTabBar(I)Z"

private const val SESSION = "Lcom/instagram/common/session/UserSession;"
private const val CONFIGS = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"

/**
 * The server config Instagram 452 asks whether an account gets the newer tab bar, the one with
 * Messages on it and a sideways swipe between every tab. Only two methods load it: the gate the
 * patch hooks, which reads it as a boolean, and one that reads a string from it.
 */
internal const val NEW_TAB_BAR_FLAG = 0x208102ea00060a8dL

/**
 * Picks the tab bar, newer or classic, instead of leaving it to Instagram (#356). Included in the
 * default selection with the choice on Instagram's, so the bar stays the one Instagram picks until
 * the user picks another.
 *
 * Instagram 452 asks one static method, `(UserSession)Z`, whether the account gets the newer bar.
 * It returns a stored override's answer when there is one, and otherwise the [NEW_TAB_BAR_FLAG]
 * config or a second gate. The tab host and about 45 other places ask it, so a server change can
 * move an account from one bar to the other between two launches. Each of the method's returns
 * hands its answer to [NEW_TAB_BAR] first, so every caller gets the bar the user picked.
 */
@Suppress("unused")
val tabBarLayoutPatch = bytecodePatch(
    name = "Tab bar layout",
    description = "Lets you keep the newer tab bar, where you swipe between every tab, or the classic one, " +
        "instead of the one Instagram picks for your account. Starts on Instagram's choice. Pick one in " +
        "HushGram settings > Tab bar.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("tabBarLayout")
        hookTabBarGate()
        enableStatus("tabBarLayout")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Puts [NEW_TAB_BAR] before every return of the gate [findTabBarGate] finds. */
internal fun BytecodePatchContext.hookTabBarGate() {
    val gate = findTabBarGate()
    val method = mutableClassDefBy(gate.definingClass).methods.single {
        it.name == gate.name && it.parameterTypes.map(CharSequence::toString) == listOf(SESSION) && it.returnType == "Z"
    }
    // At each return's own label, so a branch straight to a return passes through the call too.
    // The hook takes the answer as an int (a boolean parameter fails verification on a register
    // ART types as int), and its boolean answer goes back into the register the method returns.
    method.implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
        .asReversed()
        .forEach { (index, register) ->
            method.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static/range { v$register .. v$register }, $NEW_TAB_BAR
                    move-result v$register
                """,
            )
        }
}

/**
 * The one static `(UserSession)Z` method that loads [NEW_TAB_BAR_FLAG] and reads it as a boolean
 * through [CONFIGS]. Fails before anything changes when there isn't exactly one, or it doesn't
 * return a register, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findTabBarGate(): Method {
    val flag = NEW_TAB_BAR_FLAG.toString(16)
    val gates = classesLoading(NEW_TAB_BAR_FLAG).flatMap { it.methods }.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(SESSION) &&
            method.loads(NEW_TAB_BAR_FLAG) && method.readsBoolean()
    }
    if (gates.size != 1) {
        refuse("expected one static (UserSession)Z method reading the tab bar flag $flag as a boolean, found ${gates.size}")
    }
    val gate = gates.single()
    if (gate.implementation!!.instructions.none { it.opcode == Opcode.RETURN }) {
        refuse("${gate.definingClass}->${gate.name} reads $flag but returns no register")
    }
    return gate
}

private fun Method.loads(literal: Long): Boolean = implementation?.instructions?.any {
    it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == literal
} == true

private fun Method.readsBoolean(): Boolean = implementation?.instructions?.any {
    val called = (it as? ReferenceInstruction)?.reference as? MethodReference
    it.opcode == Opcode.INVOKE_INTERFACE && called != null && called.definingClass == CONFIGS &&
        called.parameterTypes.map(CharSequence::toString) == listOf("J") && called.returnType == "Z"
} == true
