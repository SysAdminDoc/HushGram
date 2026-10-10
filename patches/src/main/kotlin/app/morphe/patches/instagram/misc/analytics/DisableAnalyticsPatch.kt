/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.analytics

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.filterEveryReturn
import app.morphe.patches.instagram.misc.extension.filterEveryStringLoad
import app.morphe.patches.instagram.misc.extension.handleTargets
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.writeTargetCoverage
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Disable analytics"

private const val ENDPOINT = "$EXTENSION_PACKAGE/misc/Analytics;->endpoint(Ljava/lang/String;)Ljava/lang/String;"

/** Lacrima's addresses, which it uses a few milliseconds before HushGram's settings are ready. */
private const val REPORT_ENDPOINT = "$EXTENSION_PACKAGE/misc/Analytics;->reportEndpoint(Ljava/lang/String;)Ljava/lang/String;"

/** The logger's switch for Falco's event stream. It takes an int, as no hook may take a boolean. */
internal const val STREAM_EVENTS = "$EXTENSION_PACKAGE/misc/Analytics;->streamEvents(I)I"

/** Whether to skip a Bloks screen, by its app id: the "Set up on new device" screens. */
internal const val SETUP_SCREEN = "$EXTENSION_PACKAGE/misc/Analytics;->setupScreen(Ljava/lang/String;)I"

/** The routes the patch works on, in order. Each before "setup" stops events leaving the phone. */
internal val ANALYTICS_TARGETS = listOf("builder", "graph", "mqtt", "reports", "pings", "stream", "setup")

/**
 * Skipping the setup screens only matters while their seen events are refused, so a build where
 * none of the other routes was found mustn't pass on the setup screens alone.
 */
internal val ANALYTICS_SUPPORTING = setOf("setup")

@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch(
    name = "Disable analytics",
    description = "Stops Instagram from sending usage reports and crash reports to Instagram and Facebook. It " +
        "also skips the contacts and location setup screens. Restart Instagram after you change it. On by " +
        "default. Turn it off in HushGram settings > Ads and privacy.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("disableAnalytics")

        handleTargets(PATCH, "event upload addresses", ANALYTICS_TARGETS, supporting = ANALYTICS_SUPPORTING,
            coverage = { writeTargetCoverage("disableAnalytics", it) }) { target ->
            when (target) {
                // Instagram's own logging_client_events and pigeon_nest addresses, built from a host.
                "builder" -> AnalyticsEndpointFingerprint.matchAllOrNull().orEmpty().let { matches ->
                    when (matches.size) {
                        1 -> matches.single().method.filterEveryReturn(PATCH, ENDPOINT).let { null }
                        0 -> "no method builds the logging_client_events and pigeon_nest address"
                        else -> "${matches.size} methods build the logging_client_events address, expected one"
                    }
                }
                // The address the MQTT client posts its analytics to, which the server can set in the
                // client's settings. Its fallback is the Graph address, which "graph" covers already.
                "mqtt" -> wrapMqttAnalyticsEndpoint(ENDPOINT)
                // Lacrima's crash and reliability reports, to an address it builds on
                // b-www.facebook.com before the settings are ready and reads again for each send.
                "reports" -> ReportAddressFingerprint.matchAllOrNull().orEmpty().let { matches ->
                    when {
                        matches.size > 1 -> "${matches.size} methods build the b-www.facebook.com report address, expected one"
                        matches.isEmpty() -> "no method builds the b-www.facebook.com report address"
                        filterReportAddressReads(matches.single().method, REPORT_ENDPOINT) == 0 -> "nothing reads the b-www.facebook.com report address it builds"
                        else -> null
                    }
                }
                // Falco's event stream, which skips the batch upload the addresses above go to.
                "stream" -> keepEventsOffTheStream(STREAM_EVENTS)
                // The contacts and location setup screens, which keep coming back while the events
                // saying they were seen are refused.
                "setup" -> skipSetupScreens(SETUP_SCREEN)
                // Lacrima's startup and debug pings, as a constant in each sender.
                "pings" -> if (filterEveryStringLoad(ERROR_PING_ENDPOINT, REPORT_ENDPOINT) > 0) {
                    null
                } else {
                    "no code loads $ERROR_PING_ENDPOINT"
                }
                // The same events to Facebook's Graph API, as a constant wherever Instagram names it.
                else -> if (filterEveryStringLoad(GRAPH_LOGGING_ENDPOINT, ENDPOINT) > 0) {
                    null
                } else {
                    "no code loads $GRAPH_LOGGING_ENDPOINT"
                }
            }
        }

        enableStatus("disableAnalytics")
    }
}
