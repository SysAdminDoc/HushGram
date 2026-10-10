/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.share.REPOSTS_FEED_RESTORE
import app.morphe.patches.instagram.share.REPOSTS_FEED_UFI
import app.morphe.patches.instagram.share.REPOSTS_UFI_COUNT_ID
import app.morphe.patches.instagram.share.REPOSTS_UFI_ICON_ID
import app.morphe.patches.instagram.share.findFeedUfiSite
import app.morphe.patches.instagram.share.hideFeedUfi
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hook behind Download button on feed posts (#97): the binder Hide the Repost button hooks,
 * found the same way, handed the Save button, the post, the carousel's feed state and the activity.
 * The builds themselves are covered by DownloadVideoHookTest, which has their menu classes.
 */
class FeedDownloadButtonHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_BUTTON.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FEED_BUTTON is not in the extension: $declared", FEED_BUTTON.substringAfter("->") in declared)
    }

    @Test
    fun theBinderIsFoundByItsHolderStateFeedStateAndActivity() {
        val site = PatchContexts.of(classes()).findFeedButtonSite(PAGE)
        assertEquals(BINDER, site.type)
        assertEquals("bind", site.name)
        assertEquals(listOf(ITEM, STATE, HOLDER), site.parameters)
        assertEquals("the holder", 2, site.holder)
        assertEquals("the row state", 1, site.state)
        assertEquals("the feed state", 0, site.item)
        assertEquals("$HOLDER->A0I:$BOUNCY", site.save.toString())
        assertEquals("$STATE->post:$MEDIA", site.media.toString())
        assertEquals("$BINDER->activity:$ACTIVITY", site.activity.toString())
    }

    @Test
    fun theHookIsFirstInTheBinderAndHandsOverTheFourThings() {
        val context = PatchContexts.of(classes())
        context.addFeedDownloadButton(context.findFeedButtonSite(PAGE))
        val code = context.mutableClassDefBy(BINDER).methods.single { it.name == "bind" }.implementation!!.instructions.toList()
        assertHooked("stand-in", code)
    }

    /** Hide the Repost button's hook goes in either side of this one and each still lands once. */
    @Test
    fun bothOrdersWithHideTheRepostButtonKeepEachHook() {
        for (mineFirst in listOf(true, false)) {
            val context = PatchContexts.of(classes())
            val site = context.findFeedButtonSite(PAGE)
            if (mineFirst) context.addFeedDownloadButton(site)
            context.hideFeedUfi(context.findFeedUfiSite())
            if (!mineFirst) context.addFeedDownloadButton(context.findFeedButtonSite(PAGE))
            val code = context.mutableClassDefBy(BINDER).methods.single { it.name == "bind" }.implementation!!.instructions.toList()
            val order = if (mineFirst) "mine first" else "Hide the Repost button first"
            assertEquals("$order: one button hook", 1, code.count { it.names(FEED_BUTTON) })
            assertEquals("$order: one repost hide", 1, code.count { it.names(REPOSTS_FEED_UFI) })
            assertEquals("$order: one repost restore", 1, code.count { it.names(REPOSTS_FEED_RESTORE) })
            val at = code.indexOfFirst { it.names(FEED_BUTTON) }
            assertHooked(order, code.subList(at - 7, code.size))
        }
    }

    @Test
    fun aBuildWithoutTheBinderHolderStateOrActivityFailsThePatch() {
        for ((case, classes) in mapOf(
            "no binder" to classes().filter { it.type != BINDER },
            "no Save button in the holder" to classes(saveId = 0x7f0b0001),
            "two Save buttons in the holder" to classes(twoSaves = true),
            "no post in the row state" to classes(post = false),
            "a feed state that isn't a parameter" to classes(itemParameter = false),
            "two activities" to classes(activities = 2),
            "no activity" to classes(activities = 0),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).findFeedButtonSite(PAGE) }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Download any video: "))
        }
    }

    @Test
    fun aBinderWithTooFewLocalsFailsBeforeAnyChange() {
        val context = PatchContexts.of(classes(locals = 3))
        val site = context.findFeedButtonSite(PAGE)
        assertThrows(PatchException::class.java) { context.addFeedDownloadButton(site) }
    }

    /**
     * The binder opens with the four reads and the call: the holder's Save button, the post from
     * the row state, the feed state, then the activity from the binder itself, all through 16-bit
     * moves of the parameter registers. Nothing else is called.
     */
    private fun assertHooked(what: String, code: List<Instruction>) {
        val locals = 5
        val self = locals
        assertEquals("$what: the holder", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$what: from the holder parameter", self + 3, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the Save button", "$HOLDER->A0I:$BOUNCY", code[1].reference())
        assertEquals("$what: the row state", self + 2, (code[2] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the post", "$STATE->post:$MEDIA", code[3].reference())
        assertEquals("$what: the feed state", self + 1, (code[4] as TwoRegisterInstruction).registerB)
        assertEquals("$what: this", self, (code[5] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the activity", "$BINDER->activity:$ACTIVITY", code[6].reference())
        val call = code[7] as Instruction35c
        assertEquals("$what: the call", FEED_BUTTON, call.reference())
        assertEquals("$what: its four arguments", listOf(4, 0, 1, 2, 3), listOf(call.registerCount, call.registerC, call.registerD, call.registerE, call.registerF))
    }

    /**
     * The rows Home draws as components: Save's icon spec is found in the one method that loads
     * Save's id and the row's, the hook goes right after the spec's constructor with the list, the
     * spec and the row state in three free low registers, and the three bridges are written.
     */
    @Test
    fun theComponentRowIsFoundAndHookedRightAfterSavesIconSpec() {
        val context = PatchContexts.of(lithoClasses())
        val button = context.findFeedButtonSite(PAGE)
        val site = context.findLithoSaveSite(button, PAGE)
        assertEquals(BUILDER, site.type)
        assertEquals("Lfixture/Icon;", site.specType)
        assertEquals("Lfixture/Modifier;", site.modifierType)
        assertEquals("Lfixture/Icon;->modifier:Lfixture/Modifier;", site.modifier.toString())
        assertEquals("Lfixture/Icon;->scale:Landroid/widget/ImageView\$ScaleType;", site.scale.toString())
        assertEquals("Lfixture/Icon;->color:I", site.color.toString())
        assertEquals("Lfixture/Icon;->flag:Z", site.flag.toString())
        assertEquals("Lfixture/Style;->id(Lfixture/Modifier;I)Lfixture/Modifier;", site.id.toString())
        assertEquals("Lfixture/Style;->tap(Lfixture/Modifier;Lkotlin/jvm/functions/Function1;)Lfixture/Modifier;", site.click.toString())
        assertEquals("Lfixture/Style;->hold(Lfixture/Modifier;Lkotlin/jvm/functions/Function1;)Lfixture/Modifier;", site.longClick.toString())
        assertEquals("Lfixture/Style;->desc(Lfixture/Modifier;Ljava/lang/CharSequence;)Lfixture/Modifier;", site.description.toString())
        assertEquals("$STATE->post:$MEDIA", site.media.toString())
        assertEquals("$STATE->item:$ITEM", site.item.toString())
        assertEquals(LIST_REGISTER, site.list)
        assertEquals(SPEC_REGISTER, site.spec)
        assertEquals(STATE_REGISTER, site.state)

        context.addLithoDownloadButton(site)
        val code = context.mutableClassDefBy(BUILDER).methods.single { it.name == "A0o" }.implementation!!.instructions.toList()
        assertEquals("one component hook", 1, code.count { it.names(LITHO_BUTTON) })
        assertEquals("right after the spec's constructor", site.at + 3, code.indexOfFirst { it.names(LITHO_BUTTON) })
        assertEquals("Lfixture/Icon;-><init>(Landroid/widget/ImageView\$ScaleType;Lfixture/Modifier;Ljava/lang/Integer;IIZ)V", code[site.at - 1].reference())
        val moves = (0..2).map { code[site.at + it] as TwoRegisterInstruction }
        assertTrue(moves.all { it.opcode == Opcode.MOVE_OBJECT_FROM16 })
        assertEquals(listOf(LIST_REGISTER, SPEC_REGISTER, STATE_REGISTER), moves.map { it.registerB })
        val call = code[site.at + 3] as Instruction35c
        assertEquals(listOf(3, moves[0].registerA, moves[1].registerA, moves[2].registerA),
            listOf(call.registerCount, call.registerC, call.registerD, call.registerE))
        assertTrue("three low registers of their own", moves.map { it.registerA }.toSet().let { it.size == 3 && it.all { r -> r < 16 } })
        assertTrue("none of the three is a register the hook reads",
            moves.none { it.registerA in listOf(LIST_REGISTER, SPEC_REGISTER, STATE_REGISTER) })
        assertTrue("the list is added to right after", code[site.at + 4].opcode == Opcode.INVOKE_VIRTUAL && code[site.at + 4].reference().endsWith("->add(Ljava/lang/Object;)Z"))

        val bridges = context.mutableClassDefBy(FEED_BUTTON_TYPE).methods.associateBy { it.name }
        val post = bridges.getValue("lithoPost").implementation!!.instructions.toList()
        assertEquals(Opcode.CHECK_CAST, post[0].opcode)
        assertEquals("$STATE->post:$MEDIA", post[1].reference())
        val item = bridges.getValue("lithoItem").implementation!!.instructions.toList()
        assertEquals("$STATE->item:$ITEM", item[1].reference())
        val icon = bridges.getValue("lithoIcon").implementation!!.instructions.toList().map { (it as? ReferenceInstruction)?.reference?.toString() }
        for (needed in listOf(
            site.id.toString(), site.click.toString(), site.longClick.toString(),
            site.description.toString(), site.scale.toString(), site.color.toString(), site.flag.toString(), site.specConstructor.toString(),
        )) assertEquals("the icon bridge uses $needed once", 1, icon.count { it == needed })
        assertEquals("Save's spec cast once, a new one made once", 2, icon.count { it == "Lfixture/Icon;" })
        assertEquals("the icon never reads Save's modifier itself", 0, icon.count { it == site.modifier.toString() })
        val modifierType = "Lfixture/Modifier;"
        val idCall = bridges.getValue("lithoIcon").implementation!!.instructions.toList().first { (it as? ReferenceInstruction)?.reference?.toString() == site.id.toString() }
        assertEquals("the id goes on the modifier it was handed", 8 + 1, (idCall as Instruction35c).registerC)

        fun body(name: String) = bridges.getValue(name).implementation!!.instructions.toList()
        assertEquals(listOf("Lfixture/Icon;", site.modifier.toString(), null), body("lithoModifier").map { (it as? ReferenceInstruction)?.reference?.toString() })
        assertEquals("$modifierType->walk(Lkotlin/jvm/functions/Function1;)V", body("lithoParts")[2].reference())
        assertEquals("$modifierType->NONE:$modifierType", body("lithoEmpty")[0].reference())
        assertEquals("$modifierType->plus(Lfixture/Part;)$modifierType", body("lithoJoin")[2].reference())
        val saveOnly = body("lithoSaveOnly")
        assertEquals("Lfixture/Part;->kind()Lfixture/Kind;", saveOnly[1].reference())
        assertEquals("both of Save's kinds are told apart", listOf("Lfixture/BinderKind;", "Lfixture/PropKind;"),
            saveOnly.filter { it.opcode == Opcode.INSTANCE_OF }.map { it.reference() })
        assertEquals(listOf("Lfixture/BinderKind;", "Lfixture/PropKind;"), site.parts.saveKinds)
    }

    @Test
    fun aModifierThatCannotBeTakenApartFailsThePatch() {
        for ((case, classes) in mapOf(
            "an id style of one kind" to lithoClasses(idKinds = 1),
            "an id style of three kinds" to lithoClasses(idKinds = 3),
            "no walk over the parts" to lithoClasses(walk = false),
            "a part that is no interface" to lithoClasses(partInterface = false),
            "no empty modifier" to lithoClasses(empty = false),
            "no kind on the part" to lithoClasses(kindGetter = false),
        )) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(case, PatchException::class.java) {
                context.findLithoSaveSite(context.findFeedButtonSite(PAGE), PAGE)
            }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Download any video: "))
        }
    }

    @Test
    fun theComponentHookAndTheBinderHookLandTogetherAndTheHookGoesInOnce() {
        val context = PatchContexts.of(lithoClasses())
        val button = context.findFeedButtonSite(PAGE)
        val site = context.findLithoSaveSite(button, PAGE)
        context.addFeedDownloadButton(button)
        context.addLithoDownloadButton(site)
        val binder = context.mutableClassDefBy(BINDER).methods.single { it.name == "bind" }.implementation!!.instructions.toList()
        assertEquals(1, binder.count { it.names(FEED_BUTTON) })
        assertEquals("the binder hook is not the component hook", 0, binder.count { it.names(LITHO_BUTTON) })
        val builder = context.mutableClassDefBy(BUILDER).methods.single { it.name == "A0o" }.implementation!!.instructions.toList()
        assertEquals(0, builder.count { it.names(FEED_BUTTON) })
        assertEquals(1, builder.count { it.names(LITHO_BUTTON) })
    }

    @Test
    fun aBuildWhoseComponentRowIsNotWhatTheHookReadsFailsThePatch() {
        for ((case, classes) in mapOf(
            "no builder" to lithoClasses(rowId = 0x7f0b0001),
            "two builders" to lithoClasses(twoBuilders = true),
            "Save's id loaded twice" to lithoClasses(saveTwice = true),
            "no description style" to lithoClasses(description = false),
            "one function style" to lithoClasses(functionStyles = 1),
            "three function styles" to lithoClasses(functionStyles = 3),
            "no list add" to lithoClasses(add = false),
            "the list rewritten before the add" to lithoClasses(rewriteList = true),
            "the row state rewritten before the spec" to lithoClasses(rewriteState = true),
            "a spec that keeps no modifier" to lithoClasses(specKeepsModifier = false),
            "no feed state field" to lithoClasses(items = 0),
            "two feed state fields" to lithoClasses(items = 2),
        )) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(case, PatchException::class.java) {
                context.findLithoSaveSite(context.findFeedButtonSite(PAGE), PAGE)
            }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Download any video: "))
        }
    }

    @Test
    fun aComponentBuilderWithNoFreeLowRegistersFailsBeforeAnyChange() {
        val context = PatchContexts.of(lithoClasses(busy = true))
        val site = context.findLithoSaveSite(context.findFeedButtonSite(PAGE), PAGE)
        assertThrows(PatchException::class.java) { context.addLithoDownloadButton(site) }
        val code = context.mutableClassDefBy(BUILDER).methods.single { it.name == "A0o" }.implementation!!.instructions.toList()
        assertEquals("nothing was hooked", 0, code.count { it.names(LITHO_BUTTON) })
    }

    private fun Instruction.names(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun Instruction.reference() = (this as ReferenceInstruction).reference.toString()

    private companion object {
        const val BUILDER = "Lfixture/RowBuilder;"
        const val FEED_BUTTON_TYPE = "Lapp/hushgram/extension/instagram/download/FeedDownloadButton;"
        const val SCALE = "Landroid/widget/ImageView\$ScaleType;"
        const val LIST_REGISTER = 5
        const val SPEC_REGISTER = 6
        const val STATE_REGISTER = 3

        /** A method whose body is [smali], assembled the way the patches assemble what they add. */
        fun assembled(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, smali: String): ImmutableMethod =
            ImmutableMethod.of(
                ImmutableMethod(
                    owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null),
                ).toMutable().apply { addInstructions(0, smali) },
            )

        /**
         * The component builder as 450 has it, in the parts the hook reads: the row state narrowed
         * to, the list, Save's modifier given a description, an id, a tap and a long press, the
         * icon spec built from it, and the spec added to the list. The spec keeps the modifier,
         * the scale type, the drawable, the color and the flag. Every option breaks one thing.
         */
        fun lithoClasses(
            rowId: Int = 0x7f0b374c,
            twoBuilders: Boolean = false,
            saveTwice: Boolean = false,
            description: Boolean = true,
            functionStyles: Int = 2,
            add: Boolean = true,
            rewriteList: Boolean = false,
            rewriteState: Boolean = false,
            specKeepsModifier: Boolean = true,
            items: Int = 1,
            busy: Boolean = false,
            idKinds: Int = 2,
            walk: Boolean = true,
            partInterface: Boolean = true,
            empty: Boolean = true,
            kindGetter: Boolean = true,
        ): List<ClassDef> {
            val modifier = "Lfixture/Modifier;"
            val function = "Lkotlin/jvm/functions/Function1;"
            val builderBody = buildString {
                appendLine("check-cast v$STATE_REGISTER, $STATE")
                if (rewriteState) appendLine("const/4 v$STATE_REGISTER, 0x0")
                appendLine("new-instance v$LIST_REGISTER, Ljava/util/ArrayList;")
                appendLine("invoke-direct { v$LIST_REGISTER }, Ljava/util/ArrayList;-><init>()V")
                appendLine("const v1, $rowId")
                appendLine("sget-object v2, $modifier->NONE:$modifier")
                if (description) {
                    appendLine("const-string v4, \"Save\"")
                    appendLine("invoke-static { v2, v4 }, Lfixture/Style;->desc(${modifier}Ljava/lang/CharSequence;)$modifier")
                    appendLine("move-result-object v2")
                }
                appendLine("const v1, 0x7f0b370e")
                appendLine("invoke-static { v2, v1 }, Lfixture/Style;->id(${modifier}I)$modifier")
                appendLine("move-result-object v2")
                if (saveTwice) appendLine("const v0, 0x7f0b370e")
                appendLine("const/4 v9, 0x0")
                for (style in listOf("tap", "hold", "extra").take(functionStyles)) {
                    appendLine("invoke-static { v2, v9 }, Lfixture/Style;->$style($modifier$function)$modifier")
                    appendLine("move-result-object v2")
                }
                appendLine("new-instance v$SPEC_REGISTER, Lfixture/Icon;")
                appendLine("sget-object v7, $SCALE->CENTER:$SCALE")
                appendLine("move-object v8, v2")
                appendLine("const/4 v10, 0x0")
                appendLine("const/4 v11, 0x0")
                appendLine("const/4 v12, 0x0")
                appendLine("invoke-direct/range { v$SPEC_REGISTER .. v12 }, Lfixture/Icon;-><init>($SCALE${modifier}Ljava/lang/Integer;IIZ)V")
                if (rewriteList) appendLine("new-instance v$LIST_REGISTER, Ljava/util/ArrayList;")
                if (add) appendLine("invoke-virtual { v$LIST_REGISTER, v$SPEC_REGISTER }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z")
                if (busy) appendLine("invoke-static/range { v0 .. v13 }, Lfixture/Sink;->all()V")
                appendLine("return-object v$SPEC_REGISTER")
            }
            fun builder(type: String) = classOf(
                type, emptyList(),
                listOf(assembled(type, "A0o", listOf("Lfixture/Composer;"), "Lfixture/Node;", AccessFlags.PUBLIC.value, 16, builderBody)),
            )
            val iconConstructor = { _: Boolean ->
                assembled(
                    "Lfixture/Icon;", "<init>",
                    listOf(SCALE, modifier, "Ljava/lang/Integer;", "I", "I", "Z"), "V",
                    AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, 7,
                    buildString {
                        appendLine("invoke-direct { p0 }, Ljava/lang/Object;-><init>()V")
                        appendLine("iput-object p1, p0, Lfixture/Icon;->scale:$SCALE")
                        if (specKeepsModifier) appendLine("iput-object p2, p0, Lfixture/Icon;->modifier:$modifier")
                        appendLine("iput-object p3, p0, Lfixture/Icon;->tint:Ljava/lang/Integer;")
                        appendLine("iput p4, p0, Lfixture/Icon;->drawable:I")
                        appendLine("iput p5, p0, Lfixture/Icon;->color:I")
                        appendLine("iput-boolean p6, p0, Lfixture/Icon;->flag:Z")
                        appendLine("return-void")
                    },
                )
            }
            val icon = classOf(
                "Lfixture/Icon;",
                listOf(
                    field("Lfixture/Icon;", "scale", SCALE), field("Lfixture/Icon;", "modifier", modifier),
                    field("Lfixture/Icon;", "tint", "Ljava/lang/Integer;"), field("Lfixture/Icon;", "drawable", "I"),
                    field("Lfixture/Icon;", "color", "I"), field("Lfixture/Icon;", "flag", "Z"),
                ),
                listOf(iconConstructor(false)),
            )
            val state = classOf(
                STATE,
                listOf(field(STATE, "post", MEDIA)) + List(items) { field(STATE, if (it == 0) "item" else "item$it", ITEM) },
                emptyList(),
            )
            val base = classes().filter { it.type != STATE }
            return base + state + icon + builder(BUILDER) + (if (twoBuilders) listOf(builder("Lfixture/OtherBuilder;")) else emptyList()) +
                modifierClasses(idKinds, walk, partInterface, empty, kindGetter) + ExtensionDex.classDef(FEED_BUTTON_TYPE)
        }

        /**
         * The modifier as 450 has it, in the parts the bridges use: a chain of parts with a walk, a
         * join and an empty one; the part interface with its kind; an item made in a binder kind and
         * a common prop kind; and the id style, which makes its part in both (by a server flag).
         */
        fun modifierClasses(idKinds: Int, walk: Boolean, partInterface: Boolean, empty: Boolean, kindGetter: Boolean): List<ClassDef> {
            val modifier = "Lfixture/Modifier;"
            val part = "Lfixture/Part;"
            val kinds = listOf("Lfixture/BinderKind;", "Lfixture/PropKind;", "Lfixture/OtherKind;")
            val modifierClass = classOf(
                modifier,
                listOf(field(modifier, "prev", modifier), field(modifier, "part", part)) +
                    if (empty) listOf(ImmutableField(modifier, "NONE", modifier, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null)) else emptyList(),
                listOfNotNull(
                    if (walk) assembled(modifier, "walk", listOf("Lkotlin/jvm/functions/Function1;"), "V", AccessFlags.PUBLIC.value, 2, "return-void") else null,
                    assembled(modifier, "plus", listOf(part), modifier, AccessFlags.PUBLIC.value, 2, "return-object p0"),
                    assembled(modifier, "then", listOf(modifier), modifier, AccessFlags.PUBLIC.value, 2, "return-object p0"),
                ),
            )
            val partFlags = AccessFlags.PUBLIC.value or (if (partInterface) AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value else 0)
            val partClass = ImmutableClassDef(
                part, partFlags, "Ljava/lang/Object;", null, null, null, emptyList(),
                listOfNotNull(
                    if (kindGetter) ImmutableMethod(part, "kind", emptyList(), "Lfixture/Kind;", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null) else null,
                    ImmutableMethod(part, "value", emptyList(), "Ljava/lang/Object;", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null),
                ),
            )
            val kindClasses = kinds.map { kind ->
                ImmutableClassDef(kind, AccessFlags.PUBLIC.value, "Ljava/lang/Enum;", listOf("Lfixture/Kind;"), null, null, emptyList(), emptyList())
            }
            val item = ImmutableClassDef("Lfixture/Item;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", listOf(part), null, null, emptyList(), emptyList())
            val style = classOf(
                "Lfixture/Style;", emptyList(),
                listOf(
                    assembled(
                        "Lfixture/Style;", "id", listOf(modifier, "I"), modifier, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, 4,
                        buildString {
                            for (kind in kinds.take(idKinds)) {
                                appendLine("new-instance v0, Lfixture/Item;")
                                appendLine("sget-object v1, $kind->ID:$kind")
                                appendLine("invoke-direct { v0, v1, v1 }, Lfixture/Item;-><init>(${kind}Ljava/lang/Object;)V")
                            }
                            appendLine("return-object p0")
                        },
                    ),
                ),
            )
            return listOf(modifierClass, partClass, item, style) + kindClasses
        }

        const val BINDER = "Lfixture/UfiRow;"
        const val HOLDER = "Lfixture/UfiHolder;"
        const val STATE = "Lfixture/RowState;"
        const val ITEM = "Lfixture/ItemState;"
        const val ACTIVITY = "Landroid/app/Activity;"
        const val BOUNCY = "Lcom/instagram/ui/widget/bouncyufibutton/IgBouncyUfiButtonImageView;"
        const val TEXT = "Lcom/instagram/common/ui/base/IgTextView;"
        val PAGE = PageIndex(
            ImmutableFieldReference(ITEM, "page", "I"),
            ImmutableFieldReference(STATE, "item", ITEM),
            ImmutableFieldReference("Lfixture/Menu;", "item", ITEM),
        )
        val REQUIRE_VIEW = ImmutableMethodReference("Landroid/view/View;", "requireViewById", listOf("I"), "Landroid/view/View;")

        fun field(owner: String, name: String, type: String) =
            ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null)

        /** `const v0, id` then the view lookup on [view], checked to [type] and stored to [owner]'s [name] through [target]. */
        fun lookup(id: Int, view: Int, type: String, target: Int, owner: String, name: String) = listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, id),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, view, 0, 0, 0, 0, REQUIRE_VIEW),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(type)),
            ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, target, ImmutableFieldReference(owner, name, type)),
        )

        fun classOf(type: String, fields: List<ImmutableField>, methods: List<ImmutableMethod>) = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, methods,
        )

        /**
         * The binder, its holder, its row state and its feed state, as 450 has them. The binder keeps
         * [locals] locals, then `this` and (feed state, row state, holder) as the parameters.
         */
        fun classes(
            saveId: Int = 0x7f0b370e,
            twoSaves: Boolean = false,
            post: Boolean = true,
            itemParameter: Boolean = true,
            activities: Int = 1,
            locals: Int = 5,
        ): List<ClassDef> {
            val constructor = ImmutableMethod(
                HOLDER, "<init>", listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(
                    4,
                    lookup(saveId, 3, BOUNCY, 2, HOLDER, "A0I") +
                        (if (twoSaves) lookup(saveId, 3, BOUNCY, 2, HOLDER, "A0J") else emptyList()) +
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    null, null,
                ),
            )
            val holder = classOf(
                HOLDER,
                listOf(field(HOLDER, "A08", BOUNCY), field(HOLDER, "A05", TEXT), field(HOLDER, "A0H", BOUNCY),
                    field(HOLDER, "A0I", BOUNCY), field(HOLDER, "A0J", BOUNCY)),
                listOf(constructor),
            )
            val rowState = classOf(STATE, if (post) listOf(field(STATE, "post", MEDIA), field(STATE, "item", ITEM)) else listOf(field(STATE, "item", ITEM)), emptyList())
            val item = classOf(ITEM, listOf(field(ITEM, "page", "I")), emptyList())
            val parameters = if (itemParameter) listOf(ITEM, STATE, HOLDER) else listOf("Lfixture/Other;", STATE, HOLDER)
            val bind = ImmutableMethod(
                BINDER, "bind", parameters.map { ImmutableMethodParameter(it, null, null) }, "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(
                    locals + 1 + 3,
                    listOf(ImmutableInstruction12x(Opcode.MOVE_OBJECT, 4, 8)) +
                        lookup(REPOSTS_UFI_ICON_ID, 1, BOUNCY, 4, HOLDER, "A08") +
                        lookup(REPOSTS_UFI_COUNT_ID, 1, TEXT, 4, HOLDER, "A05") +
                        ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 4, ImmutableFieldReference(HOLDER, "A0H", BOUNCY)) +
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    null, null,
                ),
            )
            val binder = classOf(BINDER, List(activities) { field(BINDER, if (it == 0) "activity" else "other$it", ACTIVITY) }, listOf(bind))
            return listOf(binder, holder, rowState, item)
        }
    }
}
