/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.voice

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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Download voice messages: the menu builder hands the answer to whether a message can be saved to
 * the extension, the saver's entry asks the extension first, both bridges read what Instagram's
 * own player and parser read, and anything the patch can't pick out fails it before a change.
 */
class DownloadVoiceMessagesHookTest {
    @Test
    fun theHooksAndBridgesAreInTheExtension() {
        val declared = ExtensionDex.classDef(VOICE_MESSAGE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (method in listOf(OFFER_VOICE, SAVE_VOICE, "$VOICE_MESSAGE->$AUDIO_BRIDGE(Ljava/lang/Object;)Ljava/lang/String;",
            "$VOICE_MESSAGE->$VIEW_MODE_BRIDGE(Ljava/lang/Object;)Ljava/lang/String;")) {
            assertTrue("$method is not in the extension: $declared", method in declared)
        }
    }

    @Test
    fun theBuilderAsksTheExtensionWithTheAnswerAndTheMessage() {
        val context = context()
        context.applyVoiceMessages(context.findVoiceMessages())

        val code = context.method(BUILDER, "build").instructions()
        val at = code.indexOfFirst { it.referenceText() == OFFER_VOICE }
        val ask = code[at - 2] as FiveRegisterInstruction
        val answer = (code[at - 1] as OneRegisterInstruction).registerA
        val offer = code[at] as FiveRegisterInstruction
        assertEquals("$KIND->canSave(Lcom/instagram/common/session/UserSession;$MESSAGE)Z", code[at - 2].referenceText())
        assertEquals("the answer, then the message asked about", listOf(answer, ask.registerE), listOf(offer.registerC, offer.registerD))
        assertEquals("the extension's answer replaces Instagram's", answer, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("then Instagram's own check", Opcode.IF_EQZ, code[at + 2].opcode)
        assertEquals(answer, (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("once", 1, code.count { it.referenceText() == OFFER_VOICE })
    }

    @Test
    fun theSaverAsksTheExtensionFirst() {
        val context = context()
        context.applyVoiceMessages(context.findVoiceMessages())

        val code = context.method(SAVER, "save").instructions()
        val message = code[0] as TwoRegisterInstruction
        val activity = code[1] as TwoRegisterInstruction
        assertEquals("$REQUEST->message:$MESSAGE", code[0].referenceText())
        assertEquals("off the request", 2, message.registerB - context.method(SAVER, "save").locals())
        assertEquals("$SAVER->activity:Landroid/app/Activity;", code[1].referenceText())
        assertEquals("off the saver", 0, activity.registerB - context.method(SAVER, "save").locals())
        val save = code[2] as FiveRegisterInstruction
        assertEquals(SAVE_VOICE, code[2].referenceText())
        assertEquals(listOf(activity.registerA, message.registerA), listOf(save.registerC, save.registerD))
        assertEquals(listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), code.subList(3, 6).map { it.opcode })
        assertEquals("a no goes on to Instagram's own save", "$SAVER->check(${SAVER_PARAMETERS.joinToString("")})V", code[6].referenceText())
    }

    @Test
    fun theBridgesReadWhatInstagramReads() {
        val context = context()
        context.applyVoiceMessages(context.findVoiceMessages())

        val audio = context.method(VOICE_MESSAGE, AUDIO_BRIDGE).instructions()
        assertEquals(
            listOf("$MESSAGE->voice:$VOICE", "$VOICE->media()$VOICE_MEDIA", "$VOICE_MEDIA->source:$SOURCE", "$SOURCE->url()Ljava/lang/String;"),
            audio.filter { it.opcode in READS }.map { it.referenceText() },
        )
        assertEquals("the source is an interface", Opcode.INVOKE_INTERFACE, audio.single { it.referenceText()?.endsWith("url()Ljava/lang/String;") == true }.opcode)
        assertEquals(Opcode.INSTANCE_OF, audio.first { it.opcode == Opcode.INSTANCE_OF }.opcode)
        val mode = context.method(VOICE_MESSAGE, VIEW_MODE_BRIDGE).instructions()
        assertEquals(listOf("$MESSAGE->voice:$VOICE", "$VOICE->viewMode:Ljava/lang/String;"), mode.filter { it.opcode in READS }.map { it.referenceText() })
    }

    @Test
    fun twoFlagsGuardingSaveFailThePatch() = refuses("expected one boolean of the menu's model guarding Save, found 2") {
        context(menu = menu(secondFlag = "other")).findVoiceMessages()
    }

    @Test
    fun aSaveWithoutTheFlagFailsThePatch() = refuses("isn't added only when") {
        context(menu = menu(secondGuard = false)).findVoiceMessages()
    }

    @Test
    fun aFlagStoredFromSomethingElseFailsThePatch() = refuses("doesn't store the flag straight from a parameter") {
        context(model = model(computed = true)).findVoiceMessages()
    }

    @Test
    fun aBuilderWhoseNoGoesElsewhereFailsThePatch() = refuses("expected one place the model's builder asks") {
        context(builder = builder(noSetsZero = false)).findVoiceMessages()
    }

    @Test
    fun aJumpToTheBuildersCheckFailsThePatch() = refuses("something jumps to the builder's check") {
        context(builder = builder(jumpToCheck = true)).findVoiceMessages()
    }

    @Test
    fun aViewModeKeptTwiceFailsThePatch() = refuses("expected the view mode kept in one field") {
        context(parser = parser(keptTwice = true)).findVoiceMessages()
    }

    @Test
    fun aPlayerReadingSomethingElseFailsThePatch() = refuses("the player doesn't read the recording's address") {
        context(player = player(address = "I")).findVoiceMessages()
    }

    @Test
    fun aSaverWithoutAnEntryFailsThePatch() = refuses("expected one entry calling") {
        context(saver = saver(entry = false)).findVoiceMessages()
    }

    @Test
    fun aSaverWithTwoActivitiesFailsThePatch() = refuses("expected one activity") {
        context(saver = saver(activities = 2)).findVoiceMessages()
    }

    /**
     * In each declared build: the builder's one check of a message's kind gets the hook, the saver's
     * entry asks first, and both bridges are written from Instagram's own reads.
     */
    @Test
    fun eachDeclaredBuildOffersSaveOnVoiceMessages() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val held = listOf(MESSAGE_MENU, SAVE_ACTION, AUDIO_SOURCE, VIEW_MODE_KEY, MEDIA_SAVER)
                    .flatMap { FixtureDex.classesHolding(bundle, it) }.distinctBy { it.type }
                val extension = ExtensionDex.classDef(VOICE_MESSAGE)
                val (_, flag) = PatchContexts.of(held + extension).findSaveFlag()
                val model = flag.definingClass
                val builders = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == model } }) { method ->
                    method.instructions().any { it.opcode == Opcode.NEW_INSTANCE && (it.reference() as TypeReference).type == model }
                }.map { it.definingClass }
                val entryTypes = held.filter { it.methods.any { method -> method.loads(MEDIA_SAVER) } }
                    .flatMap { it.methods }.flatMap { it.parameterTypes.map(Any::toString) }.filter { it.startsWith("L") }
                val extra = FixtureDex.classes(bundle, (builders + entryTypes + model).toSet()).values
                val context = PatchContexts.of((held + extra).distinctBy { it.type } + extension)

                val found = context.findVoiceMessages()
                context.applyVoiceMessages(found)

                val builder = context.mutableClassDefBy(found.offer.method.definingClass).methods.single {
                    it.name == found.offer.method.name && it.parameterTypes == found.offer.method.parameterTypes
                }
                assertEquals("${bundle.name}: one offer", 1, builder.instructions().count { it.referenceText() == OFFER_VOICE })
                val saver = context.mutableClassDefBy(found.saver.method.definingClass).methods.single {
                    it.name == found.saver.method.name && it.parameterTypes == found.saver.method.parameterTypes
                }
                assertEquals("${bundle.name}: the saver asks first", SAVE_VOICE, saver.instructions()[2].referenceText())
                for (bridge in listOf(AUDIO_BRIDGE, VIEW_MODE_BRIDGE)) {
                    assertTrue("${bundle.name}: $bridge reads the message",
                        context.method(VOICE_MESSAGE, bridge).instructions().any { it.opcode == Opcode.CHECK_CAST })
                }
                if (version == "450.0.0.50.77") {
                    // Where it lands on 450, read off the dex by hand.
                    val at = builder.instructions().indexOfFirst { it.referenceText() == OFFER_VOICE }
                    assertEquals("LX/09xN;->invoke", "${builder.definingClass}->${builder.name}")
                    assertEquals("LX/0Jyt;->AYr(Lcom/instagram/common/session/UserSession;LX/0038;)Z", builder.instructions()[at - 2].referenceText())
                    assertEquals("LX/0Prd;->A03", "${saver.definingClass}->${saver.name}")
                    assertEquals("LX/0Cpp;->A00:LX/0038;", saver.instructions()[0].referenceText())
                    assertEquals("LX/0Prd;->A00:Landroid/app/Activity;", saver.instructions()[1].referenceText())
                    assertEquals(
                        listOf("LX/0038;->A0N:LX/05x6;", "LX/05x6;->A00()LX/04bU;", "LX/04bU;->A08:LX/047y;", "LX/047y;->BTn()Ljava/lang/String;"),
                        context.method(VOICE_MESSAGE, AUDIO_BRIDGE).instructions().filter { it.opcode in READS }.map { it.referenceText() },
                    )
                    assertEquals(
                        listOf("LX/0038;->A0N:LX/05x6;", "LX/05x6;->A0D:Ljava/lang/String;"),
                        context.method(VOICE_MESSAGE, VIEW_MODE_BRIDGE).instructions().filter { it.opcode in READS }.map { it.referenceText() },
                    )
                }
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

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Method.locals(): Int = implementation!!.registerCount - (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) - parameterTypes.size
    private fun Method.loads(string: String) = instructions().any {
        ((it.reference()) as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string == string
    }
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
    private fun Instruction.referenceText(): String? = reference()?.toString()

    private companion object {
        const val MENU = "Lfixture/ThreadFragment;"
        const val ACTION = "Lfixture/SaveMediaAction;"
        const val OTHER_ACTION = "Lfixture/OtherSaveMediaAction;"
        const val MODEL = "Lfixture/MenuModel;"
        const val BUILDER = "Lfixture/MenuModelBuilder;"
        const val KIND = "Lfixture/MessageKind;"
        const val MESSAGE = "Lfixture/Message;"
        const val PLAYER = "Lfixture/VoicePlayer;"
        const val VOICE = "Lfixture/Voice;"
        const val VOICE_MEDIA = "Lfixture/VoiceMedia;"
        const val SOURCE = "Lfixture/AudioSource;"
        const val PARSER = "Lfixture/VoiceParser;"
        const val JSON = "Lfixture/Json;"
        const val SAVER = "Lfixture/MediaSaver;"
        const val REQUEST = "Lfixture/SaveRequest;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        val SAVER_PARAMETERS = listOf(SAVER, "Lfixture/Media;", REQUEST, KIND)
        val READS = setOf(Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE)
        const val PUBLIC_FINAL = 0x0011
        const val PUBLIC_STATIC = 0x0009

        fun context(
            menu: ClassDef = menu(),
            model: ClassDef = model(),
            builder: ClassDef = builder(),
            player: ClassDef = player(),
            parser: ClassDef = parser(),
            saver: ClassDef = saver(),
        ) = PatchContexts.of(listOf(menu, action(ACTION), action(OTHER_ACTION), model, builder, player, parser, saver, request(), ExtensionDex.classDef(VOICE_MESSAGE)))

        /** Shaped like 450's X.0GiO.HWZ: looks the message up, then adds Save twice behind the model's boolean. */
        fun menu(secondFlag: String = "canSave", secondGuard: Boolean = true): ClassDef {
            val second = if (secondFlag == "canSave") "" else "iget-boolean v0, p2, $MODEL->$secondFlag:Z"
            val body = """
                const/4 v2, 0x0
                const-string v1, "$MESSAGE_MENU"
                invoke-virtual { v2, v1 }, Lfixture/Cache;->find(Ljava/lang/String;)$MESSAGE
                move-result-object v3
                iget-boolean v0, p2, $MODEL->canSave:Z
                if-eqz v0, :second
                if-nez v3, :second
                sget-object v1, $ACTION->INSTANCE:$ACTION
                invoke-static { v1 }, Lfixture/Rows;->add(Ljava/lang/Object;)V
                :second
                $second
                ${if (secondGuard) "if-eqz v0, :end" else "if-eqz v3, :end"}
                sget-object v1, $ACTION->INSTANCE:$ACTION
                invoke-static { v1 }, Lfixture/Rows;->add(Ljava/lang/Object;)V
                :end
                return-void
            """
            return ImmutableClassDef(
                MENU, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(method(MENU, "show", listOf("Landroid/graphics/PointF;", MODEL), 8, body, PUBLIC_FINAL)),
            )
        }

        /**
         * Shaped like 450's X.0eDg: a Save action, named in its toString. 450 has two more of these
         * (X.0HsW and X.0HuY) for other menus, and the chat's menu reads only its own.
         */
        fun action(type: String): ClassDef = ImmutableClassDef(
            type, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null,
            listOf(ImmutableField(type, "INSTANCE", type, PUBLIC_STATIC or AccessFlags.FINAL.value, null, null, null)),
            listOf(method(type, "toString", emptyList(), 2, "const-string v0, \"$SAVE_ACTION\"\nreturn-object v0", PUBLIC_FINAL, "Ljava/lang/String;")),
        )

        /** Shaped like 450's X.05Xs: a boolean per action, each from its own constructor parameter. */
        fun model(computed: Boolean = false): ClassDef {
            val canSave = if (computed) "xor-int/lit8 v0, p2, 0x1" else "move v0, p2"
            return ImmutableClassDef(
                MODEL, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null,
                listOf("canSave", "other").map { ImmutableField(MODEL, it, "Z", PUBLIC_FINAL, null, null, null) },
                listOf(
                    method(MODEL, "<init>", listOf("Z", "Z"), 4, """
                        $canSave
                        iput-boolean v0, p0, $MODEL->canSave:Z
                        iput-boolean p1, p0, $MODEL->other:Z
                        return-void
                    """, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value),
                ),
            )
        }

        /** Shaped like 450's X.09xN.invoke: asks the message's kind, and sets the model's Save boolean from the answer. */
        fun builder(noSetsZero: Boolean = true, jumpToCheck: Boolean = false): ClassDef {
            val body = """
                ${if (jumpToCheck) "if-eqz p1, :check" else "nop"}
                invoke-interface { p3, p1, p2 }, $KIND->canSave($SESSION$MESSAGE)Z
                move-result v0
                :check
                if-eqz v0, :no
                const/16 v5, 0x1
                goto :made
                :no
                ${if (noSetsZero) "const/16 v5, 0x0" else "const/16 v4, 0x0"}
                :made
                const/4 v4, 0x0
                new-instance v6, $MODEL
                invoke-direct { v6, v4, v5 }, $MODEL-><init>(ZZ)V
                return-void
            """
            return ImmutableClassDef(
                BUILDER, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null, null,
                listOf(method(BUILDER, "build", listOf(SESSION, MESSAGE, KIND), 12, body, PUBLIC_FINAL)),
            )
        }

        /** Shaped like the player's read in 450's StellaDirectMessagingService binder. */
        fun player(address: String = "Ljava/lang/String;"): ClassDef {
            val body = """
                const-string v0, "$AUDIO_SOURCE"
                invoke-static { v0 }, Lfixture/Trace;->step(Ljava/lang/String;)V
                if-eqz p1, :none
                iget-object v0, p1, $MESSAGE->voice:$VOICE
                if-eqz v0, :none
                invoke-virtual { v0 }, $VOICE->media()$VOICE_MEDIA
                move-result-object v0
                if-eqz v0, :none
                iget-object v0, v0, $VOICE_MEDIA->source:$SOURCE
                if-eqz v0, :none
                invoke-interface { v0 }, $SOURCE->url()$address
                :none
                const/4 v0, 0x0
                return-object v0
            """
            return ImmutableClassDef(
                PLAYER, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null, null,
                listOf(method(PLAYER, "link", listOf(MESSAGE), 3, body, PUBLIC_FINAL, "Ljava/lang/String;")),
            )
        }

        /** Shaped like 450's X.04q2: parses the voice media, keeping the view mode in its own field. */
        fun parser(keptTwice: Boolean = false): ClassDef {
            val body = """
                const-string v0, "$VIEW_MODE_KEY"
                invoke-virtual { p1, v0 }, $JSON->has(Ljava/lang/String;)Z
                move-result v0
                invoke-virtual { p1 }, $JSON->text()Ljava/lang/String;
                move-result-object v2
                const-string v0, "$WAVEFORM_KEY"
                invoke-virtual { p1 }, $JSON->text()Ljava/lang/String;
                move-result-object v3
                new-instance v1, $VOICE
                invoke-direct { v1 }, $VOICE-><init>()V
                iput-object v2, v1, $VOICE->viewMode:Ljava/lang/String;
                ${if (keptTwice) "iput-object v2, v1, $VOICE->mode:Ljava/lang/String;" else "nop"}
                iput-object v3, v1, $VOICE->waveform:Ljava/lang/String;
                return-object v1
            """
            return ImmutableClassDef(
                PARSER, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null, null,
                listOf(method(PARSER, "parse", listOf(JSON), 6, body, PUBLIC_FINAL, "Ljava/lang/Object;")),
            )
        }

        /** Shaped like 450's X.0Prd: a static entry that hands everything to the check of a message's kind. */
        fun saver(entry: Boolean = true, activities: Int = 1): ClassDef {
            val parameters = SAVER_PARAMETERS
            val check = "$SAVER->check(${parameters.joinToString("")})V"
            return ImmutableClassDef(
                SAVER, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null,
                (1..activities).map { ImmutableField(SAVER, if (it == 1) "activity" else "activity$it", "Landroid/app/Activity;", PUBLIC_FINAL, null, null, null) },
                listOf(
                    method(SAVER, "name", emptyList(), 2, "const-string v0, \"$MEDIA_SAVER\"\nreturn-object v0", PUBLIC_FINAL, "Ljava/lang/String;"),
                    method(SAVER, "check", parameters, 6, "const-string v0, \"$SAVER_CHECK\"\nreturn-void", PUBLIC_STATIC or AccessFlags.FINAL.value),
                    method(SAVER, "save", parameters, 6,
                        if (entry) "invoke-static { p0, p1, p2, p3 }, $check\nreturn-void" else "return-void",
                        PUBLIC_STATIC or AccessFlags.FINAL.value),
                ),
            )
        }

        /** Shaped like 450's X.0Cpp: the message to save and its kind. */
        fun request(): ClassDef = ImmutableClassDef(
            REQUEST, PUBLIC_FINAL, "Ljava/lang/Object;", null, null, null,
            listOf(ImmutableField(REQUEST, "message", MESSAGE, PUBLIC_FINAL, null, null, null)),
            null,
        )

        fun method(type: String, name: String, parameters: List<String>, registers: Int, body: String, access: Int, returns: String = "V"): Method {
            val mutable = MutableMethod(
                ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, access, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null)),
            ).apply { addInstructionsWithLabels(0, body.trimIndent()) }
            return ImmutableMethod.of(mutable)
        }
    }
}
