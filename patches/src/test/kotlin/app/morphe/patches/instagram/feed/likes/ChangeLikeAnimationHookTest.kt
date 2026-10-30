/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.likes

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The double-tap heart's set-up for a post hands Instagram's animation to LikeAnimation.pick first
 * and its check before playing one of Instagram's own to LikeAnimation.allow, as an int, and the
 * extension's stub answers the plain heart's value. Since 452 the hearts of feed posts and reels are
 * drawn without that view: the post's animation goes through pick where it's read off the post and
 * each heart's where it's kept, and every check after a comparison with the plain heart through
 * allow. The settings list's preview (#299) gets the factory a heart is built with and the call the
 * view plays it with, or neither stub is filled when the extension couldn't make the call.
 */
class ChangeLikeAnimationHookTest {
    @Test
    fun theSetUpTakesThePickedAnimationAndItsCheckIsOpened() {
        val context = PatchContexts.of(listOf(animation(), view(), extension()) + hearts())

        val anchors = context.findLikeAnimation()
        assertEquals(ANIMATION, anchors.animation)
        assertEquals("NONE", anchors.none.name)
        assertEquals("the check's answer", 4, anchors.gate)
        context.applyLikeAnimation(anchors)

        val code = context.configure()
        assertEquals(
            listOf(PICK_LIKE_ANIMATION, "move-result-object", "check-cast", "if-eqz", "sget-object", "if-eq", GATE,
                "move-result", ALLOW_LIKE_ANIMATION, "move-result", "if-eqz", SET_UP, "const/4", "return-void", OTHER, "goto"),
            code.map(::shape),
        )
        assertEquals("pick reads the animation", 4, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals("and writes it back", 4, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(ANIMATION, (code[2] as ReferenceInstruction).reference.toString())
        assertEquals("allow reads the check's answer", 0, (code[8] as FiveRegisterInstruction).registerC)
        assertEquals("and answers into it", 0, (code[9] as OneRegisterInstruction).registerA)
        assertEquals("the test reads that", 0, (code[10] as OneRegisterInstruction).registerA)
        assertTrue("once each", code.count { it.calls(PICK_LIKE_ANIMATION) } == 1 && code.count { it.calls(ALLOW_LIKE_ANIMATION) } == 1)

        assertEquals(listOf("sget-object", "return-object"), context.stub(NO_ANIMATION_STUB).take(2).map { it.opcode.name })
        assertEquals("$ANIMATION->NONE:$ANIMATION", (context.stub(NO_ANIMATION_STUB)[0] as ReferenceInstruction).reference.toString())
        assertTrue("the type is read off that value, not written in", context.stub("animationType").none { it.opcode == Opcode.CONST_CLASS })
    }

    @Test
    fun aViewOrAnimationThatIsntTheOneFailsThePatch() {
        refuses("no view", listOf(animation(), extension()))
        refuses("no set-up", listOf(animation(), view(setUp = "setUpSomethingElse"), extension()))
        refuses("an animation that isn't an enum", listOf(animation(superclass = "Ljava/lang/Object;"), view(), extension()))
        refuses("an animation that isn't public", listOf(animation(flags = AccessFlags.FINAL.value or AccessFlags.ENUM.value), view(), extension()))
        refuses("an animation that isn't in the app", listOf(view(), extension()))
        refuses("two set-ups for a post", listOf(animation(), view(twice = true), extension()))
        refuses("no plain heart compared", listOf(animation(), view(compare = ""), extension()))
        refuses("a plain heart of another type", listOf(animation(), view(compare = OTHER_NONE), extension()))
        refuses("two checks", listOf(animation(), view(check = "$CHECK\n$CHECK"), extension()))
        refuses("no check", listOf(animation(), view(check = ""), extension()))
        refuses("no extension", listOf(animation(), view()))
    }

    /**
     * #299: the feed's heart takes the picked animation where the post's is read, the component where
     * it keeps its own (the call standing where the return and the store were, so the branch onto
     * the return runs it), and every check after a comparison with the plain heart answers through
     * allow, while the branch from right after the first check's answer still skips the second's.
     */
    @Test
    fun theHeartsDrawnWithoutTheViewTakeThePickedAnimationToo() {
        val context = PatchContexts.of(listOf(animation(), view(), extension()) + hearts())
        val anchors = context.findLikeAnimation()
        assertEquals("the component's one store", 1, anchors.kept.size)
        assertEquals("the component's check and the spring's two", 3, anchors.checks.size)
        context.applyLikeAnimation(anchors)

        val source = context.code(HEARTS, "of")
        assertEquals(listOf("const/4", "if-eqz", "sget-object", PICK_LIKE_ANIMATION, "move-result-object", "check-cast", "return-object"),
            source.map(::shape))
        assertEquals("the branch onto the return lands on pick", 3, (source[1] as BuilderOffsetInstruction).target.location.index)
        assertEquals(ANIMATION, (source[5] as ReferenceInstruction).reference.toString())

        val kept = context.code(COMPONENT, "<init>")
        assertEquals(listOf("invoke-direct", PICK_LIKE_ANIMATION, "move-result-object", "check-cast", "iput-object", "return-void"),
            kept.map(::shape))
        assertEquals("pick reads what was kept", 2, (kept[1] as RegisterRangeInstruction).startRegister)
        assertEquals("and it's kept in the same field", HEART_FIELD, (kept[4] as ReferenceInstruction).reference.toString())

        assertEquals(listOf("iget-object", "if-eqz", "sget-object", "if-eq", GATE, "move-result", ALLOW_LIKE_ANIMATION, "move-result",
            "if-eqz", "return-void"), context.code(COMPONENT, "render").map(::shape))
        assertEquals("the lambda is left alone", listOf(Opcode.IGET_OBJECT, Opcode.CONST_4, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC,
            Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), context.code(LAMBDA, "invoke").map { it.opcode })

        val spring = context.code(PLAIN, "check")
        assertEquals(listOf("if-eqz", "sget-object", "if-eq", "if-eqz", GATE, "move-result", ALLOW_LIKE_ANIMATION, "move-result", "goto",
            "sget-object", "if-eq", OTHER, "move-result", ALLOW_LIKE_ANIMATION, "move-result", "if-eqz", "const/4", "return", "const/4",
            "return"), spring.map(::shape))
        assertEquals("the goto still lands on the test", 15, (spring[8] as BuilderOffsetInstruction).target.location.index)
    }

    @Test
    fun heartsThatArentTheOnesFailThePatch() {
        refuses("two post animation sources", listOf(animation(), view(), extension()) + hearts(twoSources = true))
        refuses("a factory handed something it didn't just read", listOf(animation(), view(), extension()) + hearts(readFirst = false))
        refuses("no heart but the view's", listOf(animation(), view(), extension()) + hearts(lambda = false))
        refuses("a heart that never keeps its animation", listOf(animation(), view(), extension()) + hearts(keeps = false))
        refuses("no check after the plain heart", listOf(animation(), view(), extension()) + hearts(none = OTHER_NONE))
        refuses("a stray branch right after a check", listOf(animation(), view(), extension()) + hearts(stray = true))
    }

    /** #299: the list's preview builds a heart the way a post's is built and plays it the way the view does. */
    @Test
    fun theListCanShowAndPlayEachHeart() {
        val context = PatchContexts.of(listOf(animation(), view(plays = true), drawable(), extension()) + hearts())
        val anchors = context.findLikeAnimation()
        assertEquals(FACTORY, anchors.factory)
        assertEquals("the call with no arguments, not the scale", PLAY, anchors.play)
        context.applyLikeAnimation(anchors)

        val heart = context.stub(HEART_STUB)
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            heart.take(4).map { it.opcode })
        assertEquals(ANIMATION, (heart[0] as ReferenceInstruction).reference.toString())
        assertEquals(FACTORY, (heart[1] as ReferenceInstruction).reference.toString())
        val registers = context.mutableClassDefBy(LIKE_ANIMATION).methods.single { it.name == HEART_STUB }.implementation!!.registerCount
        assertEquals("the context and the animation", listOf(registers - 2, registers - 1),
            (heart[1] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        val play = context.stub(PLAY_STUB)
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), play.take(3).map { it.opcode })
        assertEquals(DRAWABLE, (play[0] as ReferenceInstruction).reference.toString())
        assertEquals(PLAY, (play[1] as ReferenceInstruction).reference.toString())
    }

    /** Without what it needs the preview is left out, and the rest of the patch still goes in. */
    @Test
    fun aPreviewTheExtensionCantMakeIsLeftOut() {
        fun filled(classes: List<ClassDef>): Pair<Boolean, Boolean> {
            val context = PatchContexts.of(classes)
            context.applyLikeAnimation(context.findLikeAnimation())
            return context.stub(HEART_STUB).any { it.opcode == Opcode.INVOKE_STATIC } to
                context.stub(PLAY_STUB).any { it.opcode == Opcode.INVOKE_VIRTUAL }
        }
        val base = listOf(animation(), extension())
        assertEquals("a view that never plays its heart", true to false, filled(base + view() + drawable() + hearts()))
        assertEquals("a play that isn't public", true to false,
            filled(base + view(plays = true) + drawable(startFlags = AccessFlags.FINAL.value) + hearts()))
        assertEquals("a drawable that isn't public", true to false,
            filled(base + view(plays = true) + drawable(flags = AccessFlags.ABSTRACT.value) + hearts()))
        assertEquals("no drawable to look the play up in", true to false, filled(base + view(plays = true) + hearts()))
        assertEquals("two ways to play it", true to false, filled(base + view(plays = true, twoPlays = true) + drawable() + hearts()))
        assertEquals("a factory that isn't public", false to false,
            filled(base + view(plays = true) + drawable() + hearts(factoryFlags = AccessFlags.STATIC.value)))
    }

    /** A branch landing on the check's test would skip allow, so the patch fails before changing anything. */
    @Test
    fun aBranchOntoTheCheckTestFailsThePatchUnchanged() {
        val context = PatchContexts.of(listOf(animation(), view(entry = "if-nez p2, :test"), extension()) + hearts())
        val anchors = context.findLikeAnimation()
        val before = context.configure().map(::shape)
        val failure = assertThrows(PatchException::class.java) { context.applyLikeAnimation(anchors) }
        assertTrue(failure.message, failure.message!!.contains("lands right after its check"))
        assertEquals(before, context.configure().map(::shape))
    }

    /**
     * On every declared build's fixture: the view's one set-up for a post takes the session and the
     * animation enum, compares it with the plain heart's value, checks the session once, and gets
     * both hooks and the stub.
     */
    @Test
    fun eachDeclaredBuildPlaysThePickedAnimation() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildPlaysThePickedAnimationToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val view = FixtureDex.classes(bundle, setOf(LIKE_ACTION_VIEW)).values.single()
        val setUp = view.methods.single { it.name == SET_UP_CUSTOM_LIKES }
        val animation = setUp.parameterTypes.single().toString()
        // #299: the heart the factory builds, and the class above it, which declares the call that plays it.
        val heart = setUp.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .first { it.parameterTypes.map(Any::toString) == listOf(CONTEXT, animation) }.returnType
        val kept = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) if (classDef.mentions(animation) || classDef.type == heart) kept += ImmutableClassDef.of(classDef)
        }
        val drawable = kept.single { it.type == heart }
        kept += FixtureDex.classes(bundle, setOfNotNull(drawable.superclass)).values
        val context = PatchContexts.of(kept.distinctBy { it.type } + extension())

        val anchors = context.findLikeAnimation()
        assertEquals(label, animation, anchors.animation)
        assertEquals("$label: the plain heart is the animation's own", animation, anchors.none.definingClass)
        context.applyLikeAnimation(anchors)

        val code = context.mutableClassDefBy(LIKE_ACTION_VIEW).methods.single {
            it.name == anchors.configure.name && it.parameterTypes.map(Any::toString) == anchors.configure.parameterTypes.map(Any::toString)
        }.implementation!!.instructions.toList()
        assertTrue("$label: pick first", code[0].calls(PICK_LIKE_ANIMATION))
        assertEquals(label, Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals(label, Opcode.CHECK_CAST, code[2].opcode)
        val register = (code[0] as RegisterRangeInstruction).startRegister
        assertEquals("$label: the animation's register", anchors.configure.implementation!!.registerCount - 1, register)
        assertEquals(label, register, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(label, register, (code[2] as OneRegisterInstruction).registerA)
        val allow = code.indexOfFirst { it.calls(ALLOW_LIKE_ANIMATION) }
        assertEquals("$label: allow right after the check's answer", Opcode.MOVE_RESULT, code[allow - 1].opcode)
        assertTrue("$label: and the check before that", (code[allow - 2] as ReferenceInstruction).reference.toString().endsWith("(Lcom/instagram/common/session/UserSession;)Z"))
        assertEquals(label, Opcode.MOVE_RESULT, code[allow + 1].opcode)
        assertEquals("$label: the test after", Opcode.IF_EQZ, code[allow + 2].opcode)
        assertTrue("$label: the set-up after", code.drop(allow).any { it.calls("$LIKE_ACTION_VIEW->$SET_UP_CUSTOM_LIKES($animation)V") })
        assertEquals(label, 1, code.count { it.calls(PICK_LIKE_ANIMATION) })
        assertEquals(label, 1, code.count { it.calls(ALLOW_LIKE_ANIMATION) })
        assertEquals(label, anchors.none.toString(), (context.stub(NO_ANIMATION_STUB)[0] as ReferenceInstruction).reference.toString())

        // #299: the list's preview builds each heart with the post's factory and plays it with the view's call.
        assertNotNull("$label: a factory the preview can call", anchors.factory)
        assertEquals(label, heart, anchors.factory!!.substringAfterLast(')'))
        assertEquals(label, listOf(Opcode.CHECK_CAST, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
            context.stub(HEART_STUB).take(4).map { it.opcode })
        assertEquals(label, anchors.factory, (context.stub(HEART_STUB)[1] as ReferenceInstruction).reference.toString())
        assertNotNull("$label: a call the preview can play the heart with", anchors.play)
        assertEquals(label, listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), context.stub(PLAY_STUB).take(3).map { it.opcode })
        assertEquals(label, anchors.play, (context.stub(PLAY_STUB)[1] as ReferenceInstruction).reference.toString())

        // #299: 452 draws the feed's and the reels' hearts without the view.
        assertTrue("$label: the feed's heart and the reels' (${anchors.kept.size})", anchors.kept.size >= 2)
        assertTrue("$label: the double tap's, the hearts' and the spring's checks (${anchors.checks.size})", anchors.checks.size >= 4)
        val source = context.mutated(anchors.returned.method)
        val pick = source.indexOfFirst { it.calls(PICK_LIKE_ANIMATION) }
        assertEquals("$label: the post's animation goes through pick on its way out", listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST,
            Opcode.RETURN_OBJECT), source.subList(pick + 1, pick + 4).map { it.opcode })
        assertEquals(label, 1, source.count { it.calls(PICK_LIKE_ANIMATION) })
        for (site in anchors.kept) {
            val code = context.mutated(site.method)
            val at = code.indexOfFirst { it.calls(PICK_LIKE_ANIMATION) }
            assertEquals("$label ${site.method.definingClass}: picked as it's kept", listOf(Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST,
                Opcode.IPUT_OBJECT), code.subList(at + 1, at + 4).map { it.opcode })
            assertEquals(label, animation, ((code[at + 3] as ReferenceInstruction).reference as FieldReference).type)
        }
        for (sites in anchors.checks.groupBy { it.method.definingClass + it.method.name }.values) {
            val code = context.mutated(sites.first().method)
            val allows = code.indices.filter { code[it].calls(ALLOW_LIKE_ANIMATION) }
            assertEquals("$label ${sites.first().method.definingClass}: one allow per check", sites.size, allows.size)
            for (allow in allows) {
                assertEquals(label, Opcode.MOVE_RESULT, code[allow - 1].opcode)
                assertTrue("$label: right after a check of the session",
                    (code[allow - 2] as ReferenceInstruction).reference.toString().endsWith("(Lcom/instagram/common/session/UserSession;)Z"))
                assertEquals(label, Opcode.MOVE_RESULT, code[allow + 1].opcode)
            }
        }
    }

    private fun BytecodePatchContext.mutated(method: Method): List<Instruction> =
        mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
                it.returnType == method.returnType
        }.implementation!!.instructions.toList()

    /** Whether the class is [type], or has a field, a signature or an instruction that names it. */
    private fun ClassDef.mentions(type: String): Boolean =
        this.type == type || fields.any { it.type == type } || methods.any { method ->
            method.returnType == type || method.parameterTypes.any { it.toString() == type } ||
                method.implementation?.instructions?.any { instruction ->
                    when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                        is FieldReference -> reference.definingClass == type || reference.type == type
                        is MethodReference -> reference.definingClass == type || reference.returnType == type ||
                            reference.parameterTypes.any { it.toString() == type }
                        else -> false
                    }
                } == true
        }

    private fun refuses(case: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(if (classes.any { it.type == HEARTS }) classes else classes + hearts())
        assertThrows(case, PatchException::class.java) { context.findLikeAnimation() }
    }

    private fun BytecodePatchContext.code(type: String, name: String): List<Instruction> =
        mutableClassDefBy(type).methods.single { it.name == name }.implementation!!.instructions.toList()

    private fun shape(instruction: Instruction): String = when {
        instruction.calls(PICK_LIKE_ANIMATION) -> PICK_LIKE_ANIMATION
        instruction.calls(ALLOW_LIKE_ANIMATION) -> ALLOW_LIKE_ANIMATION
        instruction.calls(GATE_REFERENCE) -> GATE
        instruction.calls(OTHER_REFERENCE) -> OTHER
        instruction.calls("$LIKE_ACTION_VIEW->$SET_UP_CUSTOM_LIKES($ANIMATION)V") -> SET_UP
        else -> instruction.opcode.name
    }

    private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun BytecodePatchContext.configure(): List<Instruction> =
        mutableClassDefBy(LIKE_ACTION_VIEW).methods.single { it.name == "set" }.implementation!!.instructions.toList()

    private fun BytecodePatchContext.stub(name: String): List<Instruction> =
        mutableClassDefBy(LIKE_ANIMATION).methods.single { it.name == name }.implementation!!.instructions.toList()

    private companion object {
        const val ANIMATION = "Lfixture/LikeAnimationKind;"
        const val OTHER_ANIMATION = "Lfixture/OtherKind;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        const val GATE_REFERENCE = "Lfixture/Gate;->on($SESSION)Z"
        const val OTHER_REFERENCE = "Lfixture/Gate;->plain($SESSION)Z"
        const val GATE = "GATE"
        const val OTHER = "OTHER"
        const val SET_UP = "SET_UP"
        const val CHECK = "invoke-static { p1 }, $GATE_REFERENCE\nmove-result v0"
        const val OTHER_NONE = "$OTHER_ANIMATION->NONE:$OTHER_ANIMATION"
        const val CONTEXT = "Landroid/content/Context;"
        const val HEARTS = "Lfixture/Hearts;"
        const val COMPONENT = "Lfixture/HeartComponent;"
        const val LAMBDA = "Lfixture/HeartLambda;"
        const val PLAIN = "Lfixture/PlainHeart;"
        const val DRAWABLE = "Lfixture/HeartDrawable;"
        const val FACTORY = "$HEARTS->make($CONTEXT$ANIMATION)$DRAWABLE"
        const val HEART_FIELD = "$COMPONENT->animation:$ANIMATION"
        const val VIEW_HEART = "$LIKE_ACTION_VIEW->heart:$DRAWABLE"
        const val PLAY = "$DRAWABLE->start()V"
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        fun extension(): ClassDef = ExtensionDex.classDef(LIKE_ANIMATION)

        /** The heart's drawable, with the call that plays it and one that takes an argument. */
        fun drawable(
            flags: Int = AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value,
            startFlags: Int = PUBLIC_FINAL,
        ): ClassDef = ImmutableClassDef(DRAWABLE, flags, "Landroid/graphics/drawable/Drawable;", null, null, null, null, listOf(
            define(DRAWABLE, "start", emptyList(), "V", 1, startFlags, "return-void"),
            define(DRAWABLE, "scale", listOf("F"), "V", 2, PUBLIC_FINAL, "return-void"),
        ))

        /** The animation enum: the plain heart's value and one Rings animation, as static fields of its own type. */
        fun animation(
            superclass: String = "Ljava/lang/Enum;",
            flags: Int = PUBLIC_FINAL or AccessFlags.ENUM.value,
        ): ClassDef {
            val fieldFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value
            val fields = listOf("NONE", "RINGS").map { ImmutableField(ANIMATION, it, ANIMATION, fieldFlags, null, null, null) }
            return ImmutableClassDef(ANIMATION, flags, superclass, null, null, null, fields, emptyList())
        }

        /**
         * The heart view as 450 has it: the set-up for a post (here "set") plays the plain heart when
         * the animation is missing or the plain heart's value, or when the session's check says no,
         * and hands it to the set-up for one of Instagram's own otherwise. v0 and v1 are its locals,
         * then the view, the session and the animation. With [plays] it also plays the heart it
         * keeps and scales it, and with [twoPlays] stops it another way too.
         */
        fun view(
            setUp: String = SET_UP_CUSTOM_LIKES,
            twice: Boolean = false,
            compare: String = "$ANIMATION->NONE:$ANIMATION",
            check: String = CHECK,
            entry: String = "",
            plays: Boolean = false,
            twoPlays: Boolean = false,
        ): ClassDef {
            val comparison = if (compare.isEmpty()) "" else "sget-object v0, $compare\nif-eq p2, v0, :plain"
            val body = """
                $entry
                if-eqz p2, :plain
                $comparison
                $check
                ${if (":test" in entry) ":test" else ""}
                if-eqz v0, :plain
                invoke-virtual { p0, p2 }, $LIKE_ACTION_VIEW->$setUp($ANIMATION)V
                :reset
                const/4 v1, 0x0
                return-void
                :plain
                invoke-static { p1 }, $OTHER_REFERENCE
                goto :reset
            """.lines().filter { it.isNotBlank() }.joinToString("\n")
            val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            val methods = mutableListOf(
                method(setUp, listOf(ANIMATION), 3, AccessFlags.PUBLIC.value, "const/4 v0, 0x0\ninvoke-static { v0, p1 }, $FACTORY\nreturn-void"),
                method("set", listOf(SESSION, ANIMATION), 5, instance, body),
            )
            if (twice) methods += method("setAgain", listOf(SESSION, ANIMATION), 5, instance, body)
            if (plays) {
                methods += method("tap", emptyList(), 2, instance,
                    "iget-object v0, p0, $VIEW_HEART\nif-eqz v0, :done\ninvoke-virtual { v0 }, $PLAY\n:done\nreturn-void")
                methods += method("grow", emptyList(), 3, instance,
                    "iget-object v0, p0, $VIEW_HEART\nconst/4 v1, 0x0\ninvoke-virtual { v0, v1 }, $DRAWABLE->scale(F)V\nreturn-void")
            }
            if (twoPlays) methods += method("stop", emptyList(), 2, instance,
                "iget-object v0, p0, $VIEW_HEART\ninvoke-virtual { v0 }, $DRAWABLE->stop()V\nreturn-void")
            return ImmutableClassDef(LIKE_ACTION_VIEW, AccessFlags.PUBLIC.value, "Landroid/widget/ImageView;", null, null, null, null, methods)
        }

        fun method(name: String, parameters: List<String>, registers: Int, flags: Int, body: String): Method =
            define(LIKE_ACTION_VIEW, name, parameters, "V", registers, flags, body)

        fun define(type: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method =
            MutableMethod(ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, body.lines().map(String::trim).filter(String::isNotEmpty).joinToString("\n"))
            }.let(ImmutableMethod::of)

        /**
         * The hearts as 452 draws them without the view: the factory's class with the post's
         * animation source, whose branch lands on its return; a component keeping its animation and
         * checking the session after comparing it with the plain heart; the lambda reading that
         * into the factory; and the spring's check, whose first check's answer goes straight to the
         * second's test.
         */
        fun hearts(
            twoSources: Boolean = false,
            readFirst: Boolean = true,
            lambda: Boolean = true,
            keeps: Boolean = true,
            none: String = "$ANIMATION->NONE:$ANIMATION",
            stray: Boolean = false,
            factoryFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        ): List<ClassDef> {
            val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
            val source = """
                const/4 v0, 0x0
                if-eqz p0, :done
                sget-object v0, $ANIMATION->RINGS:$ANIMATION
                :done
                return-object v0
            """
            val factoryMethods = listOf(
                define(HEARTS, "make", listOf(CONTEXT, ANIMATION), DRAWABLE, 3, factoryFlags, "const/4 v0, 0x0\nreturn-object v0"),
                define(HEARTS, "of", listOf("Ljava/lang/Object;"), ANIMATION, 2, static, source),
            ) + if (twoSources) listOf(define(HEARTS, "alsoOf", listOf("Ljava/lang/Object;"), ANIMATION, 2, static, source)) else emptyList()
            val factory = ImmutableClassDef(HEARTS, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null, null, factoryMethods)

            val render = """
                iget-object v0, p0, $HEART_FIELD
                if-eqz v0, :plain
                sget-object v1, $none
                if-eq v0, v1, :plain
                invoke-static { p1 }, $GATE_REFERENCE
                move-result v1
                if-eqz v1, :plain
                :plain
                return-void
            """
            val component = ImmutableClassDef(
                COMPONENT, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null,
                listOf(ImmutableField(COMPONENT, "animation", ANIMATION, PUBLIC_FINAL, null, null, null)),
                listOf(
                    define(COMPONENT, "<init>", listOf(SESSION, ANIMATION), "V", 3, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
                        "invoke-direct { p0 }, Ljava/lang/Object;-><init>()V\n${if (keeps) "iput-object p2, p0, $HEART_FIELD" else ""}\nreturn-void"),
                    define(COMPONENT, "render", listOf(SESSION), "V", 4, PUBLIC_FINAL, render),
                ),
            )

            val read = "iget-object v0, v0, $HEART_FIELD"
            val invoke = """
                iget-object v0, p0, $LAMBDA->component:$COMPONENT
                ${if (readFirst) "const/4 v1, 0x0\n$read" else "$read\nconst/4 v1, 0x0"}
                invoke-static { v1, v0 }, $FACTORY
                move-result-object v0
                return-object v0
            """
            val heartLambda = ImmutableClassDef(
                LAMBDA, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null,
                listOf(ImmutableField(LAMBDA, "component", COMPONENT, PUBLIC_FINAL, null, null, null)),
                listOf(define(LAMBDA, "invoke", emptyList(), "Ljava/lang/Object;", 3, PUBLIC_FINAL, invoke)),
            )

            val check = """
                ${if (stray) "if-nez p2, :test" else ""}
                if-eqz p1, :yes
                sget-object v0, $none
                if-eq p1, v0, :yes
                if-eqz p2, :second
                invoke-static { p0 }, $GATE_REFERENCE
                move-result v0
                goto :test
                :second
                sget-object v0, $none
                if-eq p1, v0, :yes
                invoke-static { p0 }, $OTHER_REFERENCE
                move-result v0
                :test
                if-eqz v0, :yes
                const/4 v0, 0x0
                return v0
                :yes
                const/4 v0, 0x1
                return v0
            """
            val plain = ImmutableClassDef(PLAIN, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null, null,
                listOf(define(PLAIN, "check", listOf(SESSION, ANIMATION, "Ljava/lang/Integer;"), "Z", 4, static, check)))
            return listOfNotNull(factory, component, heartLambda.takeIf { lambda }, plain)
        }
    }
}
