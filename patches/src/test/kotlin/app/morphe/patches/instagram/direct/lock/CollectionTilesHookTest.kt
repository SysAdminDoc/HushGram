/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.otherBuilds
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lock collection on a Saved collection's long press (#678): each method making a collection tile
 * hands the tile to the extension as soon as it has it, just before it looks up the tile's
 * thumbnail, so the long press is on the tile before Instagram's accessibility setup reads its long
 * click flag. A build whose makers moved refuses before anything changes, and the lock goes on
 * without the long press.
 */
class CollectionTilesHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(COLLECTION_TILE.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$COLLECTION_TILE is not in the extension: $declared", COLLECTION_TILE.substringAfter("->") in declared)
    }

    @Test
    fun eachMakerHandsItsTileOverBeforeLookingUpItsThumbnail() {
        val classes = listOf(binder(), adapter(), elsewhere())
        val context = PatchContexts.of(classes)
        val sites = context.findCollectionTiles().sortedBy { it.method.definingClass }
        assertEquals(listOf(ADAPTER, BINDER), sites.map { it.method.definingClass })
        hookCollectionTiles(sites)

        val adapted = context.mutableClassDefBy(ADAPTER).methods.single().instructions()
        assertEquals("adapter: the holder's constructor comes first", HOLD.toString(), (adapted[9] as ReferenceInstruction).reference.toString())
        assertHooked("adapter", adapted, at = 10, tile = 3)
        assertTrue("adapter: then the thumbnail's id", (adapted[11] as NarrowLiteralInstruction).narrowLiteral == SAVED_COLLECTION_THUMBNAIL)
        assertEquals("adapter: then its range lookup", Opcode.INVOKE_VIRTUAL_RANGE, adapted[12].opcode)

        val bound = context.mutableClassDefBy(BINDER).methods.single().instructions()
        assertHooked("binder", bound, at = 4, tile = 2)
        assertTrue("binder: then the thumbnail's id", (bound[5] as NarrowLiteralInstruction).narrowLiteral == SAVED_COLLECTION_THUMBNAIL)
        assertEquals("binder: then its lookup", FIND_VIEW.toString(), (bound[6] as ReferenceInstruction).reference.toString())
        assertEquals("binder: the accessibility setup comes after", ACCESSIBILITY.toString(), (bound[8] as ReferenceInstruction).reference.toString())
        assertEquals("binder: and the row's addView", ADD_VIEW, (bound[9] as ReferenceInstruction).reference.toString())

        assertEquals("a class not making tiles stays", elsewhere().methods.single().instructions().map(::text),
            context.mutableClassDefBy(ELSEWHERE).methods.single().instructions().map(::text))
    }

    @Test
    fun aStaticLookupHelperFindsTheTileToo() {
        val context = PatchContexts.of(listOf(adapter(helper = true)))
        val sites = context.findCollectionTiles()
        hookCollectionTiles(sites)
        val adapted = context.mutableClassDefBy(ADAPTER).methods.single().instructions()
        assertHooked("adapter", adapted, at = 10, tile = 3)
        assertEquals("adapter: then the helper's lookup", FIND_HELPER.toString(), (adapted[12] as ReferenceInstruction).reference.toString())
    }

    @Test
    fun aMissingDoubledOrJumpedOntoTileFailsBeforeAnythingChanges() {
        refuses("found no method making a Saved collection tile", listOf(elsewhere()))
        refuses("found 2", listOf(binder(loads = 2)))
        refuses("look up its collection tile's thumbnail right after loading its id", listOf(binder(lookup = false)))
        refuses("jumps to its collection tile's thumbnail id", listOf(binder(jump = true)))
        refuses("look up its collection tile's thumbnail right after loading its id", listOf(adapter(), binder(lookup = false)))
    }

    /** A build whose tiles moved refuses naming the lock, and the lock goes on without the long press. */
    @Test
    fun anEmptyBuildRefusesNamingTheLockAndTheRestGoesOn() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList<ClassDef>()).findCollectionTiles() }
        assertTrue(failure.message, failure.message!!.startsWith("$LOCK_PATCH: "))
        assertEquals(emptyList<CollectionTileSite>(), PatchContexts.of(emptyList<ClassDef>()).findCollectionTilesOrSkip())

        val classes = listOf(binder(loads = 2))
        val context = PatchContexts.of(classes)
        assertEquals(emptyList<CollectionTileSite>(), context.findCollectionTilesOrSkip())
        assertEquals("a refused maker stays", binder(loads = 2).methods.single().instructions().map(::text),
            context.mutableClassDefBy(BINDER).methods.single().instructions().map(::text))
    }

    @Test fun declaredBuildsHandOverEveryCollectionTile() = fixtures { builds(it) }

    @Test fun otherBuildsHandOverEveryCollectionTileToo() = otherBuilds { builds(it) }

    /**
     * Every class loading the thumbnail's id, as the patcher's literal index picks them: the See all
     * grid's row binder and the Saved home row's adapter, each hooked once just before its thumbnail
     * lookup, ahead of the binder's accessibility setup, with the rest of its code as it was. One
     * adds its tile to a row, the other hands it to a view holder.
     */
    private fun builds(bundle: File) {
        val label = bundle.parentFile.name + "/" + bundle.name
        val loading = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val loads = classDef.methods.any { method ->
                    method.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == SAVED_COLLECTION_THUMBNAIL } == true
                }
                if (loads) loading += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(loading)
        val sites = try {
            context.findCollectionTiles()
        } catch (failure: PatchException) {
            throw AssertionError("$label: ${failure.message}", failure)
        }
        assertEquals("$label: the two tile makers", 2, sites.size)
        val stock = sites.map { site -> site.method.instructions().filter { it.opcode != Opcode.NOP }.map(::text) }
        hookCollectionTiles(sites)

        val hands = sites.zip(stock).map { (site, before) ->
            val where = "$label: ${site.method.definingClass}->${site.method.name}"
            val code = site.method.instructions()
            assertHooked(where, code, site.at, site.tile)
            assertTrue("$where: the thumbnail's id comes next", (code[site.at + 1] as? NarrowLiteralInstruction)?.narrowLiteral == SAVED_COLLECTION_THUMBNAIL)
            val lookup = code[site.at + 2]
            assertTrue("$where: then the lookup", lookup.looksUp())
            assertEquals("$where: the tile is the lookup's view", site.tile, receiver(lookup))
            assertEquals("$where: Instagram's own code stays", before,
                code.filterIndexed { at, each -> at != site.at && each.opcode != Opcode.NOP }.map(::text))
            val early = code.take(site.at).filter { it.setsUpTile(site.tile) }
            assertEquals("$where: nothing sets the tile up before the hook", emptyList<String>(), early.map(::text))
            val hand = code.firstOrNull { it.handsOn(site.tile) }
                ?: throw AssertionError("$where: never adds its tile to a row or hands it to a view holder")
            ((hand as ReferenceInstruction).reference as MethodReference).name
        }
        assertEquals("$label: one maker adds its tile to a row, one hands it to a view holder",
            listOf("<init>", "addView"), hands.sorted())
        val calls = loading.sumOf { classDef ->
            context.mutableClassDefBy(classDef.type).methods.sumOf { method ->
                method.instructions().count { (it as? ReferenceInstruction)?.reference?.toString() == COLLECTION_TILE }
            }
        }
        assertEquals("$label: tile hooks", 2, calls)
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.instructions().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { hookCollectionTiles(context.findCollectionTiles()) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        assertTrue("names the lock: ${refusal.message}", refusal.message.orEmpty().startsWith("$LOCK_PATCH: "))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.instructions().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    /** At [at], the tile in [tile] goes to the extension, one register in the range form. */
    private fun assertHooked(what: String, code: List<Instruction>, at: Int, tile: Int) {
        val hook = code[at]
        assertEquals("$what: the hook", COLLECTION_TILE, (hook as ReferenceInstruction).reference.toString())
        assertEquals("$what: the hook's form", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals("$what: the tile", tile, (hook as RegisterRangeInstruction).startRegister)
        assertEquals("$what: one register", 1, hook.registerCount)
        assertEquals("$what: one hook", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == COLLECTION_TILE })
    }

    /** The first register a call takes, the view a lookup is called on. */
    private fun receiver(call: Instruction): Int = when (call) {
        is RegisterRangeInstruction -> call.startRegister
        is FiveRegisterInstruction -> call.registerC
        else -> throw AssertionError("${call.opcode} takes no registers")
    }

    /** The View a row's addView or a holder's constructor is handed, after the row or the holder. */
    private fun viewRegister(call: Instruction): Int? = when (call) {
        is RegisterRangeInstruction -> call.startRegister + 1
        is FiveRegisterInstruction -> call.registerD
        else -> null
    }

    private fun Instruction.callee(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    /** A view's own findViewById or requireViewById, or a static (View, int) helper answering a view. */
    private fun Instruction.looksUp(): Boolean {
        val call = callee() ?: return false
        val types = call.parameterTypes.map(CharSequence::toString)
        return when (opcode) {
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> call.name in LOOKUPS && types == listOf("I")
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> types == listOf(TILE_VIEW, "I") && call.returnType.startsWith("L")
            else -> false
        }
    }

    /** ViewGroup.addView(View) of [tile] on a row, or a constructor taking just [tile], a view holder's. */
    private fun Instruction.handsOn(tile: Int): Boolean {
        val call = callee() ?: return false
        if (call.parameterTypes.map(CharSequence::toString) != listOf(TILE_VIEW) || viewRegister(this) != tile) return false
        return when (opcode) {
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE -> call.definingClass == ROW && call.name == "addView"
            Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> call.name == "<init>"
            else -> false
        }
    }

    /** A static (View)V call on [tile], the shape of Instagram's accessibility setup (0220.A1G in 456). */
    private fun Instruction.setsUpTile(tile: Int): Boolean {
        if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
        val call = callee() ?: return false
        return call.returnType == "V" && call.parameterTypes.map(CharSequence::toString) == listOf(TILE_VIEW) && receiver(this) == tile
    }

    /** What an instruction does, a goto widened by the insert reading as the goto it was. */
    private fun text(instruction: Instruction): String {
        val opcode = if (instruction.opcode in GOTOS) Opcode.GOTO else instruction.opcode
        return "$opcode ${(instruction as? ReferenceInstruction)?.reference ?: ""}"
    }

    private companion object {
        const val BINDER = "Lfixture/SavedHomeCollectionBinder;"
        const val ADAPTER = "Lfixture/CollectionsListAdapter;"
        const val HOLDER = "Lfixture/CollectionViewHolder;"
        const val ELSEWHERE = "Lfixture/ProfileHeader;"
        const val OBJECT = "Ljava/lang/Object;"
        const val TILE_VIEW = "Landroid/view/View;"
        const val ROW = "Landroid/view/ViewGroup;"
        const val INFLATER = "Landroid/view/LayoutInflater;"
        const val ADD_VIEW = "Landroid/view/ViewGroup;->addView(Landroid/view/View;)V"
        const val LAYOUT = 0x7f0e0101
        val GOTOS = setOf(Opcode.GOTO_16, Opcode.GOTO_32)
        val INFLATE = ImmutableMethodReference(INFLATER, "inflate", listOf("I", ROW, "Z"), TILE_VIEW)
        val FIND_VIEW = ImmutableMethodReference(TILE_VIEW, "findViewById", listOf("I"), TILE_VIEW)
        val FIND_HELPER = ImmutableMethodReference("Lfixture/Views;", "A0G", listOf(TILE_VIEW, "I"), TILE_VIEW)
        val ADD = ImmutableMethodReference(ROW, "addView", listOf(TILE_VIEW), "V")
        val HOLD = ImmutableMethodReference(HOLDER, "<init>", listOf(TILE_VIEW), "V")
        val ACCESSIBILITY = ImmutableMethodReference("Lfixture/Accessibility;", "setUp", listOf(TILE_VIEW), "V")
        val LOOKUPS = setOf("findViewById", "requireViewById")

        fun method(type: String, name: String, parameters: List<String>, returnType: String, access: Int, registers: Int,
                   code: List<ImmutableInstruction>) = ImmutableMethod(type, name,
            parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, access, null, null,
            ImmutableMethodImplementation(registers, code, null, null))

        fun classOf(type: String, method: ImmutableMethod) =
            ImmutableClassDef(type, AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, listOf(method))

        /**
         * Shaped like the See all grid's row binder: (row, inflater) inflates a tile into v2, finds
         * its thumbnail, gives the tile its accessibility setup, adds it to the row and returns.
         * [loads] is how often it loads the thumbnail's id, [lookup] false puts a nop between the id
         * and its lookup, and [jump] opens with a branch onto the id.
         */
        fun binder(loads: Int = 1, lookup: Boolean = true, jump: Boolean = false): ClassDef {
            val made = listOf<ImmutableInstruction>(
                ImmutableInstruction31i(Opcode.CONST, 0, LAYOUT),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 5, 0, 4, 1, 0, INFLATE),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
            )
            val body = made.toMutableList<ImmutableInstruction>()
            body += ImmutableInstruction31i(Opcode.CONST, 0, SAVED_COLLECTION_THUMBNAIL)
            if (!lookup) body += ImmutableInstruction10x(Opcode.NOP)
            body += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 2, 0, 0, 0, 0, FIND_VIEW)
            body += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3)
            body += ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, ACCESSIBILITY)
            body += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 4, 2, 0, 0, 0, ADD)
            repeat(loads - 1) { body += ImmutableInstruction31i(Opcode.CONST, 1, SAVED_COLLECTION_THUMBNAIL) }
            val code = if (jump) {
                // An if-eqz is two code units; it lands on the thumbnail's id, right after the tile is made.
                listOf(ImmutableInstruction21t(Opcode.IF_EQZ, 4, 2 + made.sumOf { it.codeUnits })) + body
            } else body
            val make = method(BINDER, "createView", listOf(ROW, INFLATER), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, 6, code + ImmutableInstruction10x(Opcode.RETURN_VOID))
            return classOf(BINDER, make)
        }

        /**
         * Shaped like the Saved home row's view holder maker: (parent, view type) inflates a tile into
         * v3, hands the tile to a new view holder, finds its thumbnail through the range form, then
         * returns the holder. [helper] finds it through a static (View, int) helper instead, the way
         * 386214562's adapter does.
         */
        fun adapter(helper: Boolean = false): ClassDef {
            val code = listOf<ImmutableInstruction>(
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 6, 0, 0, 0, 0,
                    ImmutableMethodReference(TILE_VIEW, "getContext", emptyList(), "Landroid/content/Context;")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                    ImmutableMethodReference(INFLATER, "from", listOf("Landroid/content/Context;"), INFLATER)),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction31i(Opcode.CONST, 1, LAYOUT),
                ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 0, 1, 6, 2, 0, INFLATE),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 2, ImmutableTypeReference(HOLDER)),
                ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, 2, 2, HOLD),
                ImmutableInstruction31i(Opcode.CONST, 4, SAVED_COLLECTION_THUMBNAIL),
                if (helper) ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 3, 4, 0, 0, 0, FIND_HELPER)
                else ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 3, 2, FIND_VIEW),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2),
            )
            val make = method(ADAPTER, "A0Z", listOf(ROW, "I"), HOLDER, AccessFlags.PUBLIC.value, 8, code)
            return classOf(ADAPTER, make)
        }

        /** A class adding a view to a row without the thumbnail's id: never a tile maker. */
        fun elsewhere(): ClassDef = classOf(ELSEWHERE, method(ELSEWHERE, "bind", listOf(ROW, TILE_VIEW), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, 2, listOf(
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, ADD),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )))
    }
}
