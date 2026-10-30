/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.tray

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide suggested stories"

internal const val STORY_NETEGO = "$EXTENSION_PACKAGE/stories/StoriesTray;->netego(Ljava/lang/String;)Z"

/** What the stories viewer's netego mapper throws for a netego type it doesn't know. Only it loads this. */
internal const val UNSUPPORTED_NETEGO = "Unsupported story netego type: "

/** The netego type of the Suggested for you card between stories, which the mapper's class names. */
internal const val SUGGESTED_USERS_NETEGO = "suggested_users_in_story"

/** What the code taking ads and netegos into the stories viewer reports for one that isn't valid. */
internal const val INVALID_NETEGO = "invalid_ad_or_netego_reel_response_item"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val STRING = "Ljava/lang/String;"

/** The viewer's check of an ad or netego item, and the item interface's getter of a netego's type. */
internal class NetegoCheck(val type: String, val name: String, val typeGetter: String)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the check the stories viewer asks before it takes in an ad or a netego (a unit Instagram
 * puts between stories, such as Suggested for you). The chain, each step exactly once:
 * the static `(String)Integer` mapper loading [UNSUPPORTED_NETEGO], in a class naming
 * [SUGGESTED_USERS_NETEGO]; the one method calling it, a static `(ReelResponseItemIntf)Integer`
 * that hands it what a String getter of the item answered, the netego's type; and the one static
 * `(UserSession, ReelResponseItemIntf)Z` calling that, the check, asked by code loading
 * [INVALID_NETEGO]. Fails before anything changes when any of it isn't so, since that's an update
 * this patch hasn't seen.
 */
internal fun BytecodePatchContext.findStoryNetegoCheck(): NetegoCheck {
    val mappers = classesLoadingString(UNSUPPORTED_NETEGO).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, UNSUPPORTED_NETEGO) }
    }
    val mapper = mappers.singleOrNull() ?: refuse("expected one method holding \"$UNSUPPORTED_NETEGO\", found ${mappers.size}")
    val mapperAt = "${mapper.definingClass}->${mapper.name}"
    if (!mapper.isStatic() || mapper.parameters() != listOf(STRING) || mapper.returnType != INTEGER) {
        refuse("$mapperAt(${mapper.parameters().joinToString("")})${mapper.returnType} isn't a netego type mapper")
    }
    if (classesLoadingString(SUGGESTED_USERS_NETEGO).none { it.type == mapper.definingClass }) {
        refuse("the class of $mapperAt doesn't name $SUGGESTED_USERS_NETEGO")
    }

    val readers = callersOf(mapper)
    val reader = readers.singleOrNull() ?: refuse("expected one method asking $mapperAt, found ${readers.size}")
    val readerAt = "${reader.definingClass}->${reader.name}"
    if (!reader.isStatic() || reader.parameters() != listOf(TRAY_ITEM_INTF) || reader.returnType != INTEGER) {
        refuse("$readerAt(${reader.parameters().joinToString("")})${reader.returnType} doesn't read a story item's netego type")
    }
    val getters = reader.calls().filter {
        it.definingClass == TRAY_ITEM_INTF && it.parameterTypes.isEmpty() && it.returnType == STRING
    }.map { it.toString() }.distinct()
    val getter = getters.singleOrNull() ?: refuse("expected $readerAt to read one String off the story item, found $getters")

    val checks = callersOf(reader).filter {
        it.isStatic() && it.parameters() == listOf(USER_SESSION, TRAY_ITEM_INTF) && it.returnType == "Z"
    }
    val check = checks.singleOrNull() ?: refuse("expected one check of a story item asking $readerAt, found ${checks.size}")
    val checkAt = "${check.definingClass}->${check.name}"
    if (callersOf(check).none { loadsString(it, INVALID_NETEGO) }) refuse("nothing asking $checkAt holds \"$INVALID_NETEGO\"")
    if (0 in check.jumpTargets()) refuse("something in $checkAt jumps back to its start")
    return NetegoCheck(check.definingClass, check.name, getter)
}

/**
 * First thing in the check: reads the item's netego type with the getter and asks [STORY_NETEGO].
 * A yes answers the check no, so the viewer leaves the item out the way it leaves out one that
 * isn't valid. A no runs Instagram's own check. At the top no local is live yet, so v0 is free.
 */
internal fun BytecodePatchContext.hideStorySuggestions(check: NetegoCheck) {
    val method = mutableClassDefBy(check.type).methods.single {
        it.name == check.name && it.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION, TRAY_ITEM_INTF)
    }
    method.requireLocals(PATCH, 1)
    val item = method.parameterRegisterNumber(1)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-interface/range { v$item .. v$item }, ${check.typeGetter}
            move-result-object v0
            invoke-static { v0 }, $STORY_NETEGO
            move-result v0
            if-eqz v0, :instagram
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("instagram", method.getInstruction(0)),
    )
}

/** The methods outside the extension calling [target], by its class, name and parameters. */
private fun BytecodePatchContext.callersOf(target: Method): List<Method> =
    classesCalling(target.definingClass, target.name).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.calls().any {
                it.definingClass == target.definingClass && it.name == target.name &&
                    it.parameterTypes.map(CharSequence::toString) == target.parameters() && it.returnType == target.returnType
            }
        }
    }

private fun Method.calls(): List<MethodReference> = implementation?.instructions
    ?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.orEmpty()

private fun Method.parameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
