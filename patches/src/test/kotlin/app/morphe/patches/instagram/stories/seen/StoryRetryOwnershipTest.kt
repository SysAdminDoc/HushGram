/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.MethodHandleType
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodHandleReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableMethodHandleEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryRetryOwnershipTest {
    @Test fun aCountThatErasesPendingItemsRefusesBeforeMutation() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val run = owner.methods.single { it.name == found.queue!!.run }
        val count = run.call(2)
        val pending = owner.methods.single { it.toString() == run.call(17).toString() }.reference(4)
        val changed = storyQueueMethod(owner.type, count.name, emptyList(), "I", 2, """
            iget-object v0, p0, $pending
            invoke-virtual { v0 }, Ljava/util/AbstractMap;->clear()V
            const/4 v0, 0x0
            return v0
        """)
        val map = linkedMapOf("held" to "unmarked original")
        map.clear()
        assertTrue("the substituted count removes held items before the selector can run", map.isEmpty())
        refused(replace(classes, owner, owner.methods.map { if (it.toString() == count.toString()) changed else it }))
    }

    @Test fun anAccountGetterThatErasesPendingItemsRefusesBeforeMutation() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val run = owner.methods.single { it.name == found.queue!!.run }
        val pending = owner.methods.single { it.toString() == run.call(17).toString() }.reference(4)
        val getter = owner.methods.single { it.name == found.sessionGetter && it.returnType == USER_SESSION }
        val changed = storyQueueMethod(owner.type, getter.name, emptyList(), USER_SESSION, 2, """
            iget-object v0, p0, $pending
            invoke-virtual { v0 }, Ljava/util/AbstractMap;->clear()V
            iget-object v0, p0, ${getter.reference(0)}
            return-object v0
        """)
        refused(replace(classes, owner, owner.methods.map { if (it == getter) changed else it }))
    }

    @Test fun aCountCannotHideANativeOrAbstractBody() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val run = owner.methods.single { it.name == found.queue!!.run }
        val count = owner.methods.single { it.toString() == run.call(2).toString() }
        for (flag in listOf(AccessFlags.NATIVE, AccessFlags.ABSTRACT)) refused(flags(classes, count, count.accessFlags or flag.value))
    }

    @Test fun anAccountGetterCannotHideANativeOrAbstractBody() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val getter = owner.methods.single { it.name == found.sessionGetter && it.returnType == USER_SESSION }
        for (flag in listOf(AccessFlags.NATIVE, AccessFlags.ABSTRACT)) refused(flags(classes, getter, getter.accessFlags or flag.value))
    }

    @Test fun aNativeBridgeCannotEvadeQueueDiscoveryByDroppingItsDexBody() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val store = classes.single { it.type == found.store }
        val bridge = store.methods.single { it.name == found.retry!!.name }
        val native = ImmutableMethod(bridge.definingClass, bridge.name, bridge.parameters, bridge.returnType,
            bridge.accessFlags or AccessFlags.NATIVE.value, bridge.annotations, bridge.hiddenApiRestrictions, null)
        refused(replace(classes, store, store.methods.map { if (it == bridge) native else it }))
    }

    @Test fun aSubclassOwnerAliasCannotCallTheFinalStoryBuilder() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val bridge = classes.single { it.type == found.store }.methods.single { it.name == found.retry!!.name }
        for (alias in listOf("Lfixture/StorySubclass;", "Lapp/hushgram/extension/instagram/stories/StorySubclass;")) {
            val caller = storyQueueMethod(alias, "outside", listOf("Ljava/lang/Object;"), bridge.returnType, 3, """
                invoke-virtual { p0, p1 }, $alias->${bridge.name}(Ljava/lang/Object;)${bridge.returnType}
                move-result-object v0
                return-object v0
            """)
            refused(classes + ImmutableClassDef(alias, AccessFlags.PUBLIC.value, found.store, null, null, null, null, listOf(caller)))
        }
    }

    @Test fun aSubclassOwnerAliasCannotHideAnEncodedBuilderHandle() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val bridge = classes.single { it.type == found.store }.methods.single { it.name == found.retry!!.name }
        for (alias in listOf("Lfixture/StoryHandleSubclass;", "Lapp/hushgram/extension/instagram/stories/StoryHandleSubclass;")) {
            val handle = ImmutableMethodHandleReference(MethodHandleType.INVOKE_INSTANCE,
                ImmutableMethodReference(alias, bridge.name, bridge.parameterTypes, bridge.returnType))
            val field = ImmutableField(alias, "saved", "Ljava/lang/invoke/MethodHandle;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
                ImmutableMethodHandleEncodedValue(handle), null, null)
            refused(classes + ImmutableClassDef(alias, AccessFlags.PUBLIC.value, found.store, null, null, null, listOf(field), null))
        }
    }

    @Test fun theConstructorMustCaptureItsAccountArgument() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val constructor = owner.methods.single { it.name == "<init>" }
        refused(changed(classes, constructor, 1, "iput-object v0, p0, ${constructor.reference(1)}"))
    }

    @Test fun aUserIdGetterMustReadTheNativeFinalField() = forInputs { classes ->
        val session = classes.single { it.type == USER_SESSION }
        val getter = session.methods.single { it.name == "getUserId" && it.parameterTypes.isEmpty() }
        refused(changed(classes, getter, 0, "const/4 v0, 0x0"))
    }

    @Test fun theFinalStoreCannotReplaceTheSharedAccountGetter() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val store = classes.single { it.type == found.store }
        val shadow = storyQueueMethod(store.type, found.sessionGetter, emptyList(), USER_SESSION, 2, """
            const/4 v0, 0x0
            return-object v0
        """)
        refused(replace(classes, store, store.methods + shadow))
    }

    @Test fun aCountCannotAliasItsTwoAccumulatedSizes() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val run = owner.methods.single { it.name == found.queue!!.run }
        val count = owner.methods.single { it.toString() == run.call(2).toString() }
        val accumulator = count.implementation!!.instructions.toList()[4].namedRegisters().single()
        refused(changed(classes, count, 8, "add-int/2addr v$accumulator, v$accumulator"))
    }

    @Test fun thePreselectionAssertionMustUseTheSnapshotIterator() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val run = owner.methods.single { it.name == found.queue!!.run }
        refused(changed(classes, run, 7, "invoke-static { p0 }, ${run.call(7)}"))
    }

    @Test fun assertionInitializationCannotRunBeforeSelection() = forInputs { classes ->
        val found = PatchContexts.of(classes).findStorySeen()
        val owner = classes.single { it.type == found.queue!!.owner }
        val run = owner.methods.single { it.name == found.queue!!.run }
        val assertions = classes.single { it.type == run.call(7).definingClass }
        val initialize = storyQueueMethod(assertions.type, "<clinit>", emptyList(), "V", 0, """
            invoke-static { }, Lfixture/QueueState;->clearPending()V
            return-void
        """, static = true)
        refused(replace(classes, assertions, assertions.methods + initialize))
    }

    private fun forInputs(check: (List<ClassDef>) -> Unit) {
        check(StorySeenHookTest().standIns())
        for (classes in native450) check(classes)
    }

    private fun refused(classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        assertThrows(PatchException::class.java) { context.holdBackStoryViews() }
        for (type in classes) for (method in type.methods) {
            val after = context.classDefByOrNull(type.type)!!.methods.single { it.toString() == method.toString() }
            assertEquals("${method} changed before refusal", method.proof(), after.proof())
        }
    }

    private fun replace(classes: List<ClassDef>, owner: ClassDef, methods: Iterable<Method>) = classes.map {
        if (it.type != owner.type) it else ImmutableClassDef(owner.type, owner.accessFlags, owner.superclass, owner.interfaces,
            null, null, owner.fields, methods)
    }
    private fun changed(classes: List<ClassDef>, method: Method, at: Int, body: String): List<ClassDef> {
        val mutable = MutableMethod(ImmutableMethod.of(method))
        mutable.replaceInstruction(at, body)
        val owner = classes.single { it.type == method.definingClass }
        return replace(classes, owner, owner.methods.map { if (it == method) ImmutableMethod.of(mutable) else it })
    }
    private fun flags(classes: List<ClassDef>, method: Method, access: Int): List<ClassDef> {
        val changed = ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
            access, method.annotations, method.hiddenApiRestrictions, method.implementation)
        val owner = classes.single { it.type == method.definingClass }
        return replace(classes, owner, owner.methods.map { if (it == method) changed else it })
    }
    private fun Method.reference(at: Int) = (implementation!!.instructions.toList()[at] as ReferenceInstruction).reference
    private fun Method.call(at: Int) = reference(at) as MethodReference
    private fun Method.proof() = implementation?.instructions?.toList()?.map { listOf(it.opcode,
        (it as? ReferenceInstruction)?.reference?.toString(), it.namedRegisters(),
        (it as? NarrowLiteralInstruction)?.narrowLiteral, (it as? OffsetInstruction)?.codeOffset) }

    companion object {
        private val native450 by lazy {
            Fixtures.files { it.name == "instagram-450.0.0.50.77-385611438.apks" }
                .map { StorySeenHookTest().fixtureClasses(it) }
        }
    }
}
