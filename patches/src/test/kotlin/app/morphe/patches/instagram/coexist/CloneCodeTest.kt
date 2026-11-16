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
 * The code half on every build of Instagram 455.0.0.51.72. Each build names its own package by the same
 * uses, Redex's names aside, and every use that means the running app moves while the rest stay.
 */
class CloneCodeTest {
    private val clone = "com.hush.second"

    /**
     * The const-string loads of an authority each build has, and the loads of Instagram's package that stay,
     * as each build's plan counts them. 455's builds have between 35 and 38 authority loads, and keep 89 or 90
     * package loads.
     */
    private val authorityLoads = mapOf(
        "386113915" to 35, "386113916" to 37, "386113918" to 38, "386113922" to 35, "386113923" to 38,
        "386113928" to 35, "386113929" to 37, "386113958" to 37, "386113968" to 35, "386113969" to 37,
        "386113970" to 36, "386113971" to 38,
    )
    private val keptLoads = mapOf(
        "386113915" to 90, "386113916" to 89, "386113918" to 90, "386113922" to 90, "386113923" to 90,
        "386113928" to 90, "386113929" to 89, "386113958" to 89, "386113968" to 90, "386113969" to 89,
        "386113970" to 89, "386113971" to 90,
    )

    /**
     * 455 moved the release and debug list checks 454 made in three places (Ig4aAppComponentFactory among
     * them) into one shared helper, LX/07Dl;->A0d(Ljava/lang/String;Z)Z on 386113969, so there are four of
     * those uses where 454 had seven.
     */
    private val uses = mapOf(
        SelfUse.INLINED to 5, SelfUse.PACKAGE_MANAGER to 7, SelfUse.OWN_INTENT to 12,
        SelfUse.NAME_CHECK to 6, SelfUse.CALLER_CHECK to 3, SelfUse.PROVIDER_OWNER to 4, SelfUse.RELEASE_AND_DEBUG to 4,
        SelfUse.DEX_LOAD_REPORT to 2,
    )

    /**
     * The builds whose uses differ from the rest. 386113918, 386113923, 386113970 and 386113971 open one screen
     * of their own fewer from a click, and 386113970 lacks one PackageManager question (LX/0oLi;->A00 on
     * 386113969, the one asking for com.facebook.katana, com.facebook.orca and com.facebook.pronest.debug).
     */
    private val usesBy = mapOf(
        "386113918" to mapOf(SelfUse.OWN_INTENT to 11),
        "386113923" to mapOf(SelfUse.OWN_INTENT to 11),
        "386113970" to mapOf(SelfUse.OWN_INTENT to 11, SelfUse.PACKAGE_MANAGER to 6),
        "386113971" to mapOf(SelfUse.OWN_INTENT to 11),
    )

    /**
     * Google Play's uses, the one count that differs by build: R8 keeps more or fewer of its helpers in each.
     * On the armeabi-v7a builds Play Core's deferred install and uninstall helpers merge into one, which
     * leaves five.
     */
    private val googlePlayUses = mapOf(
        "386113915" to 5, "386113916" to 7, "386113918" to 7, "386113922" to 5, "386113923" to 7,
        "386113928" to 5, "386113929" to 7, "386113958" to 7, "386113968" to 5, "386113969" to 7,
        "386113970" to 7, "386113971" to 7,
    )

    /** The uses in classes Redex leaves named, the same in every build. 453 renames the Play billing classes, so theirs count among the LX/ ones. */
    private val namedUses = listOf(
        "Lcom/facebook/common/dextricks/achilles/Achilles; DEX_LOAD_REPORT",
        "Lcom/facebook/common/dextricks/achilles/Achilles; DEX_LOAD_REPORT",
        "Lcom/facebook/rti/mqtt/manager/MqttBackgroundServiceDelegate; NAME_CHECK",
        "Lcom/instagram/common/resources/downloadable/impl/WaitingForStringsActivity; OWN_INTENT",
        "Lcom/instagram/inappbrowser/service/BrowserLiteCallbackService\$BrowserLiteCallbackImpl; CALLER_CHECK",
        "Lcom/instagram/share/handleractivity/BaseShareHandlerActivity; CALLER_CHECK",
        "Linstagram/features/stories/fragment/ReelViewerFragment; CALLER_CHECK",
    )

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
            if (build == "386113969") {
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
            assertEquals("[$build] uses in named classes", namedUses, plan.sites.filter { it.use != null && !it.type.startsWith("LX/") }
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

    private fun waitingForStrings(registers: Int, copies: Int = 1): ImmutableClassDef {
        val method = MutableMethod(ImmutableMethod(
            WAITING_FOR_STRINGS, "resume", listOf(ImmutableMethodParameter(WAITING_FOR_STRINGS, null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ))
        val launch = """
            invoke-static/range {p0 .. p0}, Lx/Contexts;->application(Landroid/content/Context;)Landroid/content/Context;
            move-result-object v1
            invoke-static {v1, v2}, Lx/Launchers;->openPublic(Landroid/content/Context;Landroid/content/Intent;)Z
        """.trimIndent()
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
