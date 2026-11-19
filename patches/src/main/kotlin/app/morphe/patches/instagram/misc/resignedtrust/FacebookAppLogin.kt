/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.resignedtrust

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

/** The Facebook app's login screen, which Instagram's Facebook login asks it to show. */
internal const val FACEBOOK_PROXY_AUTH = "com.facebook.katana.ProxyAuth"

internal const val FACEBOOK_LOGIN_REQUEST = "Lcom/facebook/login/LoginClient\$Request;"

internal const val RESOLVE_ACTIVITY =
    "Landroid/content/pm/PackageManager;->resolveActivity(Landroid/content/Intent;I)Landroid/content/pm/ResolveInfo;"

internal const val FACEBOOK_APP_LOOKUP = "Lapp/hushgram/extension/instagram/misc/FacebookAppLogin;->" +
    "resolveActivity(Landroid/content/pm/PackageManager;Landroid/content/Intent;I)Landroid/content/pm/ResolveInfo;"

private fun facebookLoginShape(why: String): Nothing =
    throw PatchException("Restore trust on re-signed builds: $why")

/** The indexes of [method]'s calls to PackageManager.resolveActivity. */
internal fun Method.resolveActivityCalls(): List<Int> =
    implementation?.instructions?.toList().orEmpty().withIndex().filter { (_, instruction) ->
        (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            (instruction as ReferenceInstruction).reference.toString() == RESOLVE_ACTIVITY
    }.map { it.index }

/**
 * The Facebook SDK's app login handler, the step of Instagram's Facebook login that asks the
 * Facebook app, Messenger or the Facebook beta to log in for it: the one instance method of the
 * classes naming the Facebook app's login screen that takes a login request, returns nothing and
 * looks the app up with PackageManager.resolveActivity. Exactly one, or the patch stops.
 */
internal fun BytecodePatchContext.facebookAppLoginHandler(): Method {
    val found = classesLoadingString(FACEBOOK_PROXY_AUTH).flatMap { owner ->
        owner.methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf(FACEBOOK_LOGIN_REQUEST) &&
                method.resolveActivityCalls().isNotEmpty()
        }
    }
    return found.singleOrNull() ?: facebookLoginShape(
        "expected one Facebook app login handler, found ${found.size}: ${found.map { "${it.definingClass}->${it.name}" }}",
    )
}

/**
 * Sends the handler's one app lookup to the extension, which answers no app on a re-signed build,
 * so Instagram goes on to its browser login instead of a Facebook app that refuses it (#760). The
 * call is replaced in place with the same registers, the PackageManager first, so nothing that
 * jumps to it moves and its result is read as before.
 */
internal fun MutableMethod.lookUpFacebookAppThroughExtension() {
    val calls = resolveActivityCalls()
    if (calls.size != 1) facebookLoginShape("expected one app lookup in $definingClass->$name, found ${calls.size}")
    val index = calls.single()
    val call = implementation!!.instructions.elementAt(index)
    val replacement = when (call) {
        is RegisterRangeInstruction ->
            "invoke-static/range { v${call.startRegister} .. v${call.startRegister + call.registerCount - 1} }, $FACEBOOK_APP_LOOKUP"
        is FiveRegisterInstruction -> {
            if (call.registerCount != 3) facebookLoginShape("the app lookup passes ${call.registerCount} registers")
            "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE} }, $FACEBOOK_APP_LOOKUP"
        }
        else -> facebookLoginShape("the app lookup is a ${call.opcode}")
    }
    replaceInstruction(index, replacement)
}
