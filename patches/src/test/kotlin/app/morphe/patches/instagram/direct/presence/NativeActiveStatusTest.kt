/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.presence

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.presence.HideActiveStatusHookTest.Companion.assertStatusHook
import app.morphe.patches.instagram.direct.presence.HideActiveStatusHookTest.Companion.code
import app.morphe.patches.instagram.direct.presence.HideActiveStatusHookTest.Companion.reference
import app.morphe.patches.instagram.direct.presence.HideActiveStatusHookTest.Companion.snapshot
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.shared.compat.AppCompatibilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status hook on each of 450's seven builds: the one constructor it lands in, and the proof that
 * only Instagram's presence writer, the class that opens the presence stream, builds a write request.
 */
class NativeActiveStatusTest {
    @Test fun everyBuildSendsItsStatusThroughTheHookAndOnlyThePresenceWriterBuildsTheRequest() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        val checked = mutableListOf<String>()
        for (bundle in bundles) {
            val name = bundle.absolutePath
            val classes = FixtureDex.classes(bundle, setOf(PRESENCE_WRITE_REQUEST, PRESENCE_STATUS))
            assertEquals(name, 2, classes.size)
            val context = PatchContexts.of(classes.values + ExtensionDex.classDef(ACTIVE_STATUS))
            val request = classes.getValue(PRESENCE_WRITE_REQUEST)
            val original = request.methods.single { it.name == "<init>" && it.parameterTypes.map(Any::toString) == PRESENCE_WRITE_PARAMETERS }
            val key = original.toString()
            val before = snapshot(request.methods.filter { it.toString() != key })
            context.sendIdleForActive()
            val methods = context.mutableClassDefBy(PRESENCE_WRITE_REQUEST).methods
            assertStatusHook(methods.single { it.toString() == key }, original.code())
            assertEquals("$name: the request's other methods changed", before, snapshot(methods.filter { it.toString() != key }))
            assertEquals("$name: the status enum changed", snapshot(classes.getValue(PRESENCE_STATUS).methods),
                snapshot(context.mutableClassDefBy(PRESENCE_STATUS).methods))

            val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == key } }) { method ->
                method.code().any { it.reference() == key }
            }
            assertEquals("$name: inside the request only its empty constructor calls it", listOf("$PRESENCE_WRITE_REQUEST-><init>()V"),
                callers.filter { it.definingClass == PRESENCE_WRITE_REQUEST }.map { it.toString() })
            val writers = callers.filter { it.definingClass != PRESENCE_WRITE_REQUEST }
            assertEquals("$name: one presence writer builds every request: $writers", 1, writers.map { it.definingClass }.distinct().size)
            assertEquals("$name: the stream's open, update and close each build one", 3, writers.size)
            val writer = FixtureDex.classes(bundle, setOf(writers.first().definingClass)).values.single()
            assertTrue("$name: the writer opens the presence stream", writer.methods.any { method ->
                method.code().any { it.reference()?.startsWith("$PRESENCE_CLIENT->establishStream(") == true }
            })
            checked += name
        }
        for (code in BUILDS) assertTrue("no fixture of 450 build $code among $checked", checked.any { it.contains("-$code") })
    }

    @Test fun dexBackedInstructionReReadsResolveTheSameConstructor() = fixtures { bundle ->
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, setOf(PRESENCE_WRITE_REQUEST, PRESENCE_STATUS)).values +
            ExtensionDex.classDef(ACTIVE_STATUS))
        val found = context.findPresenceWrite()
        val original = found.code()
        context.sendIdleForActive()
        assertStatusHook(found, original)
    }

    private companion object {
        const val PRESENCE_CLIENT = "Lcom/instagram/distribgw/client/presence/IgDgwPresenceClientImpl;"
        val BUILDS = listOf(385611395, 385611400, 385611404, 385611431, 385611438, 385611439, 385611440)
    }
}
