/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caught up card's Suggested for you title (#104): on 451 Home's end of feed card shows it
 * through its holder's (title, subtitle) call, and the group set's header isn't drawn. The hooks are
 * in the extension, the card's open section is found the way its own getter finds it, and in every
 * declared build, where the binder draws the group set's link beside the title, the holder's call
 * runs again with the title past FeedSuggestions.caughtUpTitle and the card in hand.
 */
class CaughtUpCardTitleTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_SUGGESTIONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$FEED_SUGGESTIONS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$CAUGHT_UP_TITLE is not in the extension: $declared", CAUGHT_UP_TITLE in declared)
        assertTrue("the section stub is not in the extension: $declared", "$FEED_SUGGESTIONS->$CAUGHT_UP_SECTION_STUB(Ljava/lang/Object;)Ljava/lang/Object;" in declared)
    }

    @Test
    fun theTitleAndTheWalkAreFound() {
        val found = PatchContexts.of(classes()).findCaughtUpCardTitle()

        assertEquals(BINDER, found.binder)
        assertEquals(TITLE_AT, found.titleAt)
        assertEquals("$HOLDER->setTitles($STRING$STRING)V", found.titleCall)
        assertEquals("$DATA->title:$STRING", found.titleField)
        assertEquals("$DATA->subtitle:$STRING", found.subtitleField)
        assertEquals(LINK_AT, found.linkAt)
        assertEquals("$SECTION->link:$STRING", found.linkRead)
        assertEquals("$HOLDER->setLink($STRING$LISTENER)V", found.linkCall)
        assertEquals(3, found.holder)
        assertEquals(8, found.card)
        assertEquals("v0 is written by the link's read and v1 isn't read again", listOf(0, 1), found.scratch)
        assertEquals(CARD, found.cardType)
        assertEquals("$CARD->data:$DATA", found.data)
        assertEquals("$DATA->groupSet:$GROUP_SET", found.groupSet)
        assertEquals(SECTIONS, found.sectionsType)
        assertEquals("$SECTIONS->activeId:$STRING", found.activeId)
        assertEquals("$SECTIONS->find($STRING)$SECTION", found.lookup)
        assertEquals(SECTION, found.sectionType)
        assertEquals("$SECTION->items:$LIST", found.items)
    }

    @Test
    fun theTitleGoesPastTheExtension() {
        val context = PatchContexts.of(classes())
        val found = context.findCaughtUpCardTitle()

        found.write(context, itemsFilled = false)

        assertHooked("stand-in", context, found, itemsFilled = false)
    }

    /** With the header's hook in, the items stub is the header's and the card leaves it be. */
    @Test
    fun theItemsStubIsFilledOnce() {
        val context = PatchContexts.of(classes())
        val found = context.findCaughtUpCardTitle()

        found.write(context, itemsFilled = true)

        assertHooked("stand-in", context, found, itemsFilled = true)
    }

    /** A build without the card's binder keeps Hide suggested posts as it was and leaves the stubs alone. */
    @Test
    fun aBuildWithoutTheCardChangesNothing() {
        val extension = ExtensionDex.classDef(FEED_SUGGESTIONS)
        val context = PatchContexts.of(listOf(extension))
        val before = extension.methods.associate { it.name to it.code().size }

        assertNull(context.caughtUpCardTitleOrWarn(null))

        val after = context.classDefBy(FEED_SUGGESTIONS).methods.associate { it.name to it.code().size }
        assertEquals(before, after)
    }

    @Test
    fun noBinderFailsTheFind() = refused("found 0", classes().filter { it.type != BINDER })

    @Test
    fun twoTitleCallsFailTheFind() = refused("found 2", classes(titleCalls = 2))

    @Test
    fun aCardWrittenOverFailsTheFind() = refused("writes over the card", classes(cardWritten = true))

    @Test
    fun aJumpOntoTheTitleFailsTheFind() = refused("jumps between", classes(jumpTo = TITLE_AT))

    @Test
    fun aJumpOntoTheLinkFailsTheFind() = refused("jumps between", classes(jumpTo = LINK_AT))

    /** Onto the card's section getter, between the title and the link: neither is a jump target. */
    @Test
    fun aJumpIntoTheMiddleFailsTheFind() = refused("jumps between", classes(jumpTo = TITLE_AT + 1))

    /** The hook's reads name the card in a four bit register. */
    @Test
    fun aCardPastV15FailsTheFind() = refused("past v15", classes(cardRegister = 16))

    @Test
    fun aLinkBeforeTheCardFailsTheFind() = refused("sets the link before it casts the card", classes(linkFirst = true))

    @Test
    fun aSubtitleFromElsewhereFailsTheFind() = refused("aren't read from", classes(subtitleOwner = SECTION))

    /** v1, the title, read again by the holder after the link: the hook borrows v2 instead. */
    @Test
    fun aRegisterReadAfterTheLinkIsntBorrowed() {
        val context = PatchContexts.of(classes(titleReadAfter = true))
        val found = context.findCaughtUpCardTitle()

        assertEquals(listOf(0, 2), found.scratch)
        found.write(context, itemsFilled = false)
        assertHooked("v1 read after the link", context, found, itemsFilled = false)
    }

    /** A card that draws no group set link has no section header to drop. */
    @Test
    fun noLinkFailsTheFind() = refused("expected one link", classes(link = false))

    @Test
    fun aTitleFromElsewhereFailsTheFind() = refused("aren't read from", classes(titleOwner = SECTION))

    @Test
    fun aSectionsViewThatIsntPublicFailsTheFind() = refused("isn't public", classes(sectionsFlags = AccessFlags.FINAL.value))

    @Test
    fun anIdThatIsntPublicFailsTheFind() = refused("isn't a public instance field", classes(idFlags = AccessFlags.FINAL.value))

    @Test
    fun eachDeclaredBuildHooksTheTitle() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooksTheTitle(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksTheTitle() {
        var checked = 0
        for (apk in Fixtures.otherBuilds()) {
            hooksTheTitle(apk, apk.parentFile.name)
            checked++
        }
        assertTrue("no other build", checked > 0)
    }

    /** The binder, the classes it names, the classes they name (the card's walk among them) and the extension. */
    private fun hooksTheTitle(bundle: File, label: String) {
        val binders = FixtureDex.classesHolding(bundle, END_CARD_HOLDER)
            .filter { binder -> binder.methods.any { method -> method.code().any { it.loads(END_CARD_UNIT) } } }
        assertTrue("$label: no class holds both of the card's casts", binders.isNotEmpty())
        val named = FixtureDex.classes(bundle, binders.typesNamed()).values
        val walked = FixtureDex.classes(bundle, named.typesNamed()).values
        val context = PatchContexts.of((binders + named + walked).distinctBy { it.type } + ExtensionDex.classDef(FEED_SUGGESTIONS))

        val found = context.findCaughtUpCardTitle()
        found.write(context, itemsFilled = false)

        assertHooked("$label ${found.binder}", context, found, itemsFilled = false)
    }

    /**
     * Right before the link's read, once in bindView: the card's title and the card go to the
     * extension, and the holder's (title, subtitle) call runs again with its answer and the card's
     * subtitle, in two registers nothing reads afterwards. The first call is left as it was. The
     * section stub walks from the card to its open section, and the items stub reads the section's
     * list unless the header filled it.
     */
    private fun assertHooked(what: String, context: BytecodePatchContext, found: CaughtUpCardTitle, itemsFilled: Boolean) {
        val code = context.mutableClassDefBy(found.binder).methods.single {
            it.name == "bindView" && it.parameterTypes.map(CharSequence::toString) == BIND_VIEW
        }.code()
        val at = found.linkAt
        val calls = code.indices.filter { code[it].referenceText() == CAUGHT_UP_TITLE }
        assertEquals("$what: one hook, just before the link", listOf(at + 2), calls)
        assertEquals("$what: the title's own call stays", found.titleCall, code[found.titleAt].referenceText())
        val (fields, title) = found.scratch
        assertTrue("$what: borrowed ${found.scratch} apart from the card and the holder", fields != title && found.scratch.none { it == found.card || it == found.holder || it > 15 })
        assertEquals(
            "$what: the card's title past the extension, then the holder's call again",
            listOf(
                Opcode.IGET_OBJECT to found.data,
                Opcode.IGET_OBJECT to found.titleField,
                Opcode.INVOKE_STATIC to CAUGHT_UP_TITLE,
                Opcode.MOVE_RESULT_OBJECT to null,
                Opcode.IGET_OBJECT to found.subtitleField,
                Opcode.INVOKE_VIRTUAL to found.titleCall,
                Opcode.IGET_OBJECT to found.linkRead,
                Opcode.INVOKE_VIRTUAL to found.linkCall,
            ),
            code.subList(at, at + 8).map { it.opcode to it.referenceText() },
        )
        val reads = listOf(code[at], code[at + 1], code[at + 4]).map { it as TwoRegisterInstruction }
        assertEquals("$what: data from the card, the title and the subtitle from data", listOf(fields to found.card, title to fields, fields to fields), reads.map { it.registerA to it.registerB })
        val ask = code[at + 2] as FiveRegisterInstruction
        assertEquals("$what: with the title and the card", listOf(2, title, found.card), listOf(ask.registerCount, ask.registerC, ask.registerD))
        assertEquals("$what: the answer in the title's register", title, (code[at + 3] as OneRegisterInstruction).registerA)
        val set = code[at + 5] as FiveRegisterInstruction
        assertEquals("$what: on the holder, the answer and the subtitle", listOf(3, found.holder, title, fields), listOf(set.registerCount, set.registerC, set.registerD, set.registerE))

        val extension = context.mutableClassDefBy(FEED_SUGGESTIONS).methods
        val stub = extension.single { it.name == CAUGHT_UP_SECTION_STUB }
        assertEquals("$what: the section stub has room for the walk", 3, stub.implementation!!.registerCount)
        assertEquals(
            "$what: the section stub walks the card's way",
            listOf(
                Opcode.CHECK_CAST to found.cardType,
                Opcode.IGET_OBJECT to found.data,
                Opcode.IF_EQZ to null,
                Opcode.IGET_OBJECT to found.groupSet,
                Opcode.IF_EQZ to null,
                Opcode.NEW_INSTANCE to found.sectionsType,
                Opcode.INVOKE_DIRECT to "${found.sectionsType}-><init>(${found.groupSetType})V",
                Opcode.IGET_OBJECT to found.activeId,
                Opcode.INVOKE_VIRTUAL to found.lookup,
                Opcode.MOVE_RESULT_OBJECT to null,
                Opcode.RETURN_OBJECT to null,
            ),
            stub.code().map { it.opcode to it.referenceText() },
        )
        val items = extension.single { it.name == SECTION_ITEMS_STUB }.code()
        if (itemsFilled) {
            assertTrue("$what: the items stub is left to the header", items.firstOrNull()?.opcode != Opcode.CHECK_CAST)
        } else {
            assertEquals(
                "$what: the items stub reads the section's list",
                listOf(Opcode.CHECK_CAST to found.sectionType, Opcode.IGET_OBJECT to found.items, Opcode.RETURN_OBJECT to null),
                items.take(3).map { it.opcode to it.referenceText() },
            )
        }
    }

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findCaughtUpCardTitle() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.loads(value: String) =
        ((this as? ReferenceInstruction)?.reference as? StringReference)?.string == value

    /** The app's classes the code of these classes names: casts, calls' owners and answers, fields' owners and types. */
    private fun Collection<ClassDef>.typesNamed(): Set<String> = flatMap { classDef ->
        classDef.methods.flatMap { it.code() }.flatMap { instruction ->
            when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                is TypeReference -> listOf(reference.type)
                is MethodReference -> listOf(reference.definingClass, reference.returnType) + reference.parameterTypes.map(CharSequence::toString)
                is FieldReference -> listOf(reference.definingClass, reference.type)
                else -> emptyList()
            }
        }
    }.filter { it.startsWith("L") && !it.startsWith("Ljava/") && !it.startsWith("Landroid/") }.toSet()

    private companion object {
        const val BINDER = "Lfixture/CardBinder;"
        const val HOLDER = "Lfixture/Holder;"
        const val CARD = "Lfixture/Card;"
        const val DATA = "Lfixture/CardData;"
        const val GROUP_SET = "Lfixture/GroupSet;"
        const val SECTIONS = "Lfixture/Sections;"
        const val SECTION = "Lfixture/Section;"
        const val FALLBACK = "Lfixture/Fallback;"
        const val STRING = "Ljava/lang/String;"
        const val LIST = "Ljava/util/List;"
        const val LISTENER = "Landroid/view/View\$OnClickListener;"
        const val PUBLIC_FINAL = 0x11
        const val TITLE_AT = 9
        const val LINK_AT = 12
        val BIND_VIEW = listOf("I", "Landroid/view/View;", "Ljava/lang/Object;", "Ljava/lang/Object;")

        fun field(owner: String, name: String, type: String) = ImmutableFieldReference(owner, name, type)

        fun invoke(opcode: Opcode, count: Int, c: Int, d: Int, e: Int, owner: String, name: String, parameters: List<String>, returns: String) =
            ImmutableInstruction35c(opcode, count, c, d, e, 0, 0, ImmutableMethodReference(owner, name, parameters, returns))

        fun method(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, code: List<Instruction>?) =
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                code?.let { ImmutableMethodImplementation(registers, it, null, null) },
            )

        fun publicField(owner: String, name: String, type: String, flags: Int = PUBLIC_FINAL) = ImmutableField(owner, name, type, flags, null, null, null)

        /**
         * Shaped like 451's LX/05ws bindView: the holder's and the card's casts after their messages, the
         * title and subtitle read from the card's data and set together, the card's section getter, then
         * the link's text read from the section and set on the holder, and the card handed to the holder.
         * Registers 14, the parameters in v9 to v13. By index:
         *   0 move   1 const-string   2 check-cast holder   3 const-string   4 check-cast card
         *   5 iget data   6 iget title   7 iget subtitle   8 nop   9 setTitles   10 section()
         *   11 move-result   12 iget link   13 setLink   14 bindCard   then return-void
         */
        fun classes(
            titleCalls: Int = 1,
            cardWritten: Boolean = false,
            jumpTo: Int? = null,
            link: Boolean = true,
            titleOwner: String = DATA,
            subtitleOwner: String = DATA,
            sectionsFlags: Int = PUBLIC_FINAL,
            idFlags: Int = PUBLIC_FINAL,
            /** Where the card is cast. A register past v15 is a stand-in that only the find reads. */
            cardRegister: Int = 8,
            /** The link's read and call right after the holder's cast, ahead of the card's. */
            linkFirst: Boolean = false,
            titleReadAfter: Boolean = false,
        ): List<ClassDef> {
            val setTitles = { invoke(Opcode.INVOKE_VIRTUAL, 3, 3, 1, 0, HOLDER, "setTitles", listOf(STRING, STRING), "V") }
            val linkCode = listOf(
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 9, field(SECTION, "link", STRING)),
                invoke(Opcode.INVOKE_VIRTUAL, 3, 3, 0, 7, HOLDER, "setLink", listOf(STRING, LISTENER), "V"),
            )
            val code = mutableListOf<Instruction>(
                ImmutableInstruction12x(Opcode.MOVE_OBJECT, 8, 12),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(END_CARD_HOLDER)),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 3, ImmutableTypeReference(HOLDER)),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(END_CARD_UNIT)),
                ImmutableInstruction21c(Opcode.CHECK_CAST, cardRegister, ImmutableTypeReference(CARD)),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 6, 8, field(CARD, "data", DATA)),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 6, field(titleOwner, "title", STRING)),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, if (cardWritten) 8 else 0, 6, field(subtitleOwner, "subtitle", STRING)),
                // Without a write over the card, the subtitle's register; with one, the card's own.
                if (cardWritten) ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 6, field(subtitleOwner, "subtitle", STRING))
                else ImmutableInstruction10x(Opcode.NOP),
                setTitles(),
                invoke(Opcode.INVOKE_VIRTUAL, 1, 8, 0, 0, CARD, "section", emptyList(), SECTION),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 9),
            )
            if (titleCalls == 2) code += setTitles()
            if (link && linkFirst) code.addAll(3, linkCode)
            if (link) {
                if (!linkFirst) code += linkCode
                code += invoke(Opcode.INVOKE_VIRTUAL, 2, 3, 8, 0, HOLDER, "bindCard", listOf(CARD), "V")
            }
            if (titleReadAfter) code += invoke(Opcode.INVOKE_VIRTUAL, 2, 3, 1, 0, HOLDER, "describe", listOf(STRING), "V")
            // A goto from below back onto the title's call or the link's read.
            if (jumpTo != null) code += ImmutableInstruction10t(Opcode.GOTO, -(code.drop(jumpTo).sumOf { it.codeUnits }))
            code += ImmutableInstruction10x(Opcode.RETURN_VOID)
            val binder = ImmutableClassDef(
                BINDER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(method(BINDER, "bindView", BIND_VIEW, "V", AccessFlags.PUBLIC.value, 14, code)),
            )
            // Shaped like 451's LX/06hB->A00: the group set's sections view and its lookup, else a section from the fallback.
            //   0 iget data   2 iget group set   4 if-eqz +14   6 new-instance   8 <init>   11 const-string   13 find
            //   16 move-result   17 return   18 iget fallback   20 new-instance   22 <init>   25 return
            val getter = listOf<Instruction>(
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 3, field(CARD, "data", DATA)),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, field(DATA, "groupSet", GROUP_SET)),
                ImmutableInstruction21t(Opcode.IF_EQZ, 0, 14),
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 2, ImmutableTypeReference(SECTIONS)),
                invoke(Opcode.INVOKE_DIRECT, 2, 2, 0, 0, SECTIONS, "<init>", listOf(GROUP_SET), "V"),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("suggested")),
                invoke(Opcode.INVOKE_VIRTUAL, 2, 2, 0, 0, SECTIONS, "find", listOf(STRING), SECTION),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, field(DATA, "fallback", FALLBACK)),
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(SECTION)),
                invoke(Opcode.INVOKE_DIRECT, 2, 1, 0, 0, SECTION, "<init>", listOf(FALLBACK), "V"),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
            )
            val card = ImmutableClassDef(
                CARD, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null,
                listOf(publicField(CARD, "data", DATA)),
                listOf(method(CARD, "section", emptyList(), SECTION, PUBLIC_FINAL, 4, getter)),
            )
            val data = ImmutableClassDef(
                DATA, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null,
                listOf(
                    publicField(DATA, "groupSet", GROUP_SET), publicField(DATA, "fallback", FALLBACK),
                    publicField(DATA, "title", STRING), publicField(DATA, "subtitle", STRING),
                ),
                null,
            )
            val groupSet = ImmutableClassDef(
                GROUP_SET, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null,
                listOf(publicField(GROUP_SET, "active", STRING)), null,
            )
            val constructor = listOf<Instruction>(
                invoke(Opcode.INVOKE_DIRECT, 1, 1, 0, 0, "Ljava/lang/Object;", "<init>", emptyList(), "V"),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 2, field(GROUP_SET, "active", STRING)),
                ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, 1, field(SECTIONS, "activeId", STRING)),
                ImmutableInstruction22c(Opcode.IPUT_OBJECT, 2, 1, field(SECTIONS, "groupSet", GROUP_SET)),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            val sections = ImmutableClassDef(
                SECTIONS, sectionsFlags, "Ljava/lang/Object;", null, null, null,
                listOf(publicField(SECTIONS, "activeId", STRING, idFlags), publicField(SECTIONS, "groupSet", GROUP_SET)),
                listOf(
                    method(SECTIONS, "<init>", listOf(GROUP_SET), "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, 3, constructor),
                    method(SECTIONS, "find", listOf(STRING), SECTION, PUBLIC_FINAL, 3, listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2))),
                ),
            )
            val section = ImmutableClassDef(
                SECTION, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null,
                listOf(publicField(SECTION, "name", STRING), publicField(SECTION, "items", LIST), publicField(SECTION, "link", STRING)), null,
            )
            return listOf(binder, card, data, groupSet, sections, section, ExtensionDex.classDef(FEED_SUGGESTIONS))
        }
    }
}
