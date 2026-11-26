/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Photo and carousel reels in the reels picture in picture window (#873): in every fixture build,
 * the window's check hands its video answer and the reel's model to the extension right after it
 * reads the answer, compares the extension's answer as it compared its own, and is otherwise as it
 * came. The extension's media_type read is written from the calls Instagram's own video question
 * reads it with.
 */
class ReelsPipStillsTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(REELS_PIP_STILLS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$REELS_PIP_STILLS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("ReelsPipStills declares no $PIP_STILLS_WINDOW: $declared", PIP_STILLS_WINDOW in declared)
        assertTrue(
            "ReelsPipStills declares no $PIP_STILLS_TYPE stub: $declared",
            "$REELS_PIP_STILLS->$PIP_STILLS_TYPE(Ljava/lang/Object;)Ljava/lang/Integer;" in declared,
        )
        for (hook in listOf(PIP_STILLS_BINDING, PIP_STILLS_BOUND, PIP_STILLS_PAGE, PIP_STILLS_SKIP)) {
            assertTrue("ReelsPipStills declares no $hook: $declared", hook in declared)
        }
        assertTrue(
            "ReelsPipStills declares no $PIP_STILLS_MEDIA stub: $declared",
            "$REELS_PIP_STILLS->$PIP_STILLS_MEDIA(Ljava/lang/Object;)Ljava/lang/Object;" in declared,
        )
    }

    /** The hashes the receiver is found by are those of the actions the extension tells apart. */
    @Test
    fun theReceiversHashesAreTheActions() {
        assertEquals(0x7e9ee785, PIP_NEXT_HASH)
        assertEquals(0x1d6b7689, PIP_PREVIOUS_HASH)
    }

    /**
     * In every build, the window's bind hands the extension its arguments, then its layout and reel
     * right where it asks a second time whether it has a reel, goes on with the reel the extension
     * answers, cast back to the reel's class, and reads the page the extension answers. The rest is
     * stock, and the extension's Media read is the reel's one Media field.
     */
    @Test
    fun eachBuildHandsTheBindItsReel() {
        var checked = 0
        for (bundle in bundles() + Fixtures.otherBuilds()) {
            val context = PatchContexts.of(FixtureDex.withStringPools(bundle, bindSlice(bundle)) + extension())
            val sites = context.findReelsPipBind("test")
            val label = bundle.name
            val stock = sites.bind.implementation!!.instructions.toList()
            assertTrue("$label: the bind loads the tag", stock.any { it.string() == PIP_BIND_TAG })
            assertEquals("$label: the site asks for the reel", Opcode.IF_EQZ, stock[sites.site].opcode)
            assertEquals("$label: that reel", sites.reel, (stock[sites.site] as OneRegisterInstruction).registerA)
            val source = stock[sites.source] as FiveRegisterInstruction
            assertEquals("$label: the source call reads the reel", sites.reel, source.registerC)
            assertEquals("$label: and the page", sites.index, source.registerD)
            assertTrue("$label: the source comes after the site", sites.source > sites.site)
            assertEquals("$label: the reel holds Media", "Lcom/instagram/feed/media/Media;", sites.media.type)
            assertEquals("$label: the Media is the reel's", sites.reelType, sites.media.definingClass)

            context.addReelsPipBind("test", sites)

            val code = context.mutable(sites.bind).implementation!!.instructions.toList()
            val at = sites.site
            assertEquals("$label: the bind's arguments go first", PIP_STILLS_BINDING, code[at].reference())
            val arguments = code[at] as RegisterRangeInstruction
            assertEquals("$label: this and both", 3, arguments.registerCount)
            assertEquals("$label: from this", sites.bind.implementation!!.registerCount - 3, arguments.startRegister)
            assertEquals("$label: then the layout and the reel", PIP_STILLS_BOUND, code[at + 1].reference())
            assertEquals("$label: those registers", listOf(sites.layout, sites.reel), (code[at + 1] as FiveRegisterInstruction).let {
                listOf(it.registerC, it.registerD)
            })
            assertEquals("$label: whose answer is the reel", Opcode.MOVE_RESULT_OBJECT, code[at + 2].opcode)
            assertEquals("$label: in its register", sites.reel, (code[at + 2] as OneRegisterInstruction).registerA)
            assertEquals("$label: cast back", Opcode.CHECK_CAST, code[at + 3].opcode)
            assertEquals("$label: to the reel's class", sites.reelType, code[at + 3].reference())
            assertEquals("$label: then the page", PIP_STILLS_PAGE, code[at + 4].reference())
            assertEquals("$label: of the reel", sites.reel, (code[at + 4] as FiveRegisterInstruction).registerC)
            assertEquals("$label: into the page's register", sites.index, (code[at + 5] as OneRegisterInstruction).registerA)
            assertEquals("$label: before Instagram asks", Opcode.IF_EQZ, code[at + 6].opcode)
            assertEquals("$label: the rest is stock", stock.kinds(), (code.take(at) + code.drop(at + 6)).kinds())

            val read = context.mutableClassDefBy(REELS_PIP_STILLS).methods.single { it.name == PIP_STILLS_MEDIA }
                .implementation!!.instructions.toList()
            assertEquals("$label: the Media read", listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), read.map { it.opcode })
            assertEquals("$label: of the reel's field", sites.media.toString(), read[1].reference())
            checked++
        }
        assertEquals("every build is read", Fixtures.BUILDS, checked)
    }

    /**
     * In every build, the receiver of the window's Previous and Next asks the extension first, with
     * the intent, and returns when it answers yes, or goes on to its own first instruction.
     */
    @Test
    fun eachBuildTurnsPagesFirst() {
        var checked = 0
        for (bundle in bundles() + Fixtures.otherBuilds()) {
            val context = PatchContexts.of(FixtureDex.withStringPools(bundle, classesSwitchingOnNext(bundle)) + extension())
            val receiver = context.findReelsPipTurns("test")
            val label = bundle.name
            val stock = receiver.implementation!!.instructions.toList()
            assertEquals("$label: the second parameter is the intent", "Landroid/content/Intent;", receiver.parameterTypes[1].toString())

            context.addReelsPipTurns("test", receiver)

            val code = context.mutable(receiver).implementation!!.instructions.toList()
            val intent = receiver.implementation!!.registerCount - 4 + 2
            assertEquals("$label: the extension goes first", PIP_STILLS_SKIP, code[0].reference())
            assertEquals("$label: with the intent", intent, (code[0] as FiveRegisterInstruction).registerC)
            assertEquals("$label: its answer", Opcode.MOVE_RESULT, code[1].opcode)
            assertEquals("$label: is asked", Opcode.IF_EQZ, code[2].opcode)
            assertEquals("$label: a no goes on to Instagram's", 4, (code[2] as BuilderOffsetInstruction).target.location.index)
            assertEquals("$label: a yes returns", Opcode.RETURN_VOID, code[3].opcode)
            assertEquals("$label: the rest is stock", stock.kinds(), code.drop(4).kinds())
            checked++
        }
        assertEquals("every build is read", Fixtures.BUILDS, checked)
    }

    @Test
    fun eachBuildHandsTheExtensionTheVideoAnswer() {
        var checked = 0
        for (bundle in bundles() + Fixtures.otherBuilds()) {
            val context = PatchContexts.of(FixtureDex.withStringPools(bundle, slice(bundle)) + extension())
            val builder = context.findReelsPipButtons("test").builder
            val sites = context.findReelsPipStills("test", builder)
            val label = bundle.name
            val stock = sites.gate.implementation!!.instructions.toList()
            val predicate = stock[sites.answer - 4] as FiveRegisterInstruction
            assertEquals("$label: the model is the predicate's subject", predicate.registerC, sites.model)
            assertEquals("$label: the answer is a Boolean's", "Ljava/lang/Boolean;->booleanValue()Z", stock[sites.answer - 1].reference())
            val answer = (stock[sites.answer] as OneRegisterInstruction).registerA
            val compare = stock[sites.answer + 1] as TwoRegisterInstruction
            assertEquals("$label: the check compares the answer", Opcode.IF_NE, stock[sites.answer + 1].opcode)
            assertEquals("$label: that register", answer, compare.registerA)
            assertEquals("$label: with the register holding 1", sites.yes, compare.registerB)
            assertTrue(
                "$label: the builder asks this check",
                builder.implementation!!.instructions.any { it.reference() == sites.gate.reference() },
            )

            context.addReelsPipStills("test", sites)

            val code = context.mutable(sites.gate).implementation!!.instructions.toList()
            val hook = sites.answer + 1
            assertEquals("$label: the answer goes to the extension", PIP_STILLS_WINDOW, code[hook].reference())
            assertEquals("$label: with the model", listOf(answer, sites.model), (code[hook] as FiveRegisterInstruction).let {
                listOf(it.registerC, it.registerD)
            })
            assertEquals("$label: and only them", 2, (code[hook] as FiveRegisterInstruction).registerCount)
            assertEquals("$label: its answer replaces Instagram's", Opcode.MOVE_RESULT, code[hook + 1].opcode)
            assertEquals("$label: in the same register", answer, (code[hook + 1] as OneRegisterInstruction).registerA)
            assertEquals("$label: and the check compares it", Opcode.IF_NE, code[hook + 2].opcode)
            assertEquals(
                "$label: as before",
                listOf(answer, sites.yes),
                (code[hook + 2] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) },
            )
            assertEquals("$label: the rest is stock", stock.kinds(), (code.take(hook) + code.drop(hook + 2)).kinds())

            val read = context.mutableClassDefBy(REELS_PIP_STILLS).methods.single { it.name == PIP_STILLS_TYPE }
                .implementation!!.instructions.toList()
            assertEquals(
                "$label: the media_type read takes Instagram's calls in order",
                listOf(sites.child, sites.wrap, sites.grandchild, sites.type).map { it.toString() },
                read.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.map { it.toString() },
            )
            assertEquals(
                "$label: with Instagram's keys",
                listOf(sites.childKey, sites.mediaKey, "media_type".hashCode()),
                read.filter { it.opcode == Opcode.CONST }.map { (it as NarrowLiteralInstruction).narrowLiteral },
            )
            assertTrue("$label: through the wrapper's tree", read.any { it.reference() == sites.tree.toString() })
            assertEquals("$label: the type is the model's media's", "Ljava/lang/Integer;", sites.type.returnType)
            checked++
        }
        assertEquals("every build is read", Fixtures.BUILDS, checked)
    }

    /**
     * The ClipsViewerPipUtil classes, the classes its methods make (the check's predicates among
     * them) and the classes those predicates call, where the video question is.
     */
    private fun slice(bundle: File): List<ClassDef> {
        val util = FixtureDex.classesLoading(bundle, PIP_UTIL_TAG)
        val have = util.mapTo(HashSet()) { it.type }
        val made = util.flatMap { it.methods }.flatMap { it.implementation?.instructions?.toList() ?: emptyList() }
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .mapNotNull { ((it as ReferenceInstruction).reference as? TypeReference)?.type }
            .filter { it !in have }.toSet()
        val lambdas = FixtureDex.classes(bundle, made).values
        have += lambdas.map { it.type }
        val asked = lambdas.flatMap { it.methods }.filter { it.name == "invoke" }
            .flatMap { it.implementation?.instructions?.toList() ?: emptyList() }
            .mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass }
            .filter { it !in have && it.startsWith("LX/") }.toSet()
        return util + lambdas + FixtureDex.classes(bundle, asked).values
    }

    /** The classes loading the bind's tag, and the reel classes whose (Integer) calls they make. */
    private fun bindSlice(bundle: File): List<ClassDef> {
        val tagged = FixtureDex.classesLoading(bundle, PIP_BIND_TAG)
        val have = tagged.mapTo(HashSet()) { it.type }
        val reels = tagged.flatMap { it.methods }.filter { it.name == "invoke" }
            .flatMap { it.implementation?.instructions?.toList() ?: emptyList() }
            .filter { it.opcode == Opcode.INVOKE_VIRTUAL }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .filter { call -> call.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Integer;") }
            .map { it.definingClass }.filter { it !in have && it.startsWith("LX/") }.toSet()
        return tagged + FixtureDex.classes(bundle, reels).values
    }

    /** The classes loading the hash of the window's Next action. */
    private fun classesSwitchingOnNext(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filterTo(found) { classDef ->
                classDef.methods.any { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == Opcode.CONST && (it as NarrowLiteralInstruction).narrowLiteral == PIP_NEXT_HASH
                    } == true
                }
            }
        }
        return found.map { ImmutableClassDef.of(it) }
    }

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun extension(): List<ClassDef> =
        listOf(ExtensionDex.classDef(REELS_PIP_BUTTONS), ExtensionDex.classDef(REELS_PIP_STILLS))

    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
            .also { assertTrue("no declared build has a fixture", it.isNotEmpty()) }
    }

    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun com.android.tools.smali.dexlib2.iface.Method.reference(): String =
        "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    /** The opcodes without the alignment nops, with a widened goto read as a goto. */
    private fun List<Instruction>.kinds(): List<Opcode> = filter { it.opcode != Opcode.NOP }.map {
        if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode
    }
}
