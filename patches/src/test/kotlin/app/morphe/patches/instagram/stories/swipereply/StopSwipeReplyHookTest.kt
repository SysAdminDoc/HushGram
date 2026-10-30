/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.swipereply

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StopSwipeReplyHookTest {
    private val delegate = "Lfixture/ItemDelegate;"
    private val manager = "Lfixture/ListenerManager;"
    private val listener = "Lfixture/SwipeListener;"
    private val composer = "Lfixture/Composer;"
    private val gestureAt = "$delegate->GKg"
    private val managerAt = "$manager->GKf"
    private val composerAt = "$composer->GKf"

    /** Where the stand-in delegate's code puts the sheet's source and the listener block, before the patch. */
    private val sheet = 4
    private val listeners = 7

    /** The hook the patch writes is in the StorySwipeReply the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_SWIPE_REPLY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$KEEPS_SWIPE is not in the extension: $declared", KEEPS_SWIPE.substringAfter("->") in declared)
    }

    /**
     * The composer's listener answers false first thing on a no, and the delegate asks right before
     * the sheet's source and jumps to its listener block on a no. Everything Instagram had is still
     * there after the guards, and Instagram's own jump to the listener block still lands on the read
     * of the listener manager.
     */
    @Test
    fun theSwipeIsGuardedAtBothPlaces() {
        val stock = classes()
        val context = PatchContexts.of(stock)

        context.stopSwipeReply()

        val before = stock.single { it.type == composer }.methods.single { it.name == "GKf" }.code()
        val guarded = context.mutableClassDefBy(composer).methods.single { it.name == "GKf" }.code()
        assertEquals("composer size", before.size + 5, guarded.size)
        assertEquals(KEEPS_SWIPE, guarded[0].referenceText())
        assertEquals(Opcode.MOVE_RESULT, guarded[1].opcode)
        assertEquals(0, (guarded[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_NEZ, guarded[2].opcode)
        assertEquals("a yes goes on to Instagram's first instruction", 5, guarded.target(2))
        assertEquals(Opcode.CONST_4, guarded[3].opcode)
        assertEquals(0, (guarded[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, guarded[4].opcode)
        assertEquals(before.map { it.opcode }, guarded.drop(5).map { it.opcode })

        val gesture = context.mutableClassDefBy(delegate).methods.single { it.name == "GKg" }.code()
        assertEquals(KEEPS_SWIPE, gesture[sheet].referenceText())
        assertEquals(Opcode.MOVE_RESULT, gesture[sheet + 1].opcode)
        assertEquals("the register nothing reads afterwards", 0, (gesture[sheet + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, gesture[sheet + 2].opcode)
        assertEquals(0, (gesture[sheet + 2] as OneRegisterInstruction).registerA)
        assertEquals("a no goes to the listener manager", listeners + 3, gesture.target(sheet + 2))
        assertEquals("$delegate->A0q:$manager", gesture[listeners + 3].referenceText())
        assertEquals(Opcode.SGET_OBJECT, gesture[sheet + 3].opcode)
        assertEquals("$delegate->GZO(Ljava/lang/Integer;)V", gesture[sheet + 4].referenceText())
        assertEquals("Instagram's own jump still lands on the listener manager", listeners + 3, gesture.target(3))

        val hooked = stock.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
            .filter { method -> method.code().any { it.referenceText() == KEEPS_SWIPE } }
        assertEquals(listOf("GKg", "GKf"), hooked.map { it.name }.sortedDescending())
    }

    /** A second run finds its own guard and refuses instead of asking twice. */
    @Test
    fun aSecondRunRefuses() {
        val context = PatchContexts.of(classes())
        context.stopSwipeReply()
        val failure = assertThrows(PatchException::class.java) { context.stopSwipeReply() }
        assertTrue(failure.message, failure.message!!.contains("$gestureAt already calls $KEEPS_SWIPE"))
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(delegateName = "ReelViewerFragment") to "expected one gesture handler of the story viewer's item delegate ($ITEM_DELEGATE) in this Instagram build, found 0",
            classes(twoDelegates = true) to "expected one gesture handler of the story viewer's item delegate ($ITEM_DELEGATE) in this Instagram build, found 2",
            classes(holdsName = false) to "found 0",
            classes(sheetCalls = 0) to "expected $gestureAt to open one reply sheet, an (Integer)V call on its own class, found 0",
            classes(sheetCalls = 2) to "expected $gestureAt to open one reply sheet, an (Integer)V call on its own class, found 2",
            classes(staticSource = false) to "$gestureAt doesn't load the reply sheet's source from a static Integer right before opening it",
            classes(returnsAfterSheet = false) to "$gestureAt doesn't return right after opening the reply sheet",
            classes(readsManager = false) to "$gestureAt doesn't read its listener manager after the reply sheet",
            classes(otherAsker = true) to "expected only $gestureAt to ask $managerAt, found",
            classes(listenerAsks = 0) to "expected $managerAt to ask one listener's GKf()Z, found ",
            classes(listenerElsewhere = true) to "expected only $managerAt to ask $listener->GKf, found",
            classes(composers = 0) to "expected one $listener focusing a text box when the story is swiped up, found 0",
            classes(composers = 2) to "expected one $listener focusing a text box when the story is swiped up, found 2",
            classes(directCall = true) to "expected only the swipe to reach $composerAt, Lfixture/Tapper; calls it too",
            classes(composerTopIsTarget = true) to "a branch in $composerAt lands on its first instruction, where the guard goes",
            classes(sheetIsTarget = true) to "a branch in $gestureAt lands on the reply sheet's source, where the guard goes",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.stopSwipeReply() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == KEEPS_SWIPE } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the swipe goes from the class Instagram names [ITEM_DELEGATE] to the
     * composer's listener, and both guards go in, nothing else asking.
     */
    @Test
    fun eachDeclaredBuildGuardsTheSwipe() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildGuardsTheSwipeToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /**
     * Reads the classes the patch looks through: the delegate, what holds [LISTENER_MANAGER], what
     * calls [REQUEST_FOCUS], then the delegate's LX/ field types and those callers again, and whatever
     * calls into any of them. The first pass reads the whole build, so the checks that only the
     * delegate asks the listener manager and only the composer calls [REQUEST_FOCUS] still see every
     * class that could break them.
     */
    private fun builds(bundle: File, label: String) {
        val first = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.originalName() == ITEM_DELEGATE ||
                    classDef.methods.any { m -> m.code().any { it.referenceText() == REQUEST_FOCUS || it.string() == LISTENER_MANAGER } }
                ) {
                    first += ImmutableClassDef.of(classDef)
                }
            }
        }
        val delegates = first.filter { it.originalName() == ITEM_DELEGATE }
        assertEquals("$label: classes named $ITEM_DELEGATE", 1, delegates.size)
        val focusing = first.filter { c -> c.methods.any { m -> m.code().any { it.referenceText() == REQUEST_FOCUS } } }.map { it.type }
        // 452.1's delegate holds Strings, Lists and Maps too, and their callers are a quarter of the app.
        // 57.79's holds the UserSession, a Media and the viewer's config as well, and copying their
        // callers runs the test heap out. The swipe's manager, listener and composer are Redex's LX/ types.
        val near = delegates.single().fields.map { it.type }.filter { it.startsWith("LX/") }.toSet() + focusing
        val have = first.mapTo(HashSet()) { it.type }
        val second = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type !in have && (classDef.type in near || classDef.methods.any { m -> m.code().any { it.method()?.definingClass?.let(near::contains) == true } })) {
                    second += ImmutableClassDef.of(classDef)
                }
            }
        }
        val classes = first + second
        val context = PatchContexts.of(classes)

        val sites = context.findSwipeReply()
        assertEquals("$label: the delegate", delegates.single().type, sites.delegate)
        val listenerType = classes.single { it.type == sites.listenerManager.definingClass }.methods
            .single { it.name == sites.listenerManager.name && it.parameterTypes.isEmpty() }.code()
            .mapNotNull { it.method() }.single { it.name == sites.listenerManager.name }.definingClass
        assertTrue("$label: the composer is a listener", listenerType in classes.single { it.type == sites.composer }.interfaces)
        val stock = delegates.single().methods.single { it.name == sites.gesture && it.parameterTypes.map(CharSequence::toString) == GESTURE_PARAMETERS }.code()
        assertEquals("$label: the reply sheet's source", Opcode.SGET_OBJECT, stock[sites.sheet].opcode)
        val read = (stock[sites.listeners] as ReferenceInstruction).reference as FieldReference
        assertEquals("$label: the listener block reads the listener manager", sites.listenerManager.definingClass, read.type)

        // What scripts/injected-mutation-contracts.txt picks each guarded method by is this one method's alone.
        val instance = { m: Method -> !AccessFlags.STATIC.isSet(m.accessFlags) }
        val shaped = first.flatMap { c ->
            c.methods.filter { m ->
                instance(m) && m.parameterTypes.map(CharSequence::toString) == GESTURE_PARAMETERS && m.returnType == "Z" &&
                    m.code().any { it.string() == LISTENER_MANAGER } && m.code().any { it.string() == "camera_entry_point" }
            }
        }
        assertEquals("$label: the gesture rule's method", listOf("${sites.delegate}->${sites.gesture}"), shaped.map { "${it.definingClass}->${it.name}" })
        // The composer rule's markers and EditText read are its class's, the call is the method's.
        val focusers = first.filter { c ->
            COMPOSER_MARKERS.all { marker -> c.methods.any { m -> m.code().any { it.string() == marker } } } &&
                c.methods.any { m ->
                    m.code().any { read ->
                        val field = (read as? ReferenceInstruction)?.reference as? FieldReference
                        field != null && field.definingClass.startsWith("LX/") && field.type == "Landroid/widget/EditText;"
                    }
                }
        }.flatMap { c ->
            c.methods.filter { m ->
                instance(m) && m.parameterTypes.isEmpty() && m.returnType == "Z" &&
                    m.code().any { it.opcode != Opcode.INVOKE_STATIC && it.referenceText() == REQUEST_FOCUS }
            }
        }
        assertEquals("$label: the composer rule's method", listOf("${sites.composer}->${sites.composerMethod}"), focusers.map { "${it.definingClass}->${it.name}" })

        context.hookSwipeReply(sites)

        val gesture = context.mutableClassDefBy(sites.delegate).methods.single { it.name == sites.gesture && it.parameterTypes.map(CharSequence::toString) == GESTURE_PARAMETERS }.code()
        assertEquals("$label: the delegate's guard", KEEPS_SWIPE, gesture[sites.sheet].referenceText())
        assertEquals("$label: a no goes to the listener manager", sites.listeners + 3, gesture.target(sites.sheet + 2))
        assertEquals("$label: then the sheet's source", stock[sites.sheet].referenceText(), gesture[sites.sheet + 3].referenceText())
        val focus = context.mutableClassDefBy(sites.composer).methods.single { it.name == sites.composerMethod && it.parameterTypes.isEmpty() }.code()
        assertEquals("$label: the composer's guard", KEEPS_SWIPE, focus[0].referenceText())
        assertTrue("$label: the composer focuses the box", focus.any { it.referenceText() == REQUEST_FOCUS })
        // Only a class the patch turned mutable can have changed, and it asks for these two alone.
        val hooked = listOf(sites.delegate, sites.composer).distinct().flatMap { context.mutableClassDefBy(it).methods }
            .filter { method -> method.code().any { it.referenceText() == KEEPS_SWIPE } }
        assertEquals("$label: methods hooked", 2, hooked.size)
    }

    /**
     * The swipe as 452 has it, in the parts the patch reads: a delegate Instagram names
     * [ITEM_DELEGATE] whose gesture handler opens the reply sheet or asks its listener manager,
     * the manager asking each listener, the composer focusing its text box, a listener that says
     * no, and a text box focused by something that isn't a listener. Every option breaks one thing.
     */
    private fun classes(
        delegateName: String = ITEM_DELEGATE,
        twoDelegates: Boolean = false,
        holdsName: Boolean = true,
        sheetCalls: Int = 1,
        staticSource: Boolean = true,
        returnsAfterSheet: Boolean = true,
        readsManager: Boolean = true,
        sheetIsTarget: Boolean = false,
        otherAsker: Boolean = false,
        listenerElsewhere: Boolean = false,
        listenerAsks: Int = 1,
        composers: Int = 1,
        directCall: Boolean = false,
        composerTopIsTarget: Boolean = false,
    ): List<ClassDef> {
        val public = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        // Four locals, this in v4, the two motion events in v5 and v6, the view in v7, the distances in v8 and v9, the flag in v10.
        fun gesture(owner: String) = method(
            owner, "GKg", GESTURE_PARAMETERS, "Z", 4, public,
            listOfNotNull(
                "const/4 v2, 0x1",
                "invoke-virtual {v7}, Ljava/lang/Object;->hashCode()I",
                "move-result v0",
                "if-eqz v0, :listeners",
                if (sheetIsTarget) "if-ltz v0, :sheet" else null,
                if (sheetIsTarget) ":sheet" else null,
                if (staticSource) "sget-object v1, Lfixture/Sources;->A00:Ljava/lang/Integer;" else "iget-object v1, v4, $owner->A0z:Ljava/lang/Integer;",
            ).plus(List(sheetCalls) { "invoke-virtual {v4, v1}, $owner->GZO(Ljava/lang/Integer;)V" })
                .plus(if (returnsAfterSheet) listOf("return v2") else listOf("const/4 v2, 0x0", "return v2"))
                .plus(":listeners")
                .plus(if (readsManager) "iget-object v0, v4, $owner->A0q:$manager" else "sget-object v0, Lfixture/Sources;->A01:$manager")
                .plus(
                    listOf(
                        "if-nez v0, :ask",
                        if (holdsName) "const-string v1, \"$LISTENER_MANAGER\"" else "const-string v1, \"listenerManager\"",
                        "new-instance v0, Ljava/lang/IllegalStateException;",
                        "invoke-direct {v0, v1}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V",
                        "throw v0",
                        ":ask",
                        "invoke-virtual {v0}, $manager->GKf()Z",
                        "move-result v0",
                        "return v0",
                    ),
                )
                .joinToString("\n"),
        )
        fun delegateClass(type: String) = classDef(
            type, "Ljava/lang/Object;", emptyList(), listOf(gesture(type)),
            listOf(
                ImmutableField(
                    type, "__redex_internal_original_name", "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, ImmutableStringEncodedValue(delegateName), null, null,
                ),
                ImmutableField(type, "A0q", manager, AccessFlags.PRIVATE.value, null, null, null),
            ),
        )
        // Two locals, this in v2.
        val ask = method(
            manager, "GKf", emptyList(), "Z", 2, public,
            listOf(
                "iget-object v0, v2, $manager->A00:Ljava/util/List;",
                "invoke-interface {v0}, Ljava/util/List;->iterator()Ljava/util/Iterator;",
                "move-result-object v0",
                ":loop",
                "invoke-interface {v0}, Ljava/util/Iterator;->hasNext()Z",
                "move-result v1",
                "if-eqz v1, :done",
                "invoke-interface {v0}, Ljava/util/Iterator;->next()Ljava/lang/Object;",
                "move-result-object v1",
                "check-cast v1, $listener",
            ).plus(List(listenerAsks) { "invoke-interface {v1}, $listener->GKf()Z\nmove-result v1" })
                .plus(listOf("if-eqz v1, :loop", "const/4 v0, 0x1", "return v0", ":done", "const/4 v0, 0x0", "return v0"))
                .joinToString("\n"),
        )
        // Three locals, this in v3.
        fun focus(owner: String) = method(
            owner, "GKf", emptyList(), "Z", 3, public,
            listOfNotNull(
                if (composerTopIsTarget) ":top" else null,
                "iget-object v0, v3, $owner->A02:Landroid/widget/EditText;",
                "if-eqz v0, :no",
                "invoke-virtual {v0}, Landroid/view/View;->requestFocus()Z",
                "const/4 v1, 0x0",
                "invoke-static {v0, v1}, Lfixture/Keyboard;->show(Landroid/view/View;I)V",
                "const/4 v0, 0x1",
                "return v0",
                ":no",
                if (composerTopIsTarget) "if-nez v3, :top" else null,
                "const/4 v2, 0x0",
                "return v2",
            ).joinToString("\n"),
        )
        val no = method(listener, "GKf", emptyList(), "Z", 1, public, "const/4 v0, 0x0\nreturn v0")
        val all = mutableListOf(
            delegateClass(delegate),
            classDef(manager, "Ljava/lang/Object;", listOf(listener), listOf(ask)),
            classDef(
                listener, "Ljava/lang/Object;", emptyList(),
                listOf(ImmutableMethod(listener, "GKf", emptyList(), "Z", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)),
                flags = AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
            ),
            classDef("Lfixture/Quiet;", "Ljava/lang/Object;", listOf(listener), listOf(renamed(no, "Lfixture/Quiet;"))),
            classDef("Lfixture/Field;", "Ljava/lang/Object;", emptyList(), listOf(focus("Lfixture/Field;"))),
        )
        if (composers == 0) all += classDef(composer, "Ljava/lang/Object;", listOf(listener), listOf(renamed(no, composer)))
        // The story composer's class loads the markers elsewhere in it.
        fun marked(owner: String) = method(
            owner, "A00", emptyList(), "V", 1, public,
            COMPOSER_MARKERS.joinToString("\n") { "const-string v0, \"$it\"" } + "\nreturn-void",
        )
        repeat(composers) { at ->
            val type = if (at == 0) composer else "Lfixture/Composer$at;"
            all += classDef(type, "Ljava/lang/Object;", listOf(listener), listOf(focus(type), marked(type)))
        }
        // 453's Live comment box listens too and focuses its own box, loading neither marker.
        all += classDef("Lfixture/LiveComments;", "Ljava/lang/Object;", listOf(listener), listOf(focus("Lfixture/LiveComments;")))
        if (twoDelegates) all += delegateClass("Lfixture/ItemDelegate2;")
        if (otherAsker) all += caller("Lfixture/Elsewhere;", "$manager->GKf()Z")
        if (listenerElsewhere) all += caller("Lfixture/Dispatcher;", "$listener->GKf()Z", "invoke-interface")
        if (directCall) all += caller("Lfixture/Tapper;", "$composer->GKf()Z")
        return all
    }

    private fun caller(type: String, calls: String, invoke: String = "invoke-virtual") = classDef(
        type, "Ljava/lang/Object;", emptyList(),
        listOf(method(type, "tap", listOf("Ljava/lang/Object;"), "V", 1, AccessFlags.PUBLIC.value, "$invoke {v2}, $calls\nreturn-void")),
    )

    private fun renamed(method: Method, owner: String): Method = ImmutableMethod(
        owner, method.name, method.parameters, method.returnType, method.accessFlags, null, null, method.implementation,
    )

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + 1 + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(
        type: String,
        superclass: String,
        interfaces: List<String>,
        methods: List<Method>,
        fields: List<ImmutableField> = emptyList(),
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ): ClassDef = ImmutableClassDef(type, flags, superclass, interfaces, null, null, fields, methods)

    /** The index of the instruction the branch at [at] jumps to. */
    private fun List<Instruction>.target(at: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        return address.indexOf(address[at] + (this[at] as OffsetInstruction).codeOffset)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}
