/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.analytics.loadsString
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags

internal const val MUSE_BANNER = "$EXTENSION_PACKAGE/metaai/MetaAi;->museBanner()Z"

/** The two analytics names only the Muse banner's builder holds: what it logs when it's shown and when it's tapped. */
internal val MUSE_BANNER_EVENTS = listOf("impression_muse_banner", "tap_muse_banner")

private const val CONTEXT = "Landroid/content/Context;"

/** Where the builder of the profile's Muse banner is: its class, name, parameters and return type. */
internal class MuseBannerBuilder(val type: String, val name: String, val parameters: List<String>, val returnType: String)

/**
 * The profile's banners (hours, mentions, dashboard, and the "Meet Muse, your personal AI agent"
 * card with its Try Muse button) are built from one list, and the Muse card comes from one static
 * method that takes a Context and the profile's data and answers the banner, or null when the
 * profile has none. All four places that add it test for null first (the profile's list, the
 * banners row in Edit featured, and both orderings of the profile's list), so a null leaves the
 * card out and nothing else.
 *
 * It's the one such method loading both [MUSE_BANNER_EVENTS], each itself or from a pool of shared
 * strings. Fails when there isn't exactly one, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findMuseBannerBuilder(): MuseBannerBuilder {
    val builders = classesLoadingString(MUSE_BANNER_EVENTS.first()).flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") &&
                method.parameterTypes.size == 2 && method.parameterTypes[0].toString() == CONTEXT &&
                MUSE_BANNER_EVENTS.all { loadsString(method, it) }
        }.map { MuseBannerBuilder(classDef.type, it.name, it.parameterTypes.map(CharSequence::toString), it.returnType) }
    }
    return builders.singleOrNull()
        ?: throw PatchException("Hide Meta AI: ${builders.size} methods build the Muse banner (${MUSE_BANNER_EVENTS.joinToString()}), not one")
}

/**
 * Makes the builder answer null from its first instruction while Hide Meta AI on your profile is
 * on, the answer a profile without the banner gets. The switch is read on every build, so off and
 * Pause take Instagram's own path. The method has locals at its start, none live, so v0 is free.
 */
internal fun BytecodePatchContext.holdMuseBanner(builder: MuseBannerBuilder) {
    val method = mutableClassDefBy(builder.type).methods.single {
        it.name == builder.name && it.returnType == builder.returnType &&
            it.parameterTypes.map(CharSequence::toString) == builder.parameters
    }
    method.requireLocals("Hide Meta AI", 1)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $MUSE_BANNER
            move-result v0
            if-eqz v0, :build
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("build", method.getInstruction(0)),
    )
}
