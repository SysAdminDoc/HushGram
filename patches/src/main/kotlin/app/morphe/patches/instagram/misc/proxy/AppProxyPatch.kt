/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.proxy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.Method

private const val PATCH = "App-only proxy"
internal const val APP_PROXY = "$EXTENSION_PACKAGE/misc/AppProxy;"
internal const val APP_PROXY_ROUTE = "$APP_PROXY->route()V"

/** The class Instagram's network stack asks for its proxy, under the name its native side keeps for it. */
internal const val TIGON_SERVICE_LAYER = "Lcom/instagram/api/tigon/TigonServiceLayer;"
internal const val PROXY_READ = "getProxyHostAndPort"

/** The property the read looks at first, the one Android sets from a Wi-Fi network's proxy. */
internal const val PROXY_HOST_PROPERTY = "https.proxyHost"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

@Suppress("unused")
val appProxyPatch = bytecodePatch(
    name = "App-only proxy",
    description = "Sends Instagram through an HTTP proxy you set, without changing the proxy for other apps or " +
        "the phone. You need to set a server too. Starts off. Turn it on in HushGram settings > Ads and privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("appProxy")
        routeProxyRead()
        enableStatus("appProxy")
    }
}

/**
 * Calls AppProxy first thing in the method Instagram's network stack asks for its proxy, so the
 * properties it reads next name the server set in HushGram while the switch is on. Fails before
 * changing anything when the method is gone, no longer reads [PROXY_HOST_PROPERTY], or something
 * jumps back to its first instruction, where the call goes.
 */
internal fun BytecodePatchContext.routeProxyRead() {
    val layer = classDefByOrNull(TIGON_SERVICE_LAYER) ?: refuse("the build has no $TIGON_SERVICE_LAYER")
    val reads = layer.methods.filter { it.readsProxy() }
    val read = reads.singleOrNull() ?: refuse("expected one $PROXY_READ() in $TIGON_SERVICE_LAYER, found ${reads.size}")
    val where = "$TIGON_SERVICE_LAYER->$PROXY_READ"
    if (!loadsString(read, PROXY_HOST_PROPERTY)) refuse("$where no longer reads \"$PROXY_HOST_PROPERTY\"")
    if (0 in read.jumpTargets()) refuse("something jumps back to the first instruction of $where")
    mutableClassDefBy(TIGON_SERVICE_LAYER).methods.single { it.readsProxy() }
        .addInstructions(0, "invoke-static {}, $APP_PROXY_ROUTE")
}

private fun Method.readsProxy() = name == PROXY_READ && parameterTypes.isEmpty() && implementation != null
