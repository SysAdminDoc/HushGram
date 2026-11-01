/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.icon

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.misc.settings.sendToStandIn
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"

private const val APP_ICON = "$EXTENSION_PACKAGE/misc/AppIcon;"

/**
 * The PackageManager calls that turn components on and off, by name, with their parameters. AppIcon
 * has a static method of the same name for each, taking the manager first.
 */
internal val COMPONENT_CALLS = mapOf(
    "setComponentEnabledSetting" to "(Landroid/content/ComponentName;II)V",
    "setComponentEnabledSettings" to "(Ljava/util/List;)V",
)

/** The call this instruction makes when it's one of [COMPONENT_CALLS], or null. */
internal fun Instruction.componentCall(): MethodReference? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (call.definingClass != PACKAGE_MANAGER) return null
    val shape = COMPONENT_CALLS[call.name] ?: return null
    return call.takeIf { it.parameterTypes.joinToString("", "(", ")") + it.returnType == shape }
}

/** The AppIcon method that stands in for the call [name]: static, the manager first. */
internal fun componentStandIn(name: String): String =
    "$APP_ICON->$name${COMPONENT_CALLS.getValue(name).replaceFirst("(", "($PACKAGE_MANAGER")}"

private fun refuse(why: String): Nothing = throw PatchException("$ICON_PATCH: $why")

/**
 * Sends every one of Instagram's [COMPONENT_CALLS] to AppIcon, on the same registers, and answers how
 * many it sent. Instagram turns its launcher alias back on at start when no icon of its own is on,
 * from more than one place, so every call goes through the filter rather than the ones known today.
 * Refuses when there are none, since then the build sets its icon some other way, or when AppIcon
 * hasn't the stand-ins.
 */
internal fun BytecodePatchContext.routeComponentCalls(): Int {
    val extension = (classDefByOrNull(APP_ICON)?.methods ?: emptySet())
        .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        .mapTo(HashSet()) { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
    COMPONENT_CALLS.keys.map(::componentStandIn).firstOrNull { it !in extension }?.let { refuse("the extension has no public static $it") }
    val calling = COMPONENT_CALLS.keys.flatMapTo(LinkedHashSet()) { name -> classesCalling(PACKAGE_MANAGER, name).map { it.type } }
    var sent = 0
    for (type in calling) {
        for (method in mutableClassDefBy(type).methods) {
            val sites = method.implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
                instruction.componentCall()?.let { index to it }
            }.orEmpty()
            // From the end, though a stand-in is the same size as the call it replaces.
            for ((index, call) in sites.asReversed()) method.sendToStandIn(index, componentStandIn(call.name))
            sent += sites.size
        }
    }
    if (sent == 0) refuse("found no PackageManager.setComponentEnabledSetting call in Instagram")
    return sent
}

/** The aliases and their icons. Only the manifest and Instagram's icon are decoded. */
private val appIconResourcesPatch = resourcePatch {
    execute {
        addAppIcons()
    }
}

/**
 * A home screen icon picked in HushGram's settings (#284): Instagram's own, HushGram's mark,
 * Instagram's glyph on black or on plum, a black glyph on white (#353), or a pencil drawing of the
 * glyph on white (#406). Included in the default selection with Instagram's icon picked.
 *
 * Android shows one icon per enabled launcher activity, so the patch adds an alias per icon next to
 * Instagram's launcher alias, each a copy of it that's off, and the extension turns the picked one on
 * and Instagram's off when Instagram goes to the background, the way Instagram changes its own
 * alternate icons. Instagram turns its alias back on at start whenever none of its own icons is on,
 * and its component manager turns on any component that hasn't said to be left alone, so every call
 * Instagram makes to turn a component on or off goes through AppIcon, which drops the ones that would
 * undo the pick. HushGram's aliases carry the meta-data that leaves them alone.
 */
@Suppress("unused")
val pickAppIconPatch = bytecodePatch(
    name = "Pick the app icon",
    description = "Lets you pick the icon Instagram shows on your home screen: Instagram's own, HushGram's, " +
        "Instagram's glyph on black, on plum, or in black on white, a hand-drawn Instagram glyph on white, " +
        "Instagram's gradient glyph on dark, or the old retro camera icon. " +
        "The new icon shows once you leave Instagram, and you may need to add it to " +
        "your home screen again from the app drawer. Starts on Instagram's own. Pick one in HushGram settings > Layout.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch, appIconResourcesPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("appIcon")
        routeComponentCalls()
        enableStatus("appIcon")
    }
}
