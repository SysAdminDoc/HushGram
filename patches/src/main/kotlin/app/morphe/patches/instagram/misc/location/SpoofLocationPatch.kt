/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.location

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.sendToStandIn
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Spoof location"
internal const val SPOOF_LOCATION = "$EXTENSION_PACKAGE/misc/SpoofLocation;"
internal const val LOCATION = "Landroid/location/Location;"

/**
 * The reads of a Location the patch sends to SpoofLocation, by name and shape after the location,
 * each with the name of its stand-in there: static, the location first, the same parameters.
 */
internal val LOCATION_READS = mapOf(
    "getLatitude" to ("()D" to "latitude"),
    "getLongitude" to ("()D" to "longitude"),
    "distanceTo" to ("($LOCATION)F" to "distanceTo"),
)

/** The reads without which a spoofed place couldn't reach Instagram at all. */
private val REQUIRED_READS = listOf("getLatitude", "getLongitude")

/** The SpoofLocation method that stands in for the Location read named [name]. */
internal fun locationStandIn(name: String): String {
    val (shape, standIn) = LOCATION_READS.getValue(name)
    return "$SPOOF_LOCATION->$standIn($LOCATION${shape.substringAfter('(')}"
}

@Suppress("unused")
val spoofLocationPatch = bytecodePatch(
    name = "Spoof location",
    description = "Tells Instagram the phone is at a place you set, for the location sticker, nearby places and " +
        "maps. Photos keep their own places. Off until you turn it on and pick a place. Switch: HushGram settings > " +
        "Ads and privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("spoofLocation")
        spoofLocationReads()
        enableStatus("spoofLocation")
    }
}

/** The name of the Location read this instruction makes, or null. */
internal fun Instruction.locationRead(): String? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (call.definingClass != LOCATION) return null
    val (shape, _) = LOCATION_READS[call.name] ?: return null
    return call.name.takeIf { call.parameterTypes.joinToString("", "(", ")") + call.returnType == shape }
}

/**
 * Sends every read of a Location's latitude, longitude and distance in Instagram's code to
 * SpoofLocation, on the same registers in the same order, the location first, so nothing after a
 * call moves. Answers how many of each it sent. Fails before changing anything when there's no
 * latitude or no longitude read, since then a spoofed place would never reach Instagram. The
 * extension's own reads are the real ones the stand-ins make, so they stay.
 */
internal fun BytecodePatchContext.spoofLocationReads(): Map<String, Int> {
    val found = LOCATION_READS.keys.flatMap { classesCalling(LOCATION, it) }.distinctBy { it.type }
    val counts = LOCATION_READS.keys.associateWith { name ->
        found.sumOf { classDef ->
            classDef.methods.sumOf { method -> method.implementation?.instructions?.count { it.locationRead() == name } ?: 0 }
        }
    }
    val missing = REQUIRED_READS.filter { counts.getValue(it) == 0 }
    if (missing.isNotEmpty()) throw PatchException("$PATCH: Instagram never calls Location.${missing.joinToString(" or Location.")}")
    for (classDef in found) {
        for (method in mutableClassDefBy(classDef.type).methods) {
            val sites = method.implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
                instruction.locationRead()?.let { index to it }
            }.orEmpty()
            sites.asReversed().forEach { (index, name) -> method.sendToStandIn(index, locationStandIn(name)) }
        }
    }
    return counts
}
