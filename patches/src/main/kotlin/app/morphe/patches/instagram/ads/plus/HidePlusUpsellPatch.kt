/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.ads.plus

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.filterEveryBooleanReturn
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

internal const val PATCH = "Hide Instagram Plus prompts"

/** The filter every answer of Instagram's Plus offer check passes through. */
internal const val PLUS_OFFER = "$EXTENSION_PACKAGE/ads/PlusUpsell;->offer(I)Z"

/** What the offer check logs when it decides a spot, held by no other method of 454. */
internal const val PLUS_DECISION = "eligibility_decision"

/** What the offer check logs when Meta's server couldn't say, the second thing that picks it out. */
internal const val PLUS_SERVER_UNAVAILABLE = "server_unavailable"

/** The session the offer check is asked about, its first parameter. */
internal const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** Asked first where Meta's newer, cached Plus offer is built: yes means build none. */
internal const val PLUS_HIDE = "$EXTENSION_PACKAGE/ads/PlusUpsell;->hide()Z"

/** The query Meta's offer resolver fetches offers with, loaded only in that resolver's class. */
internal const val PLUS_FETCH = "MetaSubsEntrypointExperienceBatchQuery"

/** The cached offer the resolver builds a Plus card's offer from, a parameter of that builder. */
internal const val PLUS_SNAPSHOT = "Lcom/meta/foa/subs/entrypointexperience/MetaSubsEntrypointExperienceSnapshot;"

@Suppress("unused")
val hidePlusUpsellPatch = bytecodePatch(
    name = "Hide Instagram Plus prompts",
    description = "Takes Instagram's offers to try Plus out of chats, stories and menus. Nothing that comes with " +
        "Plus is turned on. Starts off. Turn it on in HushGram settings > Ads and privacy.",
) {
    category("Ads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("hidePlusUpsell")
        val check = findPlusOfferCheck()
        val builder = findPlusOfferBuilder()
        hidePlusOffers(check)
        holdPlusOfferBuilder(builder)
        enableStatus("hidePlusUpsell")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Instagram's Plus offer check (002K.A02 on 454): the one static method loading [PLUS_DECISION] and
 * [PLUS_SERVER_UNAVAILABLE] that takes the session first and answers a boolean. On accounts Instagram
 * still decides for itself, each Plus offer (the card and "See preview" row on a held chat, the story
 * menu's row, the viewer list's diamond) asks it whether to show and shows nothing of Plus on a no.
 * Accounts on Meta's newer path are handled by [findPlusOfferBuilder]. What a subscriber has is read
 * elsewhere, before this is asked. Fails before anything changes when it isn't found exactly once.
 */
internal fun BytecodePatchContext.findPlusOfferCheck(): Method {
    val checks = classesLoadingString(PLUS_DECISION).flatMap { it.methods }.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
            method.returnType == "Z" && method.parameterTypes.firstOrNull()?.toString() == USER_SESSION &&
            loadsString(method, PLUS_DECISION) && loadsString(method, PLUS_SERVER_UNAVAILABLE)
    }
    return checks.singleOrNull()
        ?: refuse("expected one Plus offer check loading \"$PLUS_DECISION\" and \"$PLUS_SERVER_UNAVAILABLE\", found ${checks.size}")
}

/** Passes every answer of the offer check through [PLUS_OFFER], which says no while the switch is on. */
internal fun BytecodePatchContext.hidePlusOffers(check: Method) {
    mutableClassDefBy(check.definingClass).methods.single {
        it.name == check.name && it.returnType == check.returnType &&
            it.parameterTypes.map(Any::toString) == check.parameterTypes.map(Any::toString)
    }.filterEveryBooleanReturn(PATCH, PLUS_OFFER)
}

/**
 * Where Meta's newer Plus offer is built (MetaSubsEntrypointExperienceResolver.A00 on 454): the one
 * static method beside [PLUS_FETCH] that takes a cached [PLUS_SNAPSHOT] and returns the offer a spot
 * draws its Plus card and "See preview" row from. Accounts on that path never reach the offer check,
 * so a held chat kept its card with only the check filtered (#559). Both the cached and the freshly
 * fetched offer come through here, and every spot treats no offer as nothing to show. Fails before
 * anything changes when it isn't found exactly once.
 */
internal fun BytecodePatchContext.findPlusOfferBuilder(): Method {
    val builders = classesLoadingString(PLUS_FETCH)
        .filter { classDef -> classDef.methods.any { loadsString(it, PLUS_FETCH) } }
        .flatMap { it.methods }
        .filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
                method.returnType.startsWith("L") && method.returnType != "Ljava/lang/Object;" &&
                method.parameterTypes.any { it.toString() == PLUS_SNAPSHOT }
        }
    return builders.singleOrNull()
        ?: refuse("expected one Plus offer builder taking $PLUS_SNAPSHOT beside \"$PLUS_FETCH\", found ${builders.size}")
}

/**
 * Makes the builder hand back no offer from its first instruction while the switch is on, before it
 * logs that the offer was seen. Off and Pause build it as Instagram does. Nothing holds a local
 * before the method's first instruction, so v0 is free.
 */
internal fun BytecodePatchContext.holdPlusOfferBuilder(builder: Method) {
    val method = mutableClassDefBy(builder.definingClass).methods.single {
        it.name == builder.name && it.returnType == builder.returnType &&
            it.parameterTypes.map(Any::toString) == builder.parameterTypes.map(Any::toString)
    }
    method.requireLocals(PATCH, 1)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $PLUS_HIDE
            move-result v0
            if-eqz v0, :build
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("build", method.getInstruction(0)),
    )
}
