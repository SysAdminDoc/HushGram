/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.coexist

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code half on every build of Instagram 457.0.0.55.73. Each build names its own package by the same
 * uses, Redex's names aside, and every use that means the running app moves while the rest stay.
 */
class CloneCodeTest {
    private val clone = "com.hush.second"

    /**
     * The const-string loads of an authority each build has, and the loads of Instagram's package that stay,
     * as each build's plan counts them. 455's builds had between 35 and 38 authority loads and kept 89 or 90
     * package loads; 456.0's had 34, or 37 on the x86 build; 456.1's had 34, or 36 on armeabi-v7a, and all kept 93.
     * 457's two arm64 builds have 36 and keep 95.
     */
    private val authorityLoads = mapOf(
        "386314297" to 36, "386314341" to 36,
    )
    private val keptLoads = mapOf(
        "386314297" to 95, "386314341" to 95,
    )

    /**
     * 455 moved the release and debug list checks 454 made in three places (Ig4aAppComponentFactory among
     * them) into one shared helper, and 456 drops the two checks 455 still made of its own beside it
     * (LX/04Ch;->A09 and A0D on 386113969), so the helper's two (LX/07BL;->A0O and A0c on 386214574) are
     * the only ones left. 456 also opens one screen of its own fewer from a click.
     */
    private val uses = mapOf(
        SelfUse.INLINED to 5, SelfUse.PACKAGE_MANAGER to 7, SelfUse.OWN_INTENT to 11,
        SelfUse.NAME_CHECK to 6, SelfUse.CALLER_CHECK to 3, SelfUse.PROVIDER_OWNER to 4, SelfUse.RELEASE_AND_DEBUG to 2,
        SelfUse.DEX_LOAD_REPORT to 2,
    )

    /**
     * The builds whose uses differ from the rest. 456's armeabi-v7a builds opened one screen of their own more
     * from a click; both arm64 builds of 457 count the same.
     */
    private val usesBy = emptyMap<String, Map<SelfUse, Int>>()

    /**
     * Google Play's uses, a count that differed by build on 455 as R8 kept more or fewer of its helpers in each.
     * 456 keeps five on every build, two fewer than 455's arm64 build (LX/0KTt;->A00 and LX/0Kw1;->A00 on
     * 386113969 have no counterpart).
     */
    private val googlePlayUses = mapOf(
        "386314297" to 5, "386314341" to 5,
    )

    /**
     * The share handler's caller check: in BaseShareHandlerActivity or its subclass
     * ClipsMusicShareHandlerActivity, the builds of one version not always agreeing (456.0 and 456.1
     * had it the other way round from each other). Both of 457's arm64 builds have it in the subclass.
     */
    private val shareHandler = mapOf(
        "386314297" to "ClipsMusicShareHandlerActivity", "386314341" to "ClipsMusicShareHandlerActivity",
    )

    /**
     * The uses in classes Redex leaves named, the same in every build but for [shareHandler]. 453 renames the
     * Play billing classes, so theirs count among the LX/ ones.
     */
    private fun namedUses(build: String) = listOf(
        "Lcom/facebook/common/dextricks/achilles/Achilles; DEX_LOAD_REPORT",
        "Lcom/facebook/common/dextricks/achilles/Achilles; DEX_LOAD_REPORT",
        "Lcom/facebook/rti/mqtt/manager/MqttBackgroundServiceDelegate; NAME_CHECK",
        "Lcom/instagram/common/resources/downloadable/impl/WaitingForStringsActivity; OWN_INTENT",
        "Lcom/instagram/inappbrowser/service/BrowserLiteCallbackService\$BrowserLiteCallbackImpl; CALLER_CHECK",
        "Lcom/instagram/share/handleractivity/${shareHandler.getValue(build)}; CALLER_CHECK",
        "Linstagram/features/stories/fragment/ReelViewerFragment; CALLER_CHECK",
    ).sorted()

    private fun ownPackage() = ExtensionDex.classDef(OWN_PACKAGE_CLASS)

    @Test fun everyBuildMovesItsOwnUsesAndKeepsTheRest() {
        val builds = CloneFixtures.builds()
        assertEquals(authorityLoads.keys, builds.keys)
        // Every build is checked before any failure is reported, so one run shows each build's counts.
        val failures = builds.mapNotNull { (build, file) ->
            runCatching { checkBuild(build, file) }.exceptionOrNull()?.let { "[$build] ${it.message}" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun checkBuild(build: String, file: java.io.File) {
        run {
            val authorities = readCloneManifest(CloneFixtures.manifest(file)).authorities
            val classes = CloneFixtures.classes(file, authorities)
            if (build == "386314341") {
                val withoutAnswer = PatchContexts.of(classes).planClone(clone, authorities)
                val refusal = assertThrows(PatchException::class.java) { withoutAnswer.requireComplete() }
                assertTrue(refusal.message, refusal.message!!.contains("OwnPackage.name()"))
            }
            val context = PatchContexts.of(classes + ownPackage())
            val plan = context.planClone(clone, authorities)
            plan.requireComplete()
            println("[$build] ${plan.summary()}")
            plan.sites.forEach { println("[$build]   $it") }

            assertEquals("[$build] uses", uses + usesBy[build].orEmpty() + (SelfUse.GOOGLE_PLAY to googlePlayUses[build]), SelfUse.entries.associateWith(plan::count))
            assertEquals("[$build] uses in named classes", namedUses(build), plan.sites.filter { it.use != null && !it.type.startsWith("LX/") }
                .map { "${it.type} ${it.use!!.name}" }.sorted())
            assertTrue("[$build] each use moves to the copy", plan.sites.filter { it.use != null }.all { it.to == clone })
            assertEquals("[$build] authority loads", authorityLoads[build], plan.rewrites.count { it.use == null })
            assertTrue("[$build] each authority load names the copy's", plan.rewrites.filter { it.use == null }.all { it.to!!.removePrefix("content://").startsWith("$clone.") })
            assertEquals("[$build] kept loads", keptLoads[build], plan.kept.size)
            assertTrue("[$build] the extension's answer", plan.ownPackage?.to == clone)

            context.applyClone(plan)

            val after = context.planClone(clone, authorities)
            assertEquals("[$build] no use is left", 0, after.sites.count { it.use != null })
            assertEquals("[$build] no load names an authority Instagram declares, pooled or not", emptyList<CloneSite>(), after.authorities)
            assertEquals("[$build] the kept loads are untouched", plan.kept.map { "$it" }, after.kept.map { "$it" })
            val answer = context.classDefBy(OWN_PACKAGE_CLASS).methods.single { it.name == "name" }.implementation!!.instructions
                .filter { it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO }
                .map { ((it as ReferenceInstruction).reference as StringReference).string }
            assertEquals("[$build] OwnPackage.name()", listOf(clone), answer)
        }
    }

    @Test fun codeWithoutInstagramsUsesIsRefused() {
        val empty = PatchContexts.of(listOf(ownPackage())).planClone(clone, listOf("com.instagram.fileprovider"))
        val refusal = assertThrows(PatchException::class.java) { empty.requireComplete() }
        assertTrue(refusal.message, refusal.message!!.contains("found no code that loads $INSTAGRAM"))
    }

    @Test fun theExtensionAnswersWithOneStringThePatchCanMove() {
        val method = ownPackage().methods.single { it.name == "name" }
        val strings = method.implementation!!.instructions.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
        assertEquals(listOf(INSTAGRAM), strings)
    }

    @Test fun languagePackReturnUsesTheActivityAndKeepsTheScopedLauncher() {
        for (registers in listOf(4, 20)) {
            val context = PatchContexts.of(listOf(waitingForStrings(registers)))
            context.keepLanguagePackReturnInTask()
            val code = context.classDefBy(WAITING_FOR_STRINGS).methods.single().implementation!!.instructions.toList()
            val move = code.single { it.opcode == Opcode.MOVE_OBJECT_FROM16 } as TwoRegisterInstruction
            assertEquals(1, move.registerA)
            assertEquals(registers - 1, move.registerB)
            val calls = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name }
            assertEquals(listOf("setPackage", "openPublic", "finish"), calls)
            assertEquals(registers, context.classDefBy(WAITING_FOR_STRINGS).methods.single().implementation!!.registerCount)
        }
    }

    @Test fun ambiguousLanguagePackReturnIsRefusedWithoutChangingCode() {
        val original = waitingForStrings(4, 2)
        val context = PatchContexts.of(listOf(original))
        val before = context.mutableClassDefBy(WAITING_FOR_STRINGS).methods.single().implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { context.keepLanguagePackReturnInTask() }
        assertEquals(before,
            context.classDefBy(WAITING_FOR_STRINGS).methods.single().implementation!!.instructions.toList())
    }

    /** 457 launches the return from the activity itself, so the patch finds nothing to change and changes nothing. */
    @Test fun languagePackReturnFromTheActivityIsLeftAsItIs() {
        val context = PatchContexts.of(listOf(waitingForStrings(4, direct = true)))
        val before = context.mutableClassDefBy(WAITING_FOR_STRINGS).methods.single().implementation!!.instructions.toList()
        context.keepLanguagePackReturnInTask()
        assertEquals(before, context.classDefBy(WAITING_FOR_STRINGS).methods.single().implementation!!.instructions.toList())
        val twice = PatchContexts.of(listOf(waitingForStrings(4, copies = 2, direct = true)))
        assertThrows(PatchException::class.java) { twice.keepLanguagePackReturnInTask() }
    }

    private fun waitingForStrings(registers: Int, copies: Int = 1, direct: Boolean = false): ImmutableClassDef {
        val method = MutableMethod(ImmutableMethod(
            WAITING_FOR_STRINGS, "resume", listOf(ImmutableMethodParameter(WAITING_FOR_STRINGS, null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ))
        val launch = if (direct) {
            "invoke-static {p0, v2}, Lx/Launchers;->openPublic(Landroid/content/Context;Landroid/content/Intent;)Z"
        } else {
            """
                invoke-static/range {p0 .. p0}, Lx/Contexts;->application(Landroid/content/Context;)Landroid/content/Context;
                move-result-object v1
                invoke-static {v1, v2}, Lx/Launchers;->openPublic(Landroid/content/Context;Landroid/content/Intent;)Z
            """.trimIndent()
        }
        method.addInstructionsWithLabels(0, """
            const/4 v2, 0x0
            const-string v0, "com.instagram.android"
            invoke-virtual {v2, v0}, Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;
            ${List(copies) { launch }.joinToString("\n")}
            invoke-virtual/range {p0 .. p0}, Landroid/app/Activity;->finish()V
            return-void
        """.trimIndent())
        return ImmutableClassDef(WAITING_FOR_STRINGS, AccessFlags.PUBLIC.value, "Landroid/app/Activity;",
            null, null, null, emptyList(), listOf(method))
    }
}
