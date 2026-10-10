/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.profile

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.IMAGE_URL
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.PROFILE_PICTURE_INFO
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.profilePictureBridges
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
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

/**
 * Save profile picture: right before each of the profile menu's two sheets shows, the extension
 * gets the sheet, the account and the context, the row stub reaches the sheet's own adder, and
 * anything the patch can't pick out fails it before a change.
 */
class SaveProfilePictureHookTest {
    @Test
    fun theHookAndStubAreInTheExtension() {
        val declared = ExtensionDex.classDef(PROFILE_PICTURE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (method in listOf(OFFER_PICTURE.substringAfter("->"), OFFER_POPUP.substringAfter("->"), POPUP_ITEM_FIELDS.substringAfter("->"),
            "$ADD_ROW_STUB(${ADD_ROW_PARAMETERS.joinToString("")})Z")) {
            assertTrue("$method is not in the extension: $declared", method in declared)
        }
        val bridges = ExtensionDex.classDef(INSTAGRAM_MEDIA).methods.map { it.name }
        for (bridge in PICTURE_BRIDGES) assertTrue("$bridge is not in the extension", bridge in bridges)
    }

    @Test
    fun eachMenuOffersTheRowBeforeItShows() {
        val context = context()
        val menus = context.findProfileMenus()
        assertEquals(PROFILE_MENUS, menus.sites.map { it.name })
        context.applyProfileMenus(menus)

        val code = context.method(HOST, "open").instructions()
        val offers = code.indices.filter { code[it].referenceText() == OFFER_PICTURE }
        assertEquals("one offer per menu", 2, offers.size)
        for ((at, helper) in offers.zip(listOf(BOTTOM, NEWER))) {
            val user = code[at - 2] as TwoRegisterInstruction
            val shown = code[at - 1] as TwoRegisterInstruction
            assertEquals("$helper: the account", "$helper->user:$USER", code[at - 2].referenceText())
            assertEquals("$helper: the context the sheet shows with", "$helper->context:Landroid/content/Context;", code[at - 1].referenceText())
            assertEquals("$helper: both off the helper", user.registerB, shown.registerB)
            val call = code[at] as FiveRegisterInstruction
            assertEquals("$helper: the sheet, then what was read", listOf(call.registerC, user.registerA, shown.registerA),
                listOf(code[at + 2].sheetRegister(), call.registerD, call.registerE))
            assertEquals("$helper: right before the shower is made", Opcode.NEW_INSTANCE, code[at + 1].opcode)
            assertEquals(SHOWER, (code[at + 1].reference() as TypeReference).type)
        }

        val popup = code.indices.single { code[it].referenceText() == OFFER_POPUP }
        val popupUser = code[popup - 1] as TwoRegisterInstruction
        val offer = code[popup] as FiveRegisterInstruction
        assertEquals("the account, off the newer helper", "$NEWER->user:$USER", code[popup - 1].referenceText())
        assertEquals("the list, the account and the context the popup is built with", listOf(6, popupUser.registerA, 8),
            listOf(offer.registerC, offer.registerD, offer.registerE))
        assertEquals("the owner is the newer helper", 0, popupUser.registerB)
        assertEquals("right before the popup takes its list", "$POPUP->take(Ljava/util/List;)V", code[popup + 1].referenceText())

        val stub = context.method(PROFILE_PICTURE, ADD_ROW_STUB).instructions()
        val adds = stub.single { it.opcode == Opcode.INVOKE_VIRTUAL_RANGE } as RegisterRangeInstruction
        assertEquals("$SHEET->add(${ROW_ADDER_PARAMETERS.joinToString("")})V", (adds as Instruction).referenceText())
        assertEquals(6, adds.registerCount)
        assertEquals("no icon, as the newer menu's own rows", -1, stub.literal(adds.startRegister + 4))
        assertEquals("the plain text color, not Report's red", 0, stub.literal(adds.startRegister + 5))
        assertEquals("the stub says the row went in", Opcode.RETURN, stub.last().opcode)

        // The fixture's item stores its eleventh and sixteenth arguments in each other's field, as 450 does.
        val names = (0 until 24).map { "a$it" }.toMutableList().apply { this[10] = "a15"; this[15] = "a10" }
        assertEquals("the item's fields in argument order", "fixture.PopupItem|" + names.joinToString(","), layout(context))
    }

    @Test
    fun anItemArgumentNotStoredFailsThePatch() = refuses("doesn't store argument(s) 3") {
        context(itemStores = { at -> at != 3 }).findProfileMenus()
    }

    @Test
    fun anItemArgumentStoredInAFieldOfAnotherTypeFailsThePatch() = refuses("doesn't have argument 9's type") {
        context(itemFieldType = { at -> if (at == 9) "Ljava/lang/Object;" else null }).findProfileMenus()
    }

    @Test
    fun anItemConstructorThatBranchesFailsThePatch() = refuses("constructor branches") {
        context(itemBranches = true).findProfileMenus()
    }

    @Test
    fun aHelperNamedOtherwiseFailsThePatch() = refuses("isn't the helper named \"UserOptionsOverflowHelper\"") {
        context(newer = helper(NEWER, "SomethingElseOverflowHelper")).findProfileMenus()
    }

    @Test
    fun aHelperWithTwoAccountsFailsThePatch() = refuses("expected one account field") {
        context(newer = helper(NEWER, PROFILE_MENUS[1], users = 2)).findProfileMenus()
    }

    @Test
    fun aSheetWithTwoAddersFailsThePatch() = refuses("expected one adder of a plain row") {
        context(sheet = sheet(adders = 2)).findProfileMenus()
    }

    @Test
    fun aMethodWithOneMenuFailsThePatch() = refuses("expected one method loading") {
        context(host = host(newerName = "SomeOtherHelper")).findProfileMenus()
    }

    @Test
    fun aSheetShownWithoutItsHelpersContextFailsThePatch() = refuses("isn't shown with a context read off its helper") {
        context(host = host(readsContext = false)).findProfileMenus()
    }

    @Test
    fun aJumpToTheShowerFailsThePatch() = refuses("something jumps to") {
        context(host = host(jumpToShower = true)).findProfileMenus()
    }

    @Test
    fun aPopupItemOfAnotherSizeFailsThePatch() = refuses("expected one item class built with 24 arguments") {
        context(popupItemArguments = 23).findProfileMenus()
    }

    @Test
    fun twoPopupsFailThePatch() = refuses("expected one PopupWindow") {
        context(host = host(twoPopups = true)).findProfileMenus()
    }

    @Test
    fun aPopupNotHandedItsListRightAwayFailsThePatch() = refuses("isn't handed its list right after it's built") {
        context(host = host(popupGap = true)).findProfileMenus()
    }

    @Test
    fun aPopupWhoseContextIsNotTheHelpersFailsThePatch() = refuses("context isn't read off the newer helper") {
        context(host = host(popupContextFromElsewhere = true)).findProfileMenus()
    }

    @Test
    fun aSheetShownTwiceFailsThePatch() = refuses("shown once, found 2") {
        context(host = host(showTwice = true)).findProfileMenus()
    }

    /**
     * In each of the seven 450 builds: both menus offer the row, the stub reaches the sheet's adder,
     * the picture's bridges are written, and the pop-up item's fields are handed over in its
     * constructor's argument order, each of that argument's type.
     */
    @Test
    fun eachDeclaredBuildOffersTheRow() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("the declared build and the six others", 7, bundles.size)
        var checked = 0
        run {
            for (bundle in bundles) {
                val kept = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.type in setOf(USER, PROFILE_PICTURE_INFO, IMAGE_URL) || classDef.originalName() in PROFILE_MENUS) {
                            kept += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                val hosts = FixtureDex.classesHolding(bundle, PROFILE_MENUS[0]).filter { host ->
                    host.methods.any { method -> PROFILE_MENUS.all { name -> method.loads(name) } }
                }
                val made = hosts.flatMap { it.methods }.flatMap { it.instructions() }
                    .filter { it.opcode == Opcode.NEW_INSTANCE }.map { (it.reference() as TypeReference).type }.toSet()
                val built = FixtureDex.classes(bundle, made).values
                // The item's click callback is an interface nothing in the host makes.
                val taken = built.flatMap { it.methods }.filter { it.name == "<init>" }.flatMap { it.parameterTypes.map(Any::toString) }.toSet()
                kept += hosts + built + FixtureDex.classes(bundle, taken - made).values
                val context = PatchContexts.of(
                    kept.distinctBy { it.type } + ExtensionDex.classDef(PROFILE_PICTURE) + ExtensionDex.classDef(INSTAGRAM_MEDIA),
                )

                val menus = context.findProfileMenus()
                val bridges = context.profilePictureBridges(PROFILE_PICTURE_PATCH)
                context.applyProfileMenus(menus)
                bridges()

                val host = context.mutableClassDefBy(menus.method.definingClass).methods.single {
                    it.name == menus.method.name && it.parameterTypes == menus.method.parameterTypes
                }
                assertEquals("${bundle.name}: one offer per menu", 2, host.instructions().count { it.referenceText() == OFFER_PICTURE })
                assertEquals("${bundle.name}: one offer for the pop-up list", 1, host.instructions().count { it.referenceText() == OFFER_POPUP })
                val stub = context.method(PROFILE_PICTURE, ADD_ROW_STUB).instructions()
                val adds = stub.single { it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
                assertEquals("${bundle.name}: the stub's adder", menus.sheetType,
                    (adds.reference() as com.android.tools.smali.dexlib2.iface.reference.MethodReference).definingClass)
                assertEquals("${bundle.name}: the plain text color, not Report's red", 0,
                    stub.literal((adds as RegisterRangeInstruction).startRegister + 5))
                for (bridge in PICTURE_BRIDGES) {
                    assertEquals("${bundle.name}: $bridge", Opcode.CHECK_CAST, context.method(INSTAGRAM_MEDIA, bridge).instructions().first().opcode)
                }
                val (item, fields) = layout(context).split("|")
                val itemClass = context.classDefBy("L${item.replace('.', '/')};")
                val arguments = itemClass.methods.single { it.name == "<init>" }.parameterTypes.map(Any::toString)
                val names = fields.split(",")
                assertEquals("${bundle.name}: one field per argument", arguments.size, names.toSet().size)
                names.forEachIndexed { at, name ->
                    assertEquals("${bundle.name}: $name holds argument $at", arguments[at], itemClass.fields.single { it.name == name }.type)
                }
                checked++
            }
        }
        assertEquals("every build checked", 7, checked)
    }

    /** With the bio's getter gone, every other bridge goes in and the bio's keeps answering null, so only Copy bio is left out. */
    @Test
    fun aBioThatCantBeToldLeavesOnlyCopyBioOut() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val models = FixtureDex.classes(bundle, setOf(USER, PROFILE_PICTURE_INFO, IMAGE_URL))
                val key = "biography".hashCode()
                val user = models.getValue(USER).let { user ->
                    ImmutableClassDef(
                        user.type, user.accessFlags, user.superclass, user.interfaces, user.sourceFile, user.annotations, user.fields,
                        user.methods.filterNot { method ->
                            method.parameterTypes.isEmpty() && method.returnType == "Ljava/lang/String;" &&
                                method.instructions().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == key }
                        },
                    )
                }
                val context = PatchContexts.of(models.values.filter { it.type != USER } + user + ExtensionDex.classDef(INSTAGRAM_MEDIA))
                val stubBefore = context.method(INSTAGRAM_MEDIA, "biography").instructions().map { it.opcode }

                context.profilePictureBridges(PROFILE_PICTURE_PATCH)()

                for (bridge in PICTURE_BRIDGES - "biography") {
                    assertEquals("${bundle.name}: $bridge", Opcode.CHECK_CAST, context.method(INSTAGRAM_MEDIA, bridge).instructions().first().opcode)
                }
                assertEquals("${bundle.name}: the bio's stub", stubBefore, context.method(INSTAGRAM_MEDIA, "biography").instructions().map { it.opcode })
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun refuses(reason: String, patch: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { patch() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method = mutableClassDefBy(type).methods.single { it.name == name }

    /** What the patched [POPUP_ITEM_FIELDS] stub answers. */
    private fun layout(context: BytecodePatchContext): String {
        val stub = context.method(PROFILE_PICTURE, POPUP_ITEM_FIELDS.substringAfter("->").substringBefore("(")).instructions()
        assertEquals(listOf(Opcode.CONST_STRING, Opcode.RETURN_OBJECT), stub.map { it.opcode })
        return (stub[0].reference() as com.android.tools.smali.dexlib2.iface.reference.StringReference).string
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Method.loads(string: String) = instructions().any {
        ((it.reference()) as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string == string
    }
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
    private fun Instruction.referenceText(): String? = reference()?.toString()

    /** The sheet register a shower's constructor, two instructions on, is handed. */
    private fun Instruction.sheetRegister() = (this as FiveRegisterInstruction).registerD

    /** The value the last literal load into [register] gives it. */
    private fun List<Instruction>.literal(register: Int): Int =
        last { it is NarrowLiteralInstruction && (it as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA == register }
            .let { (it as NarrowLiteralInstruction).narrowLiteral }

    private companion object {
        const val HOST = "Lfixture/ProfileFragment;"
        const val SHEET = "Lfixture/OptionsSheet;"
        const val SHOWER = "Lfixture/SheetShower;"
        const val SESSION = "Lfixture/Session;"
        const val BOTTOM = "Lfixture/BottomOptions;"
        const val NEWER = "Lfixture/Options;"
        const val CONTEXT = "Landroid/content/Context;"
        const val POPUP = "Lfixture/Popup;"
        const val ITEM = "Lfixture/PopupItem;"
        const val CALLBACK = "Lfixture/Click;"
        val PICTURE_BRIDGES = listOf(
            "profilePicture", "fullSizeProfilePicture", "username", "biography", "profilePictureUrl", "profilePictureWidth",
            "profilePictureHeight", "candidateUrl", "candidateWidth", "candidateHeight",
        )
        const val PUBLIC_FINAL = 0x0011

        fun context(
            host: ClassDef = host(),
            sheet: ClassDef = sheet(),
            newer: ClassDef = helper(NEWER, PROFILE_MENUS[1]),
            popupItemArguments: Int = 24,
            itemStores: (Int) -> Boolean = { true },
            itemFieldType: (Int) -> String? = { null },
            itemBranches: Boolean = false,
        ) = PatchContexts.of(
            listOf(host, sheet, helper(BOTTOM, PROFILE_MENUS[0]), newer, ExtensionDex.classDef(PROFILE_PICTURE)) +
                popup(popupItemArguments, itemStores, itemFieldType, itemBranches),
        )

        /**
         * Shaped like 450's X.03W6, X.0juk and X.0qhV: the pop-up list, its item and the item's click
         * callback. Like X.0juk, the item stores its eleventh and sixteenth arguments in each other's
         * field by name.
         */
        fun popup(
            arguments: Int,
            stores: (Int) -> Boolean = { true },
            fieldType: (Int) -> String? = { null },
            branches: Boolean = false,
        ): List<ClassDef> {
            val parameters = (0 until arguments).map {
                when (it) {
                    4 -> CALLBACK
                    in 9..15 -> "Ljava/lang/Integer;"
                    16, 17 -> "Ljava/lang/String;"
                    in 18..23 -> "Z"
                    else -> "Ljava/lang/Object;"
                }
            }
            val constructor = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value
            fun swapped(at: Int) = when (at) { 10 -> 15; 15 -> 10; else -> at }
            val storesCode = parameters.indices.filter(stores).joinToString("\n") { at ->
                val (move, put) = if (parameters[at] == "Z") "move/from16" to "iput-boolean" else "move-object/from16" to "iput-object"
                // A store takes registers up to v15 only, so each argument goes through v0 first, as many of X.0juk's do.
                "$move v0, p${at + 1}\n$put v0, p0, $ITEM->a${swapped(at)}:${parameters[at]}"
            }
            val body = (if (branches) "if-eqz p1, :done\n" else "") +
                "invoke-direct { p0 }, Ljava/lang/Object;-><init>()V\n$storesCode\n:done\nreturn-void"
            val item = ImmutableClassDef(
                ITEM, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null,
                parameters.indices.map { at ->
                    ImmutableField(ITEM, "a$at", fieldType(at) ?: parameters[swapped(at)], AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null, null, null)
                },
                listOf(method(ITEM, "<init>", parameters, parameters.size + 2, body, constructor)),
            )
            val callback = ImmutableClassDef(
                CALLBACK, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
                "Ljava/lang/Object;", null, null, null, null, null,
            )
            val window = ImmutableClassDef(
                POPUP, AccessFlags.PUBLIC.value, "Landroid/widget/PopupWindow;", null, null, null, null,
                listOf(
                    method(POPUP, "<init>", listOf(CONTEXT, SESSION, "Ljava/lang/Integer;", "Z"), 6, "return-void", constructor),
                    method(POPUP, "take", listOf("Ljava/util/List;"), 3, "const/4 v0, 0x0\ncheck-cast v0, $ITEM\nreturn-void", PUBLIC_FINAL),
                    method(POPUP, "other", listOf("Ljava/util/List;"), 3, "return-void", PUBLIC_FINAL),
                ),
            )
            return listOf(item, callback, window)
        }

        /** Shaped like 450's X.0NFq and X.0NFr: the kept name, the account and the context the sheet shows with. */
        fun helper(type: String, name: String, users: Int = 1): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null,
            listOf(ImmutableField(type, "__redex_internal_original_name", "Ljava/lang/String;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, ImmutableStringEncodedValue(name), null, null)) +
                (1..users).map { ImmutableField(type, if (it == 1) "user" else "user$it", USER, AccessFlags.PUBLIC.value, null, null, null) } +
                ImmutableField(type, "context", CONTEXT, AccessFlags.PUBLIC.value, null, null, null),
            null,
        )

        /** Shaped like 450's X.0G29: built with a session and a name, with one adder of a plain row. */
        fun sheet(adders: Int = 1): ClassDef = ImmutableClassDef(
            SHEET, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(method(SHEET, "<init>", listOf(SESSION, "Ljava/lang/String;"), 3, "return-void", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value)) +
                (1..adders).map { method(SHEET, if (it == 1) "add" else "add$it", ROW_ADDER_PARAMETERS, 6, "return-void", PUBLIC_FINAL) },
        )

        /** Shaped like 450's X.09D9.A02: the bottom sheet's menu, or the newer one, each built and shown. */
        fun host(
            newerName: String = PROFILE_MENUS[1],
            readsContext: Boolean = true,
            jumpToShower: Boolean = false,
            showTwice: Boolean = false,
            twoPopups: Boolean = false,
            popupGap: Boolean = false,
            popupContextFromElsewhere: Boolean = false,
        ): ClassDef {
            val popupContext = "iget-object v8, v0, " + (if (popupContextFromElsewhere) BOTTOM else NEWER) + "->context:$CONTEXT"
            val handOver = if (popupGap) "move-object v7, v6\n invoke-virtual { v7, v6 }, $POPUP->take(Ljava/util/List;)V"
                else "invoke-virtual { v2, v6 }, $POPUP->take(Ljava/util/List;)V"
            val second = if (twoPopups) "new-instance v9, $POPUP\n invoke-direct { v9, v8, v5, v1, v3 }, $POPUP-><init>($CONTEXT${SESSION}Ljava/lang/Integer;Z)V" else ""
            val adds = "invoke-virtual/range { v20 .. v25 }, $SHEET->add(${ROW_ADDER_PARAMETERS.joinToString("")})V"
            val newerContext = if (readsContext) "iget-object v1, v0, $NEWER->context:$CONTEXT" else "const/4 v1, 0x0"
            val body = """
                const/4 v5, 0x0
                if-eqz p1, :newer
                new-instance v0, $BOTTOM
                invoke-direct { v0 }, $BOTTOM-><init>()V
                const-string v1, "${PROFILE_MENUS[0]}"
                new-instance v4, $SHEET
                invoke-direct { v4, v5, v1 }, $SHEET-><init>($SESSION Ljava/lang/String;)V
                move-object/from16 v20, v4
                iget-object v2, v0, $BOTTOM->context:$CONTEXT
                move-object/from16 v21, v2
                const/4 v3, 0x0
                move-object/from16 v22, v3
                const-string v3, "Report"
                move-object/from16 v23, v3
                const/4 v3, -0x1
                move/from16 v24, v3
                const/4 v3, 0x1
                move/from16 v25, v3
                $adds
                ${if (jumpToShower) "if-eqz v3, :shower" else "nop"}
                :shower
                new-instance v1, $SHOWER
                invoke-direct { v1, v4 }, $SHOWER-><init>($SHEET)V
                iget-object v0, v0, $BOTTOM->context:$CONTEXT
                invoke-virtual { v1, v0 }, $SHOWER->show($CONTEXT)V
                return-void
                :newer
                if-nez v5, :popup
                new-instance v0, $NEWER
                invoke-direct { v0 }, $NEWER-><init>()V
                const-string v1, "$newerName"
                new-instance v7, $SHEET
                invoke-direct { v7, v5, v1 }, $SHEET-><init>($SESSION Ljava/lang/String;)V
                new-instance v2, $SHOWER
                invoke-direct { v2, v7 }, $SHOWER-><init>($SHEET)V
                $newerContext
                invoke-virtual { v2, v1 }, $SHOWER->show($CONTEXT)V
                ${if (showTwice) "new-instance v2, $SHOWER\n invoke-direct { v2, v7 }, $SHOWER-><init>($SHEET)V" else ""}
                return-void
                :popup
                new-instance v0, $NEWER
                invoke-direct { v0 }, $NEWER-><init>()V
                $popupContext
                new-instance v6, Ljava/util/ArrayList;
                invoke-direct { v6 }, Ljava/util/ArrayList;-><init>()V
                const/4 v3, 0x0
                const/4 v1, 0x0
                new-instance v2, $POPUP
                invoke-direct { v2, v8, v5, v1, v3 }, $POPUP-><init>($CONTEXT${SESSION}Ljava/lang/Integer;Z)V
                $handOver
                $second
                return-void
            """
            return ImmutableClassDef(
                HOST, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(method(HOST, "open", listOf("Landroid/view/View;"), 28, body, PUBLIC_FINAL)),
            )
        }

        fun method(type: String, name: String, parameters: List<String>, registers: Int, body: String, access: Int): Method {
            val mutable = MutableMethod(
                ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", access, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null)),
            ).apply { addInstructionsWithLabels(0, body.trimIndent()) }
            return ImmutableMethod.of(mutable)
        }
    }
}
