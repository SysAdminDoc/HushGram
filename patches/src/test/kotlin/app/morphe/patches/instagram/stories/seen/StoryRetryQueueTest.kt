/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.MethodHandleType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.MethodHandleReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction45cc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableCallSiteReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodHandleReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodProtoReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableArrayEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableMethodHandleEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryRetryQueueTest {
    private val fixtures = StorySeenHookTest()

    @Test fun cancellationSkipsClaimAndContinuesTheOwnedSnapshotWithoutChangingEitherMap() = verify(fixtures.standIns())

    @Test fun aStockNullRequestStaysOnTheOriginalNativePath() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        verify(classes)
        val context = PatchContexts.of(classes)
        context.holdBackStoryViews()
        // Fault injection after discovery models a native request becoming null at run time.
        val bridge = context.mutableClassDefBy(found.store).methods.single { it.name == found.retry!!.name }
        val last = bridge.code().lastIndex
        val register = bridge.code().last().namedRegisters().single()
        bridge.replaceInstruction(last, "const/16 v$register, 0x0")
        bridge.addInstructionsWithLabels(last + 1, "return-object v$register")
        assertEquals(Opcode.CONST_16, bridge.code()[last].opcode)
        assertEquals(Opcode.RETURN_OBJECT, bridge.code().last().opcode)
        val queue = found.queue!!
        val loop = context.mutableClassDefBy(queue.owner).methods.single { it.name == queue.run }.code()
        val native = queue.beforeClaim + 6 + 3
        assertEquals("the stock builder receives the selected batch", queue.batch, loop[native].namedRegisters()[1])
        assertEquals(Opcode.MOVE_RESULT_OBJECT, loop[native + 1].opcode)
        assertEquals("stock null results go through the original callback and scheduler", Opcode.INVOKE_VIRTUAL, loop[native + 2].opcode)
        assertTrue("no cancellation decision reads the native request result", loop.drop(native).none { it.reference() == TO_RETRY })
    }

    @Test fun anotherPendingStoreCanReturnNullWithoutStorySelection() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val bridge = classes.single { it.type == found.store }.methods.single { it.name == found.retry!!.name }
        val other = "Lfixture/OtherPendingStore;"
        val nullBuilder = storyQueueMethod(other, bridge.name, listOf("Ljava/lang/Object;"), bridge.returnType, 3, """
            const/4 v0, 0x0
            return-object v0
        """)
        val unrelated = ImmutableClassDef(other, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            found.queue!!.owner, null, null, null, null, listOf(nullBuilder))
        verify(classes + unrelated)
        val context = PatchContexts.of(classes + unrelated)
        context.holdBackStoryViews()
        val queue = found.queue
        val loop = context.mutableClassDefBy(queue.owner).methods.single { it.name == queue.run }.code()
        assertEquals("only the story store is tested", found.store, loop[queue.beforeClaim].reference())
        assertEquals("another store goes directly to its original native claim", queue.beforeClaim + 6,
            target(loop, queue.beforeClaim + 1))
        val kept = context.classDefByOrNull(other)!!.methods.single()
        assertEquals(nullBuilder.code().map { it.opcode to it.reference() }, kept.code().map { it.opcode to it.reference() })
    }

    @Test fun declaredNativeBuildPreservesTheQueueDiskCleanupAndOtherStores() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            verify(fixtures.fixtureClasses(bundle))
            checked += version
        }
        assertEquals(versions, checked)
    }

    @Test fun sharedOrMutableInFlightMapRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val fields = owner.fields.map { field -> if (field.type == "Ljava/util/Map;")
            ImmutableField(field.definingClass, field.name, field.type, field.accessFlags and AccessFlags.FINAL.value.inv(), null, null, null)
            else field }
        refused(replace(classes, owner, fields = fields), "ownership field isn't final")
        val constructor = owner.methods.single { it.name == "<init>" }
        refused(changed(classes, constructor, 5, "new-instance v0, Ljava/util/LinkedHashMap;"), "doesn't create its own native map")
    }

    @Test fun liveMapIterationRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val method = classes.single { it.type == found.queue!!.owner }.methods.single { it.name == "A05" }
        refused(changed(classes, method, 5, "new-instance v0, Ljava/util/HashMap;"), "iteration isn't over an owned key snapshot")
    }

    @Test fun wrongClaimValueRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val method = classes.single { it.type == found.queue!!.owner }.methods.single { it.name == "A0G" }
        refused(changed(classes, method, 15, "invoke-interface { v2, p1, v0 }, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), "ownership registers")
    }

    @Test fun foreignLookupRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = found.queue!!.owner
        val method = classes.single { it.type == owner }.methods.single { it.name == "A04" }
        refused(changed(classes, method, 6, "iget-object v1, p0, $owner->pending:Ljava/util/LinkedHashMap;"), "lookup uses another store's maps")
    }

    @Test fun claimBranchOperandsAndSuccessOrFailureLiteralsRefuseUntouchedOnNative450() {
        for (classes in proofInputs()) {
            val found = PatchContexts.of(classes).findStorySeen()
            val owner = classes.single { it.type == found.queue!!.owner }
            val run = owner.methods.single { it.name == found.queue!!.run }
            val claimKey = run.code()[17].reference()
            val claim = owner.methods.single { it.toString() == claimKey }
            val lookupKey = run.code()[14].reference()
            val lookup = owner.methods.single { it.toString() == lookupKey }
            refused(changed(classes, claim, 7, ImmutableInstruction21t(Opcode.IF_NEZ, claim.parameterRegisterNumber(0),
                (claim.code()[7] as OffsetInstruction).codeOffset)), "ownership registers at 7")
            refused(changed(classes, lookup, 5, ImmutableInstruction21t(Opcode.IF_NEZ, lookup.parameterRegisterNumber(0),
                (lookup.code()[5] as OffsetInstruction).codeOffset)), "ownership registers at 5")
            refused(changed(classes, claim, 17, "const/4 v${claim.code()[17].namedRegisters().single()}, 0x0"), "native literal at 17")
            refused(changed(classes, claim, 0, "const/4 v${claim.code()[0].namedRegisters().single()}, 0x1"), "native literal at 0")
        }
    }

    @Test fun concreteBridgeAndAbstractBuilderCallersRefuseUntouchedOnNative450() {
        for (classes in proofInputs()) {
            val found = PatchContexts.of(classes).findStorySeen()
            val bridge = classes.single { it.type == found.store }.methods.single { it.name == found.retry!!.name }
            for (owner in listOf(found.store, found.queue!!.owner, "Ljava/lang/Object;"))
                for (type in listOf("Lfixture/OutsideQueue;", "Lapp/hushgram/extension/instagram/stories/OutsideQueue;")) {
                val caller = storyQueueMethod(type, "build", listOf(found.store, "Ljava/lang/Object;"), bridge.returnType, 4, """
                    invoke-virtual { p1, p2 }, $owner->${bridge.name}(Ljava/lang/Object;)${bridge.returnType}
                    move-result-object v0
                    return-object v0
                """)
                val extra = ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(caller))
                refused(classes + extra, if (owner == found.queue.owner) "expected one request-builder call" else "concrete story builder has a caller")
            }
        }
    }

    @Test fun overwrittenMonitorAndAliasedClaimMapsRefuseUntouchedOnNative450() {
        for (classes in proofInputs()) {
            val found = PatchContexts.of(classes).findStorySeen()
            val owner = classes.single { it.type == found.queue!!.owner }
            val run = owner.methods.single { it.name == found.queue!!.run }
            val snapshot = owner.methods.single { it.toString() == run.code()[3].reference() }
            val lock = snapshot.code()[0].namedRegisters().first()
            val collection = snapshot.code()[4].namedRegisters().single()
            refused(changedAll(classes, snapshot,
                5 to "new-instance v$lock, Ljava/util/ArrayList;",
                6 to "invoke-direct { v$lock, v$collection }, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V",
                8 to "return-object v$lock"), "overwrites its monitor")
            val claim = owner.methods.single { it.toString() == run.code()[17].reference() }
            val pending = claim.code()[4].namedRegisters().first()
            val flight = claim.code()[10].reference()
            val key = claim.parameterRegisterNumber(0)
            val removed = claim.code()[12].namedRegisters().single()
            refused(changedAll(classes, claim,
                10 to "iget-object v$pending, p0, $flight",
                15 to "invoke-interface { v$pending, v$key, v$removed }, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
                "claim aliases its live ownership registers")
        }
    }

    @Test fun methodHandlesAndCallSiteArgumentsCannotBypassAnyBridgeOnNative450() {
        for (classes in proofInputs()) {
            val found = PatchContexts.of(classes).findStorySeen()
            val bridge = classes.single { it.type == found.store }.methods.single { it.name == found.retry!!.name }
            for (owner in listOf(found.store, found.queue!!.owner, "Ljava/lang/Object;")) {
                val handle = ImmutableMethodHandleReference(MethodHandleType.INVOKE_INSTANCE,
                    ImmutableMethodReference(owner, bridge.name, bridge.parameterTypes, bridge.returnType))
                val bootstrap = ImmutableMethodHandleReference(MethodHandleType.INVOKE_STATIC,
                    ImmutableMethodReference("Lfixture/Bootstrap;", "capture", emptyList(), "Ljava/lang/invoke/CallSite;"))
                val callSite = ImmutableCallSiteReference("queue", bootstrap as MethodHandleReference, "build", ImmutableMethodProtoReference(emptyList(), "V"),
                    listOf(ImmutableArrayEncodedValue(listOf(ImmutableMethodHandleEncodedValue(handle)))))
                for (instruction in listOf(
                    ImmutableInstruction21c(Opcode.CONST_METHOD_HANDLE, 0, handle),
                    ImmutableInstruction35c(Opcode.INVOKE_CUSTOM, 0, 0, 0, 0, 0, 0, callSite),
                )) for (type in listOf("Lfixture/IndirectQueueCaller;", "Lapp/hushgram/extension/instagram/stories/IndirectQueueCaller;")) {
                    val caller = storyQueueMethod(type, "capture", emptyList(), "V", 1, "const/4 v0, 0x0\nreturn-void", static = true)
                    val extra = ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(caller))
                    refused(changed(classes + extra, caller, 0, instruction), "indirect story builder reference")
                }
            }
        }
    }

    @Test fun loopResultsCannotOverwriteTheLiveBatchOrIteratorOnNative450() {
        for (classes in proofInputs()) {
            val found = PatchContexts.of(classes).findStorySeen()
            val run = classes.single { it.type == found.queue!!.owner }.methods.single { it.name == found.queue!!.run }
            for ((result, branch, register) in listOf(
                Triple(18, 19, found.queue!!.batch),
                Triple(9, 10, run.code()[6].namedRegisters().single()),
            )) {
                val alias = changed(classes, run, result, ImmutableInstruction11x(Opcode.MOVE_RESULT, register))
                val aliasedRun = alias.single { it.type == run.definingClass }.methods.single { it.name == run.name }
                refused(changed(alias, aliasedRun, branch, ImmutableInstruction21t(Opcode.IF_EQZ, register,
                    (run.code()[branch] as OffsetInstruction).codeOffset)), "loop aliases its live ownership registers")
            }
        }
    }

    @Test fun anInterfaceCannotAddDispatchToTheFinalStoryBridgeOnNative450() {
        for (classes in proofInputs()) {
            val found = PatchContexts.of(classes).findStorySeen()
            val store = classes.single { it.type == found.store }
            val bridge = store.methods.single { it.name == found.retry!!.name }
            val contract = "Lfixture/StoryBuilder;"
            val declaration = ImmutableMethod(contract, bridge.name, bridge.parameters, bridge.returnType,
                AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)
            val interfaceType = ImmutableClassDef(contract,
                AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
                "Ljava/lang/Object;", null, null, null, null, listOf(declaration))
            val changedStore = ImmutableClassDef(store.type, store.accessFlags, store.superclass,
                listOf(contract), null, null, store.fields, store.methods)
            val callerType = "Lfixture/InterfaceQueueCaller;"
            val caller = storyQueueMethod(callerType, "build", listOf(contract, "Ljava/lang/Object;"), bridge.returnType, 4, """
                invoke-interface { p1, p2 }, $contract->${bridge.name}(Ljava/lang/Object;)${bridge.returnType}
                move-result-object v0
                return-object v0
            """)
            val extra = ImmutableClassDef(callerType, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(caller))
            refused(classes.map { if (it.type == store.type) changedStore else it } + interfaceType + extra, "store isn't final without interfaces")
        }
    }

    @Test fun encodedStaticHandlesCannotBypassAnyBridgeOnNative450() {
        for (classes in proofInputs()) {
            val found = PatchContexts.of(classes).findStorySeen()
            val bridge = classes.single { it.type == found.store }.methods.single { it.name == found.retry!!.name }
            for (owner in listOf(found.store, found.queue!!.owner, "Ljava/lang/Object;")) {
                val type = "Lfixture/EncodedQueueCaller;"
                val handle = ImmutableMethodHandleReference(MethodHandleType.INVOKE_INSTANCE,
                    ImmutableMethodReference(owner, bridge.name, bridge.parameterTypes, bridge.returnType))
                val field = ImmutableField(type, "build", "Ljava/lang/invoke/MethodHandle;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
                    ImmutableMethodHandleEncodedValue(handle), null, null)
                val caller = storyQueueMethod(type, "call", listOf(found.store, "Ljava/lang/Object;"), bridge.returnType, 3,
                    "const/4 v0, 0x0\nconst/4 v0, 0x0\nmove-result-object v0\nreturn-object v0", static = true)
                val extra = ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, listOf(field), listOf(caller))
                val withField = changed(classes + extra, caller, 0,
                    ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(type, field.name, field.type)))
                val fieldCaller = withField.single { it.type == type }.methods.single()
                val code = changed(withField, fieldCaller, 1, ImmutableInstruction45cc(Opcode.INVOKE_POLYMORPHIC, 3, 0, 1, 2, 0, 0,
                        ImmutableMethodReference("Ljava/lang/invoke/MethodHandle;", "invokeExact", listOf("[Ljava/lang/Object;"), "Ljava/lang/Object;"),
                        ImmutableMethodProtoReference(listOf(owner, "Ljava/lang/Object;"), bridge.returnType)))
                refused(code, "encoded story builder reference")
            }
        }
    }

    @Test fun missingMonitorHandlerRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val method = owner.methods.single { it.name == found.queue!!.run }
        val bad = ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType, method.accessFlags, null, null,
            ImmutableMethodImplementation(method.implementation!!.registerCount, method.code(), emptyList(), null))
        refused(replace(classes, owner, methods = owner.methods.map { if (it == method) bad else it }), "catch-all lock cleanup")
    }

    @Test fun anotherRequestBuilderCallerRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val run = owner.methods.single { it.name == found.queue!!.run }
        val second = ImmutableMethod(run.definingClass, "anotherLoop", run.parameters, run.returnType, run.accessFlags, null, null, run.implementation)
        refused(replace(classes, owner, methods = owner.methods.toList() + second), "expected one request-builder call")
    }

    @Test fun missingDiskCleanupRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val reader = classes.single { it.type == found.store }.methods.single { it.name == "A0L" }
        refused(changed(classes, reader, 15, "nop"), "disk reader no longer reaches its native cleanup")
    }

    @Test fun diskCleanupCalleeReceiverAndKeyMustMatchTheNativeRead() {
        for (classes in proofInputs()) {
            val found = PatchContexts.of(classes).findStorySeen()
            val reader = classes.single { it.type == found.store }.methods.single { method ->
                method.code().any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "PendingReelSeenStateStore.deserializeFromDisk" }
            }
            val code = reader.code()
            val loop = code.indexOfFirst { it.reference() == "${found.queue!!.owner}->${found.queue.run}()V" }
            val remove = loop + 4
            val registers = code[remove].namedRegisters()
            // Obfuscated names differ per build, so take the cleanup call and the key builder from the reader itself.
            val cleanup = code[remove].reference()!!
            val keyBuilder = code[loop + 2].reference()!!
            refused(changed(classes, reader, remove, "invoke-virtual { v${registers[0]}, v${registers[1]} }, ${cleanup.substringBefore("->")}->preserve(Ljava/lang/String;)V"), "native cleanup")
            refused(changed(classes, reader, remove, "invoke-virtual { v${code[loop + 2].namedRegisters()[0]}, v${registers[1]} }, $cleanup"), "native receiver")
            refused(changed(classes, reader, remove, "invoke-virtual { v${registers[0]}, v${code[loop + 2].namedRegisters()[0]} }, $cleanup"), "native cleanup")
            val key = code[loop + 2].namedRegisters()
            refused(changed(classes, reader, loop + 2, "invoke-static { v${key[1]}, v${key[1]} }, $keyBuilder"), "pending-story prefix")
            val load = code.indexOfFirst { val ref = it.reference()
                ref != null && ref.startsWith(cleanup.substringBefore("->")) && ref.endsWith("(Ljava/lang/String;Z)Ljava/lang/Object;") }
            refused(changed(classes, reader, load - 1, "const/4 v${code[load - 1].namedRegisters().single()}, 0x0"), "consumes the copy it read")
            for (register in listOf(registers[0], key[0], code[loop + 1].namedRegisters().last())) {
                refused(inserted(classes, reader, loop, "const/4 v$register, 0x0"), "changed receiver, prefix or account dataflow")
            }
        }
    }

    @Test fun bridgeWithWorkAfterItsBuildRefusesUntouched() {
        val classes = fixtures.standIns()
        val found = PatchContexts.of(classes).findStorySeen()
        val retry = found.retry!!
        val method = classes.single { it.type == found.store }.methods.single { it.name == retry.name }
        refused(changed(classes, method, retry.build + 2, "invoke-static { }, Lfixture/Checks;->cleanup()V"), "bridge has cleanup or other work")
    }

    @Test fun nativeDiskHelpersRefuseChangedKeyCaptureTaskIdentityAndCleanupUntouched() {
        for (classes in proofInputs()) {
            val found = PatchContexts.of(classes).findStorySeen()
            val reader = classes.single { it.type == found.store }.methods.single { method ->
                method.code().any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "PendingReelSeenStateStore.deserializeFromDisk" }
            }
            val at = reader.code().indexOfFirst { it.reference() == "${found.queue!!.owner}->${found.queue.run}()V" }
            val key = classes.flatMap { it.methods }.single { it.toString() == reader.code()[at + 2].reference() }
            val builder = key.code()[0].namedRegisters().single()
            refused(changed(classes, key, 2, "invoke-virtual { v$builder, p1 }, ${key.code()[2].reference()}"), "ownership registers at 2")
            refused(changed(classes, key, 6, "return-object p0"), "ownership registers at 6")

            val cleanup = classes.flatMap { it.methods }.single { it.toString() == reader.code()[at + 4].reference() }
            val task = classes.single { it.type == cleanup.code()[3].reference() }
            val constructor = task.methods.single { it.name == "<init>" }
            val run = task.methods.single { it.name == "run" && it.parameterTypes.isEmpty() }
            val noCatch = ImmutableMethod(run.definingClass, run.name, run.parameters, run.returnType, run.accessFlags, null, null,
                ImmutableMethodImplementation(run.implementation!!.registerCount, run.code(), emptyList(), null))
            refused(replace(classes, task, methods = task.methods.map { if (it == run) noCatch else it }), "task control flow")
            refused(changed(classes, constructor, 1, "const/16 v${constructor.code()[1].namedRegisters().single()}, 0x1fe"), "native literal at 1")
            refused(changed(classes, constructor, 5, "iput-object p1, p0, ${constructor.code()[5].reference()}"), "ownership registers at 5")
            refused(changed(classes, run, 4, "const/4 v${run.code()[4].namedRegisters().single()}, 0x1"), "native literal at 4")
            val backend = run.code()[5].reference()!!.substringBefore("->")
            val erase = run.code()[5].namedRegisters().joinToString(", ") { "v$it" }
            refused(changed(classes, run, 5, "invoke-interface { $erase }, $backend->preserve(Ljava/lang/String;Ljava/util/Map;)V"), "native null-map operation")
            refused(changed(classes, run, 1, "invoke-static { v${run.code()[3].namedRegisters().first()} }, ${run.code()[1].reference()}"), "ownership registers at 1")
            val executor = cleanup.code()[2].namedRegisters().first()
            val created = cleanup.code()[3].namedRegisters().single()
            refused(changedAll(classes, cleanup,
                2 to "iget-object v$created, p0, ${cleanup.code()[2].reference()}",
                5 to "invoke-virtual { v$created, v$created }, ${cleanup.code()[5].reference()}"), "aliases its captured task and executor")
            val submit = cleanup.code()[5].reference()!!.substringBefore("->")
            val parent = task.superclass
            refused(changed(classes, cleanup, 5, "invoke-virtual { v$executor, v$created }, $submit->preserve($parent)V"), "native task submission")
        }
    }

    private fun verify(classes: List<ClassDef>) {
        val found = PatchContexts.of(classes).findStorySeen()
        val queue = found.queue!!
        val original = classes.single { it.type == queue.owner }.methods.single { it.name == queue.run }
        val context = PatchContexts.of(classes)
        context.holdBackStoryViews()
        val patched = context.mutableClassDefBy(queue.owner).methods.single { it.name == queue.run }
        val code = patched.code()
        val at = queue.beforeClaim
        assertEquals(listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
            Opcode.IF_NEZ, Opcode.GOTO), code.subList(at, at + 6).map { it.opcode })
        assertEquals(listOf(queue.scratch, original.localRegisterCount()), code[at].namedRegisters())
        assertEquals("only the final story store can take the cancellation path", found.store, code[at].reference())
        assertEquals(TO_RETRY, code[at + 2].reference())
        assertEquals(listOf(original.localRegisterCount(), queue.batch), code[at + 2].namedRegisters())
        assertEquals(listOf(queue.batch), code[at + 3].namedRegisters())
        assertEquals(listOf(queue.batch), code[at + 4].namedRegisters())
        assertEquals(at + 6, target(code, at + 1))
        assertEquals(at + 6, target(code, at + 4))
        assertEquals(queue.loop, target(code, at + 5))
        assertEquals("selection precedes the native claim", original.code()[17].reference(), code[at + 6].reference())
        assertEquals("the selected local reaches the native request builder", listOf(original.localRegisterCount(), queue.batch), code[at + 9].namedRegisters())
        preserved(original, patched, at, 6)

        assertTrue("cancellation allocates no batch, callback, lambda or request", code.subList(at, at + 6)
            .none { it.opcode in setOf(Opcode.NEW_INSTANCE, Opcode.NEW_ARRAY, Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE) })
        assertEquals("after a null selection there is only the native snapshot-loop backedge", listOf(Opcode.GOTO), code.subList(at + 5, at + 6).map { it.opcode })
        for (type in classes) for (method in type.methods) {
            if (type.type == queue.owner && method.name == queue.run || type.type == found.store && method.name == found.send ||
                type.type == found.binder && method.name == found.binderName || type.type.startsWith("Lapp/hushgram/extension/")) continue
            val after = context.classDefByOrNull(type.type)!!.methods.single { it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType }
            assertEquals("${type.type}->${method.name} changed", method.code().map { it.opcode to it.reference() }, after.code().map { it.opcode to it.reference() })
        }
    }

    private fun preserved(before: Method, after: Method, at: Int, added: Int) {
        val old = before.code()
        val new = after.code()
        fun mapped(index: Int) = if (index < at) index else index + added
        assertEquals(old.size + added, new.size)
        for (i in old.indices) {
            val changed = mapped(i)
            assertEquals("opcode $i changed", old[i].opcode, new[changed].opcode)
            assertEquals("reference $i changed", old[i].reference(), new[changed].reference())
            assertEquals("registers $i changed", old[i].namedRegisters(), new[changed].namedRegisters())
            assertEquals("literal $i changed", (old[i] as? NarrowLiteralInstruction)?.narrowLiteral, (new[changed] as? NarrowLiteralInstruction)?.narrowLiteral)
            if (old[i] is OffsetInstruction) assertEquals("native branch $i changed", mapped(target(old, i)), target(new, changed))
        }
        val oldAddresses = addresses(old)
        val newAddresses = addresses(new)
        fun moved(address: Int) = newAddresses[mapped(oldAddresses.indexOf(address))]
        val blocks = after.implementation!!.tryBlocks.toList()
        assertEquals(before.implementation!!.tryBlocks.size, blocks.size)
        for ((i, block) in before.implementation!!.tryBlocks.withIndex()) {
            assertEquals(moved(block.startCodeAddress), blocks[i].startCodeAddress)
            assertEquals(moved(block.startCodeAddress + block.codeUnitCount), blocks[i].startCodeAddress + blocks[i].codeUnitCount)
            assertEquals(block.exceptionHandlers.map { it.exceptionType to moved(it.handlerCodeAddress) },
                blocks[i].exceptionHandlers.map { it.exceptionType to it.handlerCodeAddress })
        }
    }

    private fun refused(classes: List<ClassDef>, reason: String) {
        val context = PatchContexts.of(classes)
        val error = assertThrows(PatchException::class.java) { context.holdBackStoryViews() }
        assertTrue(error.message, error.message!!.startsWith("$PATCH: story retry queue") && reason in error.message!!)
        for (type in classes) for (method in type.methods) {
            val after = context.classDefByOrNull(type.type)!!.methods.single { it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType }
            assertEquals("${type.type}->${method.name} was mutated before refusal", method.proof(), after.proof())
        }
    }

    private fun changed(classes: List<ClassDef>, method: Method, at: Int, body: String): List<ClassDef> {
        return changedAll(classes, method, at to body)
    }

    private fun changedAll(classes: List<ClassDef>, method: Method, vararg changes: Pair<Int, String>): List<ClassDef> {
        val mutable = MutableMethod(ImmutableMethod.of(method))
        for ((at, body) in changes) mutable.replaceInstruction(at, body)
        val owner = classes.single { it.type == method.definingClass }
        return replace(classes, owner, methods = owner.methods.map { if (it == method) ImmutableMethod.of(mutable) else it })
    }

    private fun changed(classes: List<ClassDef>, method: Method, at: Int, instruction: Instruction): List<ClassDef> {
        val replacement = ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType, method.accessFlags, null, null,
            ImmutableMethodImplementation(method.implementation!!.registerCount, method.code().mapIndexed { index, old -> if (index == at) instruction else old },
                method.implementation!!.tryBlocks, null))
        val owner = classes.single { it.type == method.definingClass }
        return replace(classes, owner, methods = owner.methods.map { if (it == method) replacement else it })
    }

    private fun inserted(classes: List<ClassDef>, method: Method, at: Int, body: String): List<ClassDef> {
        val mutable = MutableMethod(ImmutableMethod.of(method))
        mutable.addInstructionsWithLabels(at, body)
        // Mutate an executed path. Native labels otherwise stay on the old instruction and skip
        // an inserted overwrite, which would be an inert counterexample on native450.
        val code = mutable.code().toMutableList()
        val added = code.size - method.code().size
        val addresses = addresses(code)
        for ((index, instruction) in method.code().withIndex()) {
            if (instruction !is OffsetInstruction || target(method.code(), index) != at) continue
            val now = if (index < at) index else index + added
            code[now] = ImmutableInstruction21t(instruction.opcode, instruction.namedRegisters().single(), addresses[at] - addresses[now])
        }
        val replacement = ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType, method.accessFlags, null, null,
            ImmutableMethodImplementation(method.implementation!!.registerCount, code, mutable.implementation!!.tryBlocks, null))
        val owner = classes.single { it.type == method.definingClass }
        return replace(classes, owner, methods = owner.methods.map { if (it == method) replacement else it })
    }

    private fun proofInputs(): List<List<ClassDef>> {
        assertTrue("the authoritative declared 450 build (385611438) is required", nativeProofInputs.isNotEmpty())
        return nativeProofInputs + listOf(fixtures.standIns())
    }
    private fun replace(classes: List<ClassDef>, owner: ClassDef, fields: Iterable<com.android.tools.smali.dexlib2.iface.Field> = owner.fields,
        methods: Iterable<Method> = owner.methods): List<ClassDef> = classes.map { if (it.type != owner.type) it else
        ImmutableClassDef(owner.type, owner.accessFlags, owner.superclass, owner.interfaces, null, null, fields, methods) }
    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Method.proof() = code().map { listOf(it.opcode, it.reference(), it.namedRegisters(),
        (it as? NarrowLiteralInstruction)?.narrowLiteral, (it as? OffsetInstruction)?.codeOffset) }
    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
    private fun addresses(code: List<Instruction>) = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    private fun target(code: List<Instruction>, at: Int): Int {
        val addresses = addresses(code)
        return addresses.indexOf(addresses[at] + (code[at] as OffsetInstruction).codeOffset)
    }

    companion object {
        private val nativeProofInputs by lazy {
            Fixtures.files { it.name == "instagram-450.0.0.50.77-385611438.apks" }
                .map { StorySeenHookTest().fixtureClasses(it) }
        }
    }
}
