/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * More people in the row a long press on Share opens (discussion #811): on every declared build the
 * method filling the row asks the extension for the four it takes, the row's width clamp asks it for
 * the four it keeps the size at, the row's constructor asks it for the most faces it draws, and
 * nothing else changes. A build where any of them can't be read keeps the rest of the share sheet
 * patch.
 */
class QuickSendHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(QUICK_SEND_PEOPLE.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(QUICK_SEND_PEOPLE, QUICK_SEND_INFLATE, QUICK_SEND_SLOTS, QUICK_SEND_AVATAR, QUICK_SEND_LABEL)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** A face maker that no longer throws past the ranks it knows isn't the one the slots were written for. */
    @Test
    fun aFaceMakerThatNoLongerThrowsLeavesTheSwitchOut() {
        val context = firstOtherBuild()
        val slots = context.findQuickSend().slots
        val maker = context.mutable(slots.slot)
        val throwAt = maker.code().indices.first { it > slots.ranks && maker.code()[it].opcode == Opcode.THROW }
        maker.replaceInstruction(throwAt, "nop")
        refuses("doesn't throw past the ranks it knows") { context.findQuickSend() }
        assertNull(context.findQuickSendOrWarn())
    }

    @Test
    fun everyBuildAsksForThePeopleAllThreeTimes() {
        val checked = mutableListOf<String>()
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) checked += asks(bundle, bundle.name)
        }
        for (base in Fixtures.otherBuilds()) checked += asks(base, base.parentFile.name)
        assertTrue("no fixture was checked", checked.isNotEmpty())
    }

    @Test
    fun aBuildWithoutTheRowLeavesTheSwitchOut() {
        val context = PatchContexts.of(emptyList())
        refuses("found 0") { context.findQuickSend() }
        assertNull(context.findQuickSendOrWarn())
    }

    /** A clamp that no longer cuts the size to four isn't the one the hook was written for. */
    @Test
    fun aClampThatNoLongerCutsTheSizeLeavesTheSwitchOut() {
        val context = firstOtherBuild()
        val sites = context.findQuickSend()
        context.mutable(sites.clamp).replaceInstruction(2, "nop")
        refuses("doesn't open by keeping the size at four") { context.findQuickSend() }
        assertNull(context.findQuickSendOrWarn())
    }

    /** A four that isn't the take's count is left alone. */
    @Test
    fun aTakeOfAnotherCountLeavesTheSwitchOut() {
        val context = firstOtherBuild()
        val sites = context.findQuickSend()
        context.mutable(sites.fill).replaceInstruction(sites.four, "const/4 v${sites.count}, 0x5")
        refuses("found 0") { context.findQuickSend() }
    }

    private fun firstOtherBuild(): BytecodePatchContext {
        val build = Fixtures.otherBuilds().firstOrNull() ?: throw AssertionError("no other build to read")
        return PatchContexts.of(keptFrom(build))
    }

    /**
     * The classes loading the note, the class of the clamp the one filling the row calls after its
     * take, and the string pools they ask, read off the stock code apart from the finder.
     */
    private fun keptFrom(bundle: File): List<ClassDef> {
        val loading = FixtureDex.classesLoading(bundle, QUICK_SEND_NOTE)
        val clampTypes = loading.flatMap { it.methods }.mapNotNull { method ->
            val code = method.code()
            val take = code.indices.firstOrNull { at -> code[at].isTakeOfFour(code.getOrNull(at - 1)) } ?: return@mapNotNull null
            (take + 1 until code.size).firstNotNullOfOrNull { at ->
                (code[at].methodReference())?.takeIf { code[at].opcode == Opcode.INVOKE_VIRTUAL && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("I") }
            }?.definingClass
        }.toSet()
        assertEquals("${bundle.name}: one class clamps the row", 1, clampTypes.size)
        val clamps = FixtureDex.classes(bundle, clampTypes).values
        val makers = FixtureDex.classesLoading(bundle, QUICK_SEND_RANKS)
        assertEquals("${bundle.name}: one class makes a face by rank", 1, makers.size)
        return FixtureDex.withStringPools(bundle, (loading + clamps + makers).distinctBy { it.type })
    }

    private fun asks(bundle: File, label: String): String {
        val context = PatchContexts.of(keptFrom(bundle))
        val sites = runCatching { context.findQuickSend() }.getOrElse { throw AssertionError("$label: ${it.message}", it) }
        val fillBefore = context.mutable(sites.fill).code()
        val clampBefore = context.mutable(sites.clamp).code()
        val buildBefore = context.mutable(sites.build).code()
        val slotBefore = context.mutable(sites.slots.slot).code()
        context.askQuickSendPeople(sites)

        // The fill: the stock four stays where the loop over the list ends, and the answer goes into its register before the take.
        val fill = context.mutable(sites.fill).code()
        val where = "$label: ${sites.fill.type}->${sites.fill.name}"
        assertTrue("$where: the four is the take's", fillBefore[sites.four + 1].isTakeOfFour(fillBefore[sites.four]))
        assertEquals("$where: the four stays", Opcode.CONST_4, fill[sites.four].opcode)
        val ask = fill[sites.four + 1] as RegisterRangeInstruction
        assertEquals("$where: asks the extension", QUICK_SEND_PEOPLE, fill[sites.four + 1].reference())
        assertEquals("$where: with the four", listOf(Opcode.INVOKE_STATIC_RANGE, sites.count, 1), listOf(ask.opcode, ask.startRegister, ask.registerCount))
        assertEquals("$where: and keeps the answer", listOf(Opcode.MOVE_RESULT, sites.count), listOf(fill[sites.four + 2].opcode, (fill[sites.four + 2] as OneRegisterInstruction).registerA))
        assertEquals("$where: for the take", sites.count, (fill[sites.four + 3] as FiveRegisterInstruction).registerD)
        assertTrue("$where: the take", fill[sites.four + 3].isTakeOfFour(fill[sites.four]))
        assertEquals("$where: one ask", 1, fill.count { it.reference() == QUICK_SEND_PEOPLE })
        assertEquals("$where: the rest is Instagram's", fillBefore.shapes(), fill.filterIndexed { i, _ -> i != sites.four + 1 && i != sites.four + 2 }.shapes())

        // The clamp: its four goes to the extension, and the size it cut to four takes the answer instead.
        val clamp = context.mutable(sites.clamp).code()
        val at = "$label: ${sites.clamp.type}->${sites.clamp.name}"
        val size = context.mutable(sites.clamp).implementation!!.registerCount - 1
        assertEquals("$at: the size is p1", size, sites.size)
        assertEquals("$at: the four stays first", listOf(Opcode.CONST_4, 4), listOf(clamp[0].opcode, (clamp[0] as NarrowLiteralInstruction).narrowLiteral))
        val clampAsk = clamp[1] as FiveRegisterInstruction
        assertEquals("$at: asks the extension", QUICK_SEND_PEOPLE, clamp[1].reference())
        assertEquals("$at: with the four", listOf(Opcode.INVOKE_STATIC, 1, sites.limit), listOf(clampAsk.opcode, clampAsk.registerCount, clampAsk.registerC))
        assertEquals("$at: keeps the answer", listOf(Opcode.MOVE_RESULT, sites.limit), listOf(clamp[2].opcode, (clamp[2] as OneRegisterInstruction).registerA))
        val test = clamp[3] as TwoRegisterInstruction
        assertEquals("$at: the size is tested against the answer", listOf(Opcode.IF_LE, size, sites.limit), listOf(test.opcode, test.registerA, test.registerB))
        val cut = clamp[4] as TwoRegisterInstruction
        assertEquals("$at: a size past it takes the answer", listOf(Opcode.MOVE, size, sites.limit), listOf(cut.opcode, cut.registerA, cut.registerB))
        assertEquals("$at: the rest is Instagram's", clampBefore.drop(3).shapes(), clamp.drop(5).shapes())
        assertEquals("$at: one ask", 1, clamp.count { it.reference() == QUICK_SEND_PEOPLE })

        // The constructor: the most faces the row draws, a four it kept in a field, is the answer instead.
        val built = context.mutable(sites.build).code()
        val made = "$label: ${sites.build.type} constructor"
        assertEquals("$made: is a constructor", "<init>", sites.build.name)
        val kept = buildBefore[sites.cap] as TwoRegisterInstruction
        assertEquals("$made: keeps a four in an int field", listOf(Opcode.IPUT, sites.faces), listOf(kept.opcode, kept.registerA))
        assertTrue("$made: the four", buildBefore.take(sites.cap).last { it.opcode.setsRegister() && it is OneRegisterInstruction && it.registerA == sites.faces }.isFour())
        val capAsk = built[sites.cap] as RegisterRangeInstruction
        assertEquals("$made: asks the extension", QUICK_SEND_PEOPLE, built[sites.cap].reference())
        assertEquals("$made: with the four", listOf(Opcode.INVOKE_STATIC_RANGE, sites.faces, 1), listOf(capAsk.opcode, capAsk.startRegister, capAsk.registerCount))
        assertEquals("$made: and keeps the answer", listOf(Opcode.MOVE_RESULT, sites.faces), listOf(built[sites.cap + 1].opcode, (built[sites.cap + 1] as OneRegisterInstruction).registerA))
        assertEquals("$made: in the field", kept.reference(), built[sites.cap + 2].reference())
        assertEquals("$made: one ask", 1, built.count { it.reference() == QUICK_SEND_PEOPLE })

        // The constructor also inflates through the extension, with Instagram's registers, and adds
        // the slots right before its one return, keeping the room they made as the most faces.
        val slots = sites.slots
        val inflateBefore = buildBefore[slots.inflate] as FiveRegisterInstruction
        assertEquals("$made: inflates", "Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;)Landroid/view/View;", buildBefore[slots.inflate].reference())
        val inflateAt = if (sites.cap < slots.inflate) slots.inflate + 2 else slots.inflate
        val inflate = built[inflateAt] as FiveRegisterInstruction
        assertEquals("$made: through the extension", listOf(Opcode.INVOKE_STATIC, QUICK_SEND_INFLATE), listOf(inflate.opcode, built[inflateAt].reference()))
        assertEquals(
            "$made: with Instagram's inflater, layout and parent",
            listOf(inflateBefore.registerC, inflateBefore.registerD, inflateBefore.registerE),
            listOf(inflate.registerC, inflate.registerD, inflate.registerE),
        )
        assertEquals("$made: returns once", Opcode.RETURN_VOID, buildBefore[slots.end].opcode)
        val end = slots.end + 2
        assertEquals(
            "$made: adds the slots and keeps their room",
            listOf(
                Opcode.IGET to kept.reference(), Opcode.INVOKE_STATIC to QUICK_SEND_SLOTS, Opcode.MOVE_RESULT to null,
                Opcode.IPUT to kept.reference(), Opcode.RETURN_VOID to null,
            ),
            built.subList(end, end + 5).map { it.opcode to it.reference() },
        )
        val asks = setOf(sites.cap, sites.cap + 1, inflateAt) + (end until end + 4)
        assertEquals(
            "$made: the rest is Instagram's",
            buildBefore.filterIndexed { i, _ -> i != slots.inflate }.shapes(),
            built.filterIndexed { i, _ -> i !in asks }.shapes(),
        )

        // The face for a rank past Instagram's asks the extension for its slot's ids before the throw, and meets the known ranks.
        val slot = context.mutable(slots.slot)
        val maker = slot.code()
        val face = "$label: ${slots.slot.type} constructor"
        assertEquals("$face: throws past its ranks", QUICK_SEND_RANKS, slotBefore[slots.ranks].reference())
        val block = maker.subList(slots.ranks, slots.ranks + 14)
        assertEquals(
            "$face: the slot's ids",
            listOf(
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT_OBJECT, Opcode.IPUT_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT, Opcode.GOTO, Opcode.NOP,
            ),
            block.map { if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode },
        )
        assertEquals("$face: asks for the avatar's id", listOf(QUICK_SEND_AVATAR, slots.rank), listOf(block[0].reference(), (block[0] as FiveRegisterInstruction).registerC))
        assertEquals("$face: finds it as Instagram finds the others", slots.look, block[5].reference())
        assertTrue("$face: with a lookup answering a view", slots.look.endsWith(")Landroid/view/View;"))
        assertEquals(
            "$face: the one Instagram keeps the others with",
            setOf(slots.look),
            slotBefore.indices.filter { slotBefore[it].opcode == Opcode.IPUT_OBJECT && slotBefore[it].reference() == slots.avatar }
                .map { slotBefore[it - 2].reference() }.toSet(),
        )
        assertEquals("$face: keeps it in the avatar's field", slots.avatar, block[7].reference())
        assertEquals("$face: asks for the name's id", listOf(QUICK_SEND_LABEL, slots.rank), listOf(block[10].reference(), (block[10] as FiveRegisterInstruction).registerC))
        assertEquals("$face: a rank without a slot goes on to the throw", slots.ranks + 13, maker.targetOf(slots.ranks + 2))
        val meet = if (slots.tail > slots.ranks) slots.tail + 14 else slots.tail
        assertEquals("$face: and one with a slot meets the known ranks", meet, maker.targetOf(slots.ranks + 12))
        assertEquals("$face: where the name is found", slots.find, maker[meet].reference())
        assertEquals("$face: the rest is Instagram's", slotBefore.shapes(), maker.filterIndexed { i, _ -> i !in slots.ranks until slots.ranks + 14 }.shapes())
        return label
    }

    /** The index a branch or goto at [at] lands on. */
    private fun List<Instruction>.targetOf(at: Int): Int {
        val addresses = runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val target = addresses[at] + (this[at] as OffsetInstruction).codeOffset
        return addresses.indexOf(target).also { assertTrue("no instruction at $target", it in indices) }
    }

    /** A constructor that no longer keeps four faces isn't the one the hook was written for. */
    @Test
    fun aRowThatNoLongerDrawsFourFacesLeavesTheSwitchOut() {
        val context = firstOtherBuild()
        val sites = context.findQuickSend()
        val build = context.mutable(sites.build)
        val four = build.code().take(sites.cap).indexOfLast { it.opcode.setsRegister() && it is OneRegisterInstruction && it.registerA == sites.faces }
        build.replaceInstruction(four, "const/4 v${sites.faces}, 0x5")
        refuses("keeping four faces, found 0") { context.findQuickSend() }
        assertNull(context.findQuickSendOrWarn())
    }

    /** The patch refuses, for the reason given. */
    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    private fun Instruction.isFour() = opcode == Opcode.CONST_4 && (this as NarrowLiteralInstruction).narrowLiteral == 4

    private fun Instruction.isTakeOfFour(before: Instruction?): Boolean {
        val call = methodReference() ?: return false
        if (opcode != Opcode.INVOKE_STATIC || call.returnType != "Ljava/util/List;" ||
            call.parameterTypes.map(Any::toString) != listOf("Ljava/lang/Iterable;", "I")
        ) {
            return false
        }
        return before?.opcode == Opcode.CONST_4 && (before as NarrowLiteralInstruction).narrowLiteral == 4 &&
            (before as OneRegisterInstruction).registerA == (this as FiveRegisterInstruction).registerD
    }

    /** Each instruction's kind and reference, nops left out and a widened goto read as a goto. */
    private fun List<Instruction>.shapes() = filter { it.opcode != Opcode.NOP && !it.opcode.format.isPayloadFormat }.map { instruction ->
        val kind = when (instruction.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> instruction.opcode
        }
        "$kind ${instruction.reference().orEmpty()}"
    }

    private fun BytecodePatchContext.mutable(site: SheetMethod): MutableMethod =
        mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
