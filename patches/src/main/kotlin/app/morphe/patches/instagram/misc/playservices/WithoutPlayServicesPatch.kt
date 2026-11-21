/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.playservices

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

internal const val PATCH_NAME = "Run without Play services"

/**
 * The package Firebase Instance ID puts in the intent that names the app asking for a token. Nothing
 * is installed under it; only the PendingIntent around it matters.
 */
internal const val PLACEHOLDER_PACKAGE = "com.google.example.invalidpackage"

internal const val GET_BROADCAST = "Landroid/app/PendingIntent;->" +
    "getBroadcast(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;"

internal const val FIREBASE_BROADCAST = "Lapp/hushgram/extension/instagram/misc/FirebaseRpc;->" +
    "getBroadcast(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;"

private fun firebaseShape(why: String): Nothing = throw PatchException("$PATCH_NAME: $why")

/** The indexes of [method]'s calls to PendingIntent.getBroadcast. */
internal fun Method.getBroadcastCalls(): List<Int> =
    implementation?.instructions?.toList().orEmpty().withIndex().filter { (_, instruction) ->
        (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            (instruction as ReferenceInstruction).reference.toString() == GET_BROADCAST
    }.map { it.index }

/**
 * Firebase Instance ID's token request sender: the static method that takes the request's Bundle and
 * the sender, returns the answer's Bundle, names the placeholder package and builds the PendingIntent
 * that says which app is asking. Exactly one, or the patch stops.
 */
internal fun BytecodePatchContext.firebaseRequestSender(): Method {
    val found = classesLoadingString(PLACEHOLDER_PACKAGE).flatMap { owner ->
        owner.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Landroid/os/Bundle;" &&
                method.parameterTypes.size == 2 && method.parameterTypes.first().toString() == "Landroid/os/Bundle;" &&
                loadsString(method, PLACEHOLDER_PACKAGE) && method.getBroadcastCalls().isNotEmpty()
        }
    }
    return found.singleOrNull() ?: firebaseShape(
        "expected one Firebase token request sender, found ${found.size}: ${found.map { "${it.definingClass}->${it.name}" }}",
    )
}

/**
 * Sends the sender's one PendingIntent.getBroadcast to the extension, which marks it immutable, so
 * Android 12 and newer accept it on a phone without Play services (#767). The call is replaced in
 * place with the same registers, so nothing that jumps to it moves and its result is read as before.
 */
internal fun MutableMethod.buildRequestIdentityThroughExtension() {
    val calls = getBroadcastCalls()
    if (calls.size != 1) firebaseShape("expected one PendingIntent in $definingClass->$name, found ${calls.size}")
    val index = calls.single()
    val replacement = when (val call = implementation!!.instructions.elementAt(index)) {
        is RegisterRangeInstruction ->
            "invoke-static/range { v${call.startRegister} .. v${call.startRegister + call.registerCount - 1} }, $FIREBASE_BROADCAST"
        is FiveRegisterInstruction -> {
            if (call.registerCount != 4) firebaseShape("the PendingIntent call passes ${call.registerCount} registers")
            "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE}, v${call.registerF} }, $FIREBASE_BROADCAST"
        }
        else -> firebaseShape("the PendingIntent call is a ${call.opcode}")
    }
    replaceInstruction(index, replacement)
}

@Suppress("unused")
val withoutPlayServicesPatch = bytecodePatch(
    name = "Run without Play services",
    description = "Keeps Instagram from closing right after you sign in on a phone without Google Play services, " +
        "such as one with microG. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Updates")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("withoutPlayServices")
        val sender = firebaseRequestSender()
        mutableClassDefBy(sender.definingClass).methods.single { it.toString() == sender.toString() }
            .buildRequestIdentityThroughExtension()
        enableStatus("withoutPlayServices")
    }
}
