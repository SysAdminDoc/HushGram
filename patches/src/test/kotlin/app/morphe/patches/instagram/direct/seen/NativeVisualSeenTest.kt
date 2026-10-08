/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.direct.seen.VisualSeenHookTest.Companion.assertVisualGuard
import app.morphe.patches.instagram.direct.seen.VisualSeenHookTest.Companion.snapshot
import app.morphe.patches.instagram.direct.seen.VisualSeenHookTest.Companion.traceGuard
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Native structure and injected-branch execution, without claiming sender-side acceptance. */
class NativeVisualSeenTest {
    @Test fun declaredBuildsBindALiveVisualCreatorAndCompleteOnlyItsHandler() = fixtures { bundle ->
        val classes = nativeClasses(bundle)
        val context = PatchContexts.of(classes.values)
        val found = context.findVisualSeen()
        assertTrue("creator must be Instagram's visual viewer", found.creator.definingClass.contains("/direct/visual/"))
        val before = classes.mapValues { snapshot(it.value.methods) }
        context.holdBackVisualSeen()
        assertVisualGuard(found.handler, found.complete.toString())
        assertEquals(1, traceGuard(found.handler, true).completed)
        assertEquals(0, traceGuard(found.handler, true).sent)
        assertEquals(1, traceGuard(found.handler, false).sent)
        for ((type, original) in before) if (type != found.handler.definingClass) {
            assertEquals("native $type changed", original, snapshot(context.mutableClassDefBy(type).methods))
        }
        val untouched = classes.values.flatMap { it.methods }.filter { method ->
            method.visualCode().any { it.visualString() in setOf("voice_media", "IGDirectItemSeenMutation", "seenEphemeralMessageThreadData") }
        }
        assertTrue("voice and ordinary receipt paths must be included", untouched.size >= 3)
        untouched.forEach { assertEquals(0, it.visualCode().count { ins -> ins.visualReference()?.toString() == HOLD_VISUAL_SEEN }) }
    }

    @Test fun dexBackedInstructionReReadsStillResolveTheCallbackForwarding() = fixtures { bundle ->
        val types = nativeClasses(bundle).keys - VISUAL_SEEN
        val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, types).values + ExtensionDex.classDef(VISUAL_SEEN))
        val found = context.findVisualSeen()
        context.holdBackVisualSeen()
        assertVisualGuard(found.handler, found.complete.toString())
    }

    @Test fun nativeSuccessRemovesRetriesAndRewritesOnlyTheRemainingMutationQueue() = fixtures { bundle ->
        val classes = nativeClasses(bundle)
        val found = PatchContexts.of(classes.values).findVisualSeen()
        val completion = classes.values.flatMap { it.methods }.single {
            it.name == found.complete.name && it.parameterTypes.map(Any::toString) == found.complete.parameterTypes.map(Any::toString) &&
                it.visualCode().any { instruction -> instruction.visualString() == "uploaded" }
        }
        val completeCode = completion.visualCode()
        assertEquals(Opcode.IF_NEZ, completeCode.first().opcode)
        assertEquals("a null error selects the uploaded path", completion.parameterRegisterNumber(0), (completeCode.first() as OneRegisterInstruction).registerA)
        assertTrue("success cancels retry timer", completeCode.calls().any { it.definingClass == "Landroid/os/Handler;" && it.name == "removeMessages" })
        assertTrue("success removes manager bookkeeping", completeCode.calls().count { it.name == "remove" && it.definingClass == "Ljava/util/AbstractMap;" } >= 3)
        val updateRef = completeCode.calls().single {
            it.returnType == "Z" && it.parameterTypes.size == 2 && it.parameterTypes.first().toString() == found.handler.parameterTypes[2].toString()
        }
        val queue = classes.getValue(updateRef.definingClass)
        val update = queue.methods.single { it.toString() == updateRef.toString() }
        val remove = queue.methods.single { it.parameterTypes.map(Any::toString) == listOf(STRING) && it.returnType == "Landroid/util/Pair;" }
        val delay = update.visualCode().calls().single { it.returnType == "J" && it.parameterTypes.map(Any::toString) == listOf(STRING) }
        assertUploadedDelayIsZero(found.registry, classes, delay)
        assertTrue("uploaded selects removal rather than retry", uploadedCallsRemoval(update, remove.toString(), delay))
        val removeCode = remove.visualCode()
        assertTrue("removes just the completed task's keys", removeCode.calls().count { it.name == "remove" } >= 3)
        assertTrue("does not clear the in-memory queue", removeCode.calls().none { it.name == "clear" })
        val rewriteRef = removeCode.calls().single { it.definingClass == queue.type && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(queue.type) }
        val rewrite = queue.methods.single { it.toString() == rewriteRef.toString() }
        assertTrue("persists the remaining queues", rewrite.visualCode().calls().any { it.name == "addAll" })
        val diskConstructor = rewrite.visualCode().calls().single {
            it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf(DISK_IO, "Ljava/util/List;")
        }
        val disk = classes.getValue(diskConstructor.definingClass).methods.single { it.name == "run" }
        val diskCalls = disk.visualCode().calls()
        val tableType = disk.visualCode().filter { it.opcode == Opcode.CONST_CLASS }.mapNotNull { (it.visualReference() as? TypeReference)?.type }.single()
        val table = classes.getValue(tableType)
        val tableBase = classes.getValue(table.superclass!!)
        val deleteAllRef = diskCalls.single { it.definingClass == tableBase.type && it.parameterTypes.map(Any::toString) == listOf(STRING) && it.returnType == "I" }
        val deleteAll = tableBase.methods.single { it.toString() == deleteAllRef.toString() }
        assertTrue("disk rewrite deletes old queued rows", deleteAll.visualCode().calls().any { it.name == "delete" && it.definingClass == "Landroid/database/sqlite/SQLiteDatabase;" })
        assertTrue("disk rewrite writes remaining tasks", diskCalls.any { it.definingClass == tableBase.type && it.parameterTypes.contains("Ljava/io/ByteArrayOutputStream;") })
        assertTrue("disk rewrite commits", diskCalls.any { it.definingClass == "Landroid/database/sqlite/SQLiteDatabase;" && it.name == "setTransactionSuccessful" })
    }

    companion object {
        private const val DISK_IO = "Lcom/instagram/direct/store/impl/sqlite/DirectSQLiteDiskIO;"
        private val cached = mutableMapOf<String, Map<String, ClassDef>>()
        private val anchors = setOf(VISUAL_ENDPOINT, VISUAL_MUTATION, SUCCESS_ANCHOR, DISPATCH_ANCHOR,
            "mutation_success", "IGDirectItemSeenMutation", "seenEphemeralMessageThreadData")

        internal fun fixtures(check: (File) -> Unit) {
            val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
            var count = 0
            for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                check(bundle)
                count++
            }
            assertTrue("no fixture of a declared build", count > 0)
        }

        internal fun nativeClasses(bundle: File): Map<String, ClassDef> = cached.getOrPut(bundle.absolutePath) {
            val found = mutableMapOf<String, ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                if (dex.stringSection.any { it in anchors }) for (candidate in dex.classes) {
                    if (candidate.methods.any { it.visualCode().any { ins -> ins.visualString() in anchors } }) {
                        found[candidate.type] = ImmutableClassDef.of(candidate)
                    }
                }
            }
            val handler = found.values.flatMap { it.methods }.single("visual handler") { it.visualCode().mapNotNull { ins -> ins.visualString() }.containsAll(listOf(VISUAL_ENDPOINT, VISUAL_KIND)) }
            val mutation = (handler.visualCode().first().visualReference() as TypeReference).type
            val needs = handler.parameterTypes.map(Any::toString).toMutableSet()
            needs += handler.visualCode().calls().single("response factory") { it.parameterTypes.map(Any::toString) == listOf(USER_SESSION, handler.parameterTypes[1].toString()) }.definingClass
            needs += found.getValue(handler.definingClass).methods.single { it.name == "<clinit>" }.visualCode().mapNotNull { (it.visualReference() as? FieldReference)?.definingClass }
            // 450's creator sends through a static helper, so the dispatcher's callers come too.
            val dispatch = found.values.flatMap { it.methods }.single("mutation dispatcher") {
                it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf(handler.parameterTypes[2].toString()) &&
                    it.visualCode().any { ins -> ins.visualString() == DISPATCH_ANCHOR }
            }.let { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            FixtureDex.forEach(bundle) { dex ->
                val hasMutation = dex.typeSection.any { it == mutation }
                val callsDispatch = dex.methodSection.any { it.toString() == dispatch }
                for (candidate in dex.classes) {
                    if (candidate.type in needs || hasMutation && candidate.methods.any { method ->
                        method.visualCode().any { it.opcode == Opcode.NEW_INSTANCE && (it.visualReference() as? TypeReference)?.type == mutation }
                    } || callsDispatch && candidate.methods.any { method -> method.visualCode().calls().any { it.toString() == dispatch } }
                    ) found[candidate.type] = ImmutableClassDef.of(candidate)
                }
            }
            val complete = found.getValue(handler.parameterTypes[1].toString()).methods.toList().single("completion interface") { it.returnType == "V" && it.parameterTypes.size == 2 && it.parameterTypes.last().toString() == STRING }
            val nativeCompletion = found.values.flatMap { it.methods }.single("uploaded completion") {
                it.name == complete.name && it.parameterTypes.map(Any::toString) == complete.parameterTypes.map(Any::toString) && it.visualCode().any { ins -> ins.visualString() == "uploaded" }
            }
            val queueRef = nativeCompletion.visualCode().calls().single("queue status update") { it.returnType == "Z" && it.parameterTypes.size == 2 && it.parameterTypes.first().toString() == handler.parameterTypes[2].toString() }
            found += FixtureDex.classes(bundle, setOf(queueRef.definingClass))
            val queue = found.getValue(queueRef.definingClass)
            val update = queue.methods.single { it.toString() == queueRef.toString() }
            val delayType = update.visualCode().calls().single { it.returnType == "J" && it.parameterTypes.map(Any::toString) == listOf(STRING) }.definingClass
            val registryCode = found.values.flatMap { it.methods }.single { method ->
                method.visualCode().any { it.visualString() == VISUAL_MUTATION } &&
                    method.visualCode().any { (it.visualReference() as? FieldReference)?.definingClass == handler.definingClass }
            }.visualCode()
            val nameAt = registryCode.indexOfFirst { it.visualString() == VISUAL_MUTATION }
            val descriptor = (registryCode[nameAt + 2].visualReference() as MethodReference).definingClass
            found += FixtureDex.classes(bundle, setOf(delayType, descriptor))
            val rewrite = queue.methods.toList().single("remaining queue rewrite") { it.parameterTypes.map(Any::toString) == listOf(queue.type) && it.returnType == "V" }
            val disk = rewrite.visualCode().calls().single("disk task constructor") { it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf(DISK_IO, "Ljava/util/List;") }.definingClass
            found += FixtureDex.classes(bundle, setOf(disk))
            val table = found.getValue(disk).methods.single { it.name == "run" }.visualCode()
                .filter { it.opcode == Opcode.CONST_CLASS }.mapNotNull { (it.visualReference() as? TypeReference)?.type }.single()
            found += FixtureDex.classes(bundle, setOf(table))
            found += FixtureDex.classes(bundle, setOf(found.getValue(table).superclass!!))
            found[VISUAL_SEEN] = ImmutableClassDef.of(ExtensionDex.classDef(VISUAL_SEEN))
            found
        }

        private fun List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>.calls() = mapNotNull { it.visualReference() as? MethodReference }
        private fun <T> List<T>.single(label: String, predicate: (T) -> Boolean): T = filter(predicate).let {
            check(it.size == 1) { "$label: expected one, found ${it.size}" }
            it.single()
        }

        /** Follows the native queue's status switch with "uploaded", stopping at its removal call. */
        private fun uploadedCallsRemoval(method: Method, removal: String, delay: MethodReference): Boolean {
            val flow = ControlFlow.of(method)
            val registers = mutableMapOf<Int, Any?>()
            var result: Any? = null
            var at = 0
            repeat(flow.instructions.size * 3) {
                val instruction = flow.instructions[at]
                val field = instruction.visualReference() as? FieldReference
                val call = instruction.visualReference() as? MethodReference
                when (instruction.opcode) {
                    Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST -> registers[(instruction as OneRegisterInstruction).registerA] = (instruction as NarrowLiteralInstruction).narrowLiteral
                    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> registers[(instruction as OneRegisterInstruction).registerA] = instruction.visualString()
                    Opcode.IGET_OBJECT -> registers[(instruction as OneRegisterInstruction).registerA] = if (field?.type == STRING) "uploaded" else Any()
                    Opcode.MOVE_RESULT -> registers[(instruction as OneRegisterInstruction).registerA] = result
                    Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT_WIDE -> registers[(instruction as OneRegisterInstruction).registerA] = result
                    Opcode.CONST_WIDE_16 -> registers[(instruction as OneRegisterInstruction).registerA] = (instruction as WideLiteralInstruction).wideLiteral
                    Opcode.CMP_LONG -> (instruction as ThreeRegisterInstruction).let { registers[it.registerA] = (registers[it.registerB] as Long).compareTo(registers[it.registerC] as Long) }
                    Opcode.CHECK_CAST, Opcode.INVOKE_STATIC -> Unit
                    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE -> {
                        if (call.toString() == removal) return true
                        val args = instruction.namedRegisters().map { registers[it] }
                        result = when (call?.name) {
                            "containsKey" -> 1
                            "hashCode" -> (args.first() as String).hashCode()
                            "equals" -> if (args[0] == args[1]) 1 else 0
                            else -> when {
                                call.toString() == delay.toString() -> 0L // Independently proved for the registered visual descriptor.
                                call?.returnType == delay.definingClass && call.parameterTypes.map(Any::toString) == listOf(STRING) -> Any()
                                call?.returnType == STRING && call.parameterTypes.isEmpty() -> VISUAL_MUTATION
                                else -> error("unexpected queue call $call")
                            }
                        }
                    }
                    Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_EQ, Opcode.IF_NE -> {
                        val left = registers[(instruction as OneRegisterInstruction).registerA]
                        val right = if (instruction is TwoRegisterInstruction) registers[instruction.registerB] else 0
                        val take = if (instruction.opcode in setOf(Opcode.IF_EQZ, Opcode.IF_EQ)) left == right else left != right
                        at = if (take) flow.normal[at].single { it != at + 1 } else at + 1
                        return@repeat
                    }
                    Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32 -> { at = flow.normal[at].single(); return@repeat }
                    Opcode.IF_GTZ -> {
                        val positive = (registers[(instruction as OneRegisterInstruction).registerA] as Int) > 0
                        at = if (positive) flow.normal[at].single { it != at + 1 } else at + 1
                        return@repeat
                    }
                    Opcode.RETURN -> return false
                    else -> error("unexpected queue instruction ${instruction.opcode}")
                }
                at++
            }
            error("queue status switch did not terminate")
        }

        /** The descriptor retains uploaded tasks only if its explicit retention flag is true. */
        internal fun assertUploadedDelayIsZero(registry: Method, classes: Map<String, ClassDef>, delay: MethodReference, marker: String = VISUAL_MUTATION) {
            val descriptorClass = classes.getValue(delay.definingClass)
            val constructor = descriptorClass.methods.single { it.name == "<init>" }
            val code = constructor.visualCode()
            val uploadedAt = code.indexOfFirst { it.visualString() == "uploaded" }
            assertTrue(uploadedAt >= 0)
            val boxed = code[uploadedAt + 1]
            assertEquals("Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;", boxed.visualReference().toString())
            val low = boxed.namedRegisters().first()
            val zero = code.indices.single { code[it].opcode == Opcode.CONST_WIDE_16 && (code[it] as OneRegisterInstruction).registerA == low }
            assertEquals(0L, (code[zero] as WideLiteralInstruction).wideLiteral)
            val retention = code.indices.single { code[it].opcode == Opcode.SGET_WIDE && (code[it] as OneRegisterInstruction).registerA == low }
            assertEquals(Opcode.IF_EQZ, code[retention - 1].opcode)
            assertEquals(Opcode.IGET_BOOLEAN, code[retention - 2].opcode)
            val flag = (code[retention - 2].visualReference() as FieldReference).toString()
            assertTrue("false skips the nonzero uploaded delay", retention + 1 in ControlFlow.of(constructor).normal[retention - 1])
            val builder = classes.getValue(constructor.parameterTypes.single().toString())
            val build = builder.methods.single { it.name == "<init>" }.visualCode()
            assertTrue("descriptor initialization only calls Object's constructor", build.calls().all { it.definingClass == OBJECT && it.name == "<init>" })
            assertTrue("retention starts false", build.none { it.opcode == Opcode.IPUT_BOOLEAN && it.visualReference().toString() == flag })
            val registration = registry.visualCode()
            val nameAt = registration.indexOfFirst { it.visualString() == marker }
            assertTrue("$marker is registered", nameAt >= 0)
            val wrappedAt = registration.indices.first { it > nameAt &&
                (registration[it].visualReference() as? MethodReference)?.let { ref -> ref.name == "<init>" && ref.definingClass == descriptorClass.type } == true }
            val visualBlock = registration.subList(nameAt + 2, wrappedAt)
            assertTrue("$marker's descriptor leaves retention off", visualBlock.none { it.opcode == Opcode.IPUT_BOOLEAN && it.visualReference().toString() == flag })
            for (call in visualBlock.calls().filter { it.parameterTypes.map(Any::toString) == listOf(builder.type) }) {
                val helper = classes.getValue(call.definingClass).methods.single { it.toString() == call.toString() }
                assertTrue("$marker's configuration keeps retention off", helper.visualCode().none { it.opcode == Opcode.IPUT_BOOLEAN && it.visualReference().toString() == flag })
            }
        }
    }
}
