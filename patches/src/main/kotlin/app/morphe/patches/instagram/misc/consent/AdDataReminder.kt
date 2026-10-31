/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.consent

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/** What the consent Quick Promotion's callback logs once its Bloks parsed, before showing them. */
internal const val CONSENT_QP_SHOWN = "consent_qp_controller_load_qp_bloks_parse_success"

/** Opens the consent flow for an account its eligibility check found. The name isn't obfuscated. */
internal const val EVERGREEN_LAUNCHER = "Lcom/instagram/consent/evergreenlaunch/AcvEvergreenConsentLauncher;"

/** Asks the server for the consent flow by a trigger. The name isn't obfuscated. */
internal const val UCF_LAUNCHER = "Lcom/instagram/consent/ucf/IGUCFLauncher;"

/** What a diagnostics log calls each route, so a report names the one that fired. */
internal const val ROUTE_QUICK_PROMOTION = "quick promotion"
internal const val ROUTE_EVERGREEN = "evergreen launcher"
internal const val ROUTE_TRIGGER = "trigger"

private const val CONTEXT = "Landroid/content/Context;"
private const val SESSION = "Lcom/instagram/common/session/UserSession;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/** The Quick Promotion callback's success path: an instance (Object)V, the method its String is in. */
private fun Method.showsConsentQp() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
    parameterTypes.map(Any::toString) == listOf(OBJECT)

/** The evergreen launcher's launch: its one instance (Context, *, UserSession, *, *)V. */
private fun Method.launchesEvergreen() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
    parameterTypes.map(Any::toString).let { it.size == 5 && it[0] == CONTEXT && it[2] == SESSION }

/** The trigger's suspend call: (UserSession, String, String, continuation)Object. */
private fun Method.asksByTrigger() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == OBJECT &&
    parameterTypes.map(Any::toString).let { it.size == 4 && it[0] == SESSION && it[1] == STRING && it[2] == STRING }

/** A local below the parameters, for the route's name and the answer. */
private fun Method.hasSpareRegister(): Boolean {
    val body = implementation ?: return false
    val parameters = parameterTypes.fold(1) { count, type -> count + if (type.toString() == "J" || type.toString() == "D") 2 else 1 }
    return body.instructions.any() && body.registerCount - parameters >= 1
}

/**
 * Asks [hook] first thing in each of the three routes that bring up the ad data review by
 * themselves, and on a yes leaves as the route does when it has nothing to show.
 *
 * The Quick Promotion route returns from the callback that shows the parsed Bloks, the same as
 * Instagram's own parse failure, so no impression goes out. The evergreen route returns before its
 * rate limit records anything. The trigger route answers null, which its callers already take as a
 * response with no flow in it. All three are found before any is changed, and the shared host
 * activity is left alone so the flow still opens from a settings link or a tapped button. Answers
 * null on success, or why not.
 */
internal fun BytecodePatchContext.skipAdDataReminder(hook: String): String? {
    val owners = classesLoadingString(CONSENT_QP_SHOWN).filter { classDef ->
        classDef.methods.any { it.showsConsentQp() && loadsString(it, CONSENT_QP_SHOWN) }
    }.map { it.type }.distinct()
    val owner = owners.singleOrNull()
        ?: return "expected one class showing the consent Quick Promotion with $CONSENT_QP_SHOWN, found ${owners.size}"
    val quickPromotion = mutableClassDefBy(owner).methods.singleOrNull { it.showsConsentQp() && loadsString(it, CONSENT_QP_SHOWN) }
        ?: return "expected one method of $owner to show the consent Quick Promotion"

    val evergreenClass = classDefByOrNull(EVERGREEN_LAUNCHER) ?: return "this build has no $EVERGREEN_LAUNCHER"
    if (evergreenClass.methods.count { it.launchesEvergreen() } != 1) return "expected one launch method in $EVERGREEN_LAUNCHER"
    val evergreen = mutableClassDefBy(EVERGREEN_LAUNCHER).methods.single { it.launchesEvergreen() }

    val triggerClass = classDefByOrNull(UCF_LAUNCHER) ?: return "this build has no $UCF_LAUNCHER"
    if (triggerClass.methods.count { it.asksByTrigger() } != 1) return "expected one trigger method in $UCF_LAUNCHER"
    val trigger = mutableClassDefBy(UCF_LAUNCHER).methods.single { it.asksByTrigger() }

    listOf(quickPromotion, evergreen, trigger).firstOrNull { !it.hasSpareRegister() }?.let {
        return "${it.definingClass}->${it.name} has no body or spare register"
    }

    quickPromotion.askFirst(hook, ROUTE_QUICK_PROMOTION, "return-void")
    evergreen.askFirst(hook, ROUTE_EVERGREEN, "return-void")
    trigger.askFirst(hook, ROUTE_TRIGGER, "const/4 v0, 0x0\n        return-object v0")
    return null
}

private fun MutableMethod.askFirst(hook: String, route: String, leave: String) {
    addInstructionsWithLabels(
        0,
        """
            const-string v0, "$route"
            invoke-static { v0 }, $hook
            move-result v0
            if-eqz v0, :open
            $leave
        """,
        ExternalLabel("open", getInstruction(0)),
    )
}
