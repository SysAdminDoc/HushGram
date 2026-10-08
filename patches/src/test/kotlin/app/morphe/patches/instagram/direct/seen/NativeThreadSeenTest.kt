/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.assertUploadedDelayIsZero
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.nativeClasses
import app.morphe.patches.instagram.direct.seen.ThreadSeenHookTest.Companion.ThreadTrace
import app.morphe.patches.instagram.direct.seen.ThreadSeenHookTest.Companion.assertThreadGuard
import app.morphe.patches.instagram.direct.seen.ThreadSeenHookTest.Companion.traceThreadGuard
import app.morphe.patches.instagram.direct.seen.VisualSeenHookTest.Companion.assertVisualGuard
import app.morphe.patches.instagram.direct.seen.VisualSeenHookTest.Companion.snapshot
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/** The chat receipt hook on each declared build's own dex, next to the view-once receipt hook. */
class NativeThreadSeenTest {
    @Test fun declaredBuildsHoldTheChatReceiptInItsOwnHandlerOnly() = fixtures { bundle ->
        val classes = threadClasses(bundle)
        val context = PatchContexts.of(classes.values)
        val found = context.findThreadSeen()
        val visual = context.findVisualSeen()
        assertEquals("both receipts finish through the queue's one callback", visual.complete.toString(), found.complete.toString())
        assertEquals("the receipt goes out for the account its handler keeps", found.handler.definingClass, found.account.definingClass)
        assertEquals(USER_SESSION, found.account.type)
        assertNotEquals(visual.handler.definingClass, found.handler.definingClass)
        assertNotEquals(visual.creator.definingClass, found.creator.definingClass)
        val first = found.handler.visualCode().first()
        val hooked = "${found.handler.definingClass}->${found.handler.name}("
        val before = classes.mapValues { snapshot(it.value.methods) }
        context.holdBackThreadSeen()
        assertThreadGuard(found.handler, found.complete.toString(), first, found.account.toString())
        assertEquals(ThreadTrace(completed = 1, sent = 0), traceThreadGuard(found.handler, true))
        assertEquals(ThreadTrace(completed = 0, sent = 1), traceThreadGuard(found.handler, false))
        for ((type, original) in before) {
            val now = snapshot(context.mutableClassDefBy(type).methods)
            assertEquals("native $type changed", original.filterNot { it.first.startsWith(hooked) }, now.filterNot { it.first.startsWith(hooked) })
        }
    }

    /** Either patch can go first: each finds its own handler untouched by the other's guard. */
    @Test fun bothReceiptGuardsLandInEitherOrder() = fixtures { bundle ->
        val classes = threadClasses(bundle)
        val first = threadHandler(classes).visualCode().first()
        for (visualFirst in listOf(true, false)) {
            val context = PatchContexts.of(classes.values)
            val visual = context.findVisualSeen()
            val found = context.findThreadSeen()
            if (visualFirst) context.holdBackVisualSeen()
            context.holdBackThreadSeen()
            if (!visualFirst) context.holdBackVisualSeen()
            assertVisualGuard(visual.handler, visual.complete.toString())
            assertThreadGuard(found.handler, found.complete.toString(), first, found.account.toString())
        }
    }

    @Test fun dexBackedInstructionReReadsResolveTheSameTargets() = fixtures { bundle ->
        val types = threadClasses(bundle).keys - THREAD_SEEN - VISUAL_SEEN
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, types).values + ExtensionDex.classDef(THREAD_SEEN))
        val found = context.findThreadSeen()
        val first = found.handler.visualCode().first()
        context.holdBackThreadSeen()
        assertThreadGuard(found.handler, found.complete.toString(), first, found.account.toString())
    }

    /**
     * Finishing with no error marks the task uploaded, and NativeVisualSeenTest follows the queue
     * from there to its removal when the descriptor keeps uploaded tasks for no time. This holds
     * the chat receipt's descriptor to that, so a held receipt isn't kept or sent after a restart.
     */
    @Test fun aFinishedChatReceiptIsNotKeptInTheQueue() = fixtures { bundle ->
        val classes = threadClasses(bundle)
        val found = PatchContexts.of(classes.values).findThreadSeen()
        val completion = classes.values.flatMap { it.methods }.single {
            it.name == found.complete.name && it.parameterTypes.map(Any::toString) == found.complete.parameterTypes.map(Any::toString) &&
                it.visualCode().any { instruction -> instruction.visualString() == "uploaded" }
        }
        val updateRef = completion.visualCode().calls().single {
            it.returnType == "Z" && it.parameterTypes.size == 2 && it.parameterTypes.first().toString() == found.handler.parameterTypes[2].toString()
        }
        val update = classes.getValue(updateRef.definingClass).methods.single { it.toString() == updateRef.toString() }
        val delay = update.visualCode().calls().single { it.returnType == "J" && it.parameterTypes.map(Any::toString) == listOf(STRING) }
        assertUploadedDelayIsZero(found.registry, classes, delay, THREAD_SEEN_MUTATION)
    }

    companion object {
        private val cached = mutableMapOf<String, Map<String, ClassDef>>()

        /** The view-once test's classes, which hold the chat handler and its sender too, plus its provider and the extension. */
        internal fun threadClasses(bundle: File): Map<String, ClassDef> = cached.getOrPut(bundle.absolutePath) {
            val classes = nativeClasses(bundle).toMutableMap()
            val handler = threadHandler(classes)
            val providers = classes.getValue(handler.definingClass).methods.single { it.name == "<clinit>" }.visualCode()
                .mapNotNull { (it.visualReference() as? FieldReference)?.definingClass }.toSet() - handler.definingClass
            classes += FixtureDex.classes(bundle, providers)
            classes[THREAD_SEEN] = ImmutableClassDef.of(ExtensionDex.classDef(THREAD_SEEN))
            classes
        }

        private fun threadHandler(classes: Map<String, ClassDef>): Method = classes.values.flatMap { it.methods }.single { method ->
            method.visualCode().mapNotNull { it.visualString() }.containsAll(listOf(THREAD_SEEN_QUERY, THREAD_SEEN_ROOT))
        }

        private fun List<Instruction>.calls() = mapNotNull { it.visualReference() as? MethodReference }
    }
}
