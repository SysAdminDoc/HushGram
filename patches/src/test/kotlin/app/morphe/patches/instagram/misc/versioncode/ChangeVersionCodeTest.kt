/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.versioncode

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Change version code (#68): the manifest carries the highest version code, and Instagram's reads of
 * its own code answer the one Meta built. A read counts as Instagram's own only when its PackageInfo
 * provably came from getPackageInfo on Instagram's own name; every other read, and the two that are
 * meant to see the raised code, stay as they were. Anything the patch can't hold to that refuses
 * before a line changes.
 */
class ChangeVersionCodeTest {
    private val own = "com.instagram.android"
    private val real = 385611438
    private val helper = READ.substringBefore("->")
    private val app = "Lfixture/App;"
    private val start = "Lfixture/Start;"
    private val scheduler = "Lfixture/Scheduler;"
    private val lookup = "Lfixture/Lookup;"
    private val wrap = "Lfixture/Wrap;"
    private val crash = "Lfixture/Crash;"
    private val other = "Lfixture/Other;"
    private val item = "Lfixture/Item;"
    private val kept = "Lfixture/Kept;"
    private val public = AccessFlags.PUBLIC.value
    private val static = public or AccessFlags.STATIC.value
    private val manager = "Landroid/content/pm/PackageManager;"
    private val getInfo = "$manager->getPackageInfo(Ljava/lang/String;I)$PACKAGE_INFO"
    private val contextManager = "Landroid/content/Context;->getPackageManager()$manager"
    private val contextName = "Landroid/content/Context;->getPackageName()Ljava/lang/String;"

    /** Each read and what becomes of it: Instagram's own, the two kept raised, and every other. */
    @Test fun eachReadIsJudgedByWhosePackageItAsksAbout() {
        val verdicts = PatchContexts.of(classes()).versionCodeReads(own)
            .groupBy({ "${it.method.definingClass}->${it.method.name}" }, { "${it.index} ${it.verdict}${if (it.long) " long" else ""}" })
        assertEquals(mapOf(
            "$start->check" to listOf("7 REAL"),
            "$scheduler->setUp" to listOf("8 REAL", "11 REAL"),
            "$lookup->version" to listOf("4 REAL"),
            "$crash->report" to listOf("6 REAL long"),
            "$crash->wide" to listOf("7 REAL long"),
            "$other->foreign" to listOf("6 OTHER"),
            "$other->named" to listOf("5 OTHER"),
            "$other->given" to listOf("0 OTHER"),
            "$other->either" to listOf("9 OTHER"),
            "$other->item" to listOf("7 OTHER"),
            "$other->wrapped" to listOf("1 OTHER long"),
            "$kept->manifest" to listOf("6 KEPT"),
            "$kept->update" to listOf("6 KEPT"),
        ), verdicts)
    }

    @Test fun ownReadsCallTheHookInTheirPlace() {
        val context = PatchContexts.of(classes(extension = true))
        context.applyVersionCode(context.prepareVersionCode(own, real))

        assertRead(context.code(start, "check"), 7, READ, holder = 0, result = 1)
        val setUp = context.code(scheduler, "setUp")
        assertRead(setUp, 8, READ, holder = 1, result = 2)
        assertRead(setUp, 11 + 1, READ, holder = 3, result = 2)
        val branch = setUp.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the branch still lands past the second read", Opcode.CONST_STRING, setUp[branch.target.location.index].opcode)
        assertRead(context.code(lookup, "version"), 4, READ, holder = 0, result = 0)
        assertRead(context.code(crash, "report"), 6, READ_LONG, holder = 0, result = null)
        val wide = context.code(crash, "wide")
        assertRead(wide, 7, READ_LONG, holder = 16, result = null)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, wide[7].opcode)
        assertEquals(Opcode.MOVE_RESULT_WIDE, wide[8].opcode)

        for (type in listOf(other, kept)) for (method in context.classDefBy(type).methods) {
            assertTrue("$type->${method.name}", method.implementation!!.instructions.none { it.reference()?.startsWith(helper) == true })
        }
        assertEquals(real, context.stub("real"))
        assertEquals(HIGHEST_VERSION_CODE, context.stub("raised"))
        assertEquals("the status row", 1, context.status())
    }

    /** What the patch has to find before it writes anything. Without it, nothing changes. */
    @Test fun aBuildItCantHoldToItsChecksIsLeftAlone() {
        val cases = mapOf(
            "no start-up check" to classes(extension = true, startString = "Started"),
            "two start-up checks" to classes(extension = true) + startClass("Lfixture/StartAgain;"),
            "a start-up check on another app" to classes(extension = true, startName = "com.whatsapp"),
            "a start-up check kept raised" to classes(extension = true, startKept = true),
            "no job scheduler check" to classes(extension = true, schedulerString = "Codes match"),
            "no VersionCode class" to classes(extension = true, hooks = null),
            "no long hook" to classes(extension = true, hooks = listOf("read")),
            "a private hook" to classes(extension = true, hooks = listOf("read", "readLong"), hookFlags = AccessFlags.STATIC.value),
            "no raised stub" to classes(extension = true, stubs = listOf("real")),
        )
        for ((case, classes) in cases) {
            val context = PatchContexts.of(classes)
            val failure = runCatching { context.applyVersionCode(context.prepareVersionCode(own, real)) }.exceptionOrNull()
            assertTrue("$case: ${failure?.message}", failure is PatchException && failure.message!!.startsWith("$PATCH: "))
            assertUnchanged(case, context)
        }
        val noStatus = PatchContexts.of(classes(extension = true).filter { it.type != SETTINGS_STATUS } + clazz(SETTINGS_STATUS))
        val failure = runCatching { noStatus.applyVersionCode(noStatus.prepareVersionCode(own, real)) }.exceptionOrNull()
        assertTrue("${failure?.message}", failure is PatchException && failure.message!!.contains("versionCode()"))
        assertUnchanged("no status method", noStatus)
    }

    @Test fun theRealCodeIsOneBelowTheHighest() {
        assertEquals(real, realVersionCode("385611438"))
        assertEquals(real, realVersionCode(" 385611438 "))
        for (text in listOf(null, "", "abc", "0", "-5", "2147483647", "99999999999")) {
            val failure = runCatching { realVersionCode(text) }.exceptionOrNull()
            assertTrue("$text: ${failure?.message}", failure is PatchException && failure.message!!.startsWith("$PATCH: "))
        }
    }

    @Test fun theManifestTakesTheHighestCodeAndNothingElseMoves() {
        val manifest = manifest()
        raiseVersionCode(manifest, real)
        val root = manifest.documentElement
        assertEquals("2147483647", root.getAttribute("android:versionCode"))
        assertEquals("450.0.0.50.77", root.getAttribute("android:versionName"))
        assertEquals(own, root.getAttribute("package"))
        assertEquals(1, manifest.getElementsByTagName("application").length)
    }

    @Test fun aManifestThatIsntTheOneReadIsRefusedUnchanged() {
        val cases = mapOf(
            "another code" to manifest(code = "385511871"),
            "already raised" to manifest(code = "2147483647"),
            "no code" to manifest(code = null),
            "a major half" to manifest(major = true),
            "not a manifest" to manifest(root = "application"),
        )
        for ((case, manifest) in cases) {
            val before = manifest.documentElement.getAttribute("android:versionCode")
            val failure = runCatching { raiseVersionCode(manifest, real) }.exceptionOrNull()
            assertTrue("$case: ${failure?.message}", failure is PatchException && failure.message!!.startsWith("$PATCH: "))
            assertEquals(case, before, manifest.documentElement.getAttribute("android:versionCode"))
        }
    }

    /**
     * In each declared build, Instagram's own reads are found and hooked, its start-up and job
     * scheduler checks among them, and only the two meant to see the raised code are kept.
     */
    @Test fun everyDeclaredBuildHasItsChecksAndOnlyProvenReads() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            val code = realVersionCode(bundle.nameWithoutExtension.substringAfterLast('-'))
            val found = mutableMapOf<String, ClassDef>()
            val wanted = mutableSetOf<String>()
            FixtureDex.forEach(bundle) { dex ->
                if (dex.methodSection.none { it.definingClass == manager && it.name == "getPackageInfo" }) return@forEach
                for (classDef in dex.classes) {
                    val calls = classDef.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }
                        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                    if (calls.none { it.definingClass == manager && it.name == "getPackageInfo" }) continue
                    found[classDef.type] = ImmutableClassDef.of(classDef)
                    calls.filter { it.name == "getPackageName" || it.name == "getApplicationInfo" ||
                        it.parameterTypes.map(Any::toString).let { p -> p == listOf(PACKAGE_INFO) || p == listOf("Ljava/lang/String;") }
                    }.mapTo(wanted) { it.definingClass }
                    classDef.superclass?.let(wanted::add)
                }
            }
            var next = wanted.filterNot { it in found || it.startsWith("Landroid/") || it.startsWith("Ljava/") }.toSet()
            while (next.isNotEmpty()) {
                val supers = FixtureDex.classes(bundle, next)
                found.putAll(supers)
                next = supers.values.mapNotNull { it.superclass }
                    .filterNot { it in found || it.startsWith("Landroid/") || it.startsWith("Ljava/") }.toSet()
            }
            val context = PatchContexts.of(found.values + ExtensionDex.classDef(helper) + ExtensionDex.classDef(SETTINGS_STATUS))
            val reads = context.versionCodeReads(own)
            val prepared = context.prepareVersionCode(own, code)
            val keptReads = reads.filter { it.verdict == Verdict.KEPT }
            println("[$version] ${reads.size} reads: ${reads.count { it.verdict == Verdict.REAL }} own, ${keptReads.size} kept, " +
                "${reads.count { it.verdict == Verdict.OTHER }} other")
            reads.forEach { println("[$version]   $it") }

            assertTrue("the start-up check", prepared.reads.any {
                it.method.definingClass == "Lcom/instagram/process/instagram/InstagramApplicationForMainProcess;" &&
                    it.method.name == "initializeAllColdStartJobs"
            })
            assertEquals("the job scheduler check's two reads", 2,
                prepared.reads.count { read -> read.method.implementation!!.instructions.any { it.string()?.startsWith(SCHEDULER_CHECK) == true } })
            assertEquals("own reads", 14, prepared.reads.size)
            assertEquals("own long reads", 1, prepared.reads.count { it.long })
            assertEquals("kept raised", listOf(
                "held against the manifest of Instagram's own APK file, which is raised too",
                "sent to Google Play's update check, which is what the raised code is for",
            ), keptReads.map { it.why }.sorted())

            context.applyVersionCode(prepared)
            val hooked = prepared.reads.map { it.method }.distinct().sumOf { method ->
                context.code(method.definingClass, method.name, method.parameterTypes.map(Any::toString))
                    .count { it.reference() == READ || it.reference() == READ_LONG }
            }
            assertEquals("a hook call for each own read", prepared.reads.size, hooked)
            assertEquals(code, context.stub("real"))
            assertEquals(1, context.status())
            checked += version
        }
        assertEquals("declared build has no fixture", versions, checked)
    }

    private fun assertRead(code: List<Instruction>, at: Int, hook: String, holder: Int, result: Int?) {
        assertEquals("the hook at $at", hook, code[at].reference())
        val registers = when (val call = code[at]) {
            is FiveRegisterInstruction -> listOf(call.registerC).take(call.registerCount)
            is RegisterRangeInstruction -> (call.startRegister until call.startRegister + call.registerCount).toList()
            else -> emptyList()
        }
        assertEquals("the PackageInfo at $at", listOf(holder), registers)
        if (result != null) {
            assertEquals(Opcode.MOVE_RESULT, code[at + 1].opcode)
            assertEquals("the read's own register", result, (code[at + 1] as OneRegisterInstruction).registerA)
        }
    }

    private fun assertUnchanged(case: String, context: BytecodePatchContext) {
        for (type in listOf(start, scheduler, lookup, crash, other, kept)) for (method in context.classDefBy(type).methods) {
            assertTrue("$case: $type->${method.name}", method.implementation!!.instructions.none { it.reference()?.startsWith(helper) == true })
        }
        assertEquals("$case: the status row", 0, runCatching { context.status() }.getOrDefault(0))
    }

    private fun BytecodePatchContext.code(type: String, name: String, parameters: List<String>? = null): List<Instruction> =
        classDefBy(type).methods.single { it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters) }
            .implementation!!.instructions.toList()

    private fun BytecodePatchContext.stub(name: String): Int =
        (code(helper, name).first() as NarrowLiteralInstruction).narrowLiteral

    private fun BytecodePatchContext.status(): Int =
        (code(SETTINGS_STATUS, "versionCode").first() as NarrowLiteralInstruction).narrowLiteral

    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun manifest(code: String? = "385611438", major: Boolean = false, root: String = "manifest"): Document {
        val attributes = listOfNotNull(
            "package=\"$own\"",
            code?.let { "android:versionCode=\"$it\"" },
            "android:versionName=\"450.0.0.50.77\"",
            if (major) "android:versionCodeMajor=\"1\"" else null,
        ).joinToString(" ")
        val xml = "<$root xmlns:android=\"http://schemas.android.com/apk/res/android\" $attributes><application/></$root>"
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }

    private fun classes(
        extension: Boolean = false,
        startString: String = START_CHECK,
        startName: String? = null,
        startKept: Boolean = false,
        schedulerString: String = "${SCHEDULER_CHECK}packageManager: %d buildConstant: %d",
        hooks: List<String>? = listOf("read", "readLong"),
        hookFlags: Int = static,
        stubs: List<String> = listOf("real", "raised"),
    ): List<ClassDef> = listOfNotNull(
        clazz(app, supertype = "Landroid/app/Application;"),
        startClass(start, startString, startName, startKept),
        clazz(scheduler, methods = listOf(method(scheduler, "setUp", listOf("Landroid/content/Context;", "I"), "V", 7, public, """
            invoke-virtual { p1 }, Landroid/content/Context;->getApplicationInfo()Landroid/content/pm/ApplicationInfo;
            move-result-object v0
            iget-object v0, v0, Landroid/content/pm/ApplicationInfo;->packageName:Ljava/lang/String;
            invoke-virtual { p1 }, $contextManager
            move-result-object v1
            const/4 v2, 0x0
            invoke-virtual { v1, v0, v2 }, $getInfo
            move-result-object v1
            iget v2, v1, $VERSION_CODE_FIELD
            if-eqz p2, :same
            move-object v3, v1
            iget v2, v3, $VERSION_CODE_FIELD
            :same
            const-string v0, "$schedulerString"
            return-void
        """))),
        clazz(lookup, methods = listOf(
            method(lookup, "manager", emptyList(), manager, 1, static, "const/4 v0, 0x0\nreturn-object v0"),
            method(lookup, "ask", listOf("Ljava/lang/String;"), PACKAGE_INFO, 3, static, """
                if-nez p0, :ask
                const/4 v0, 0x0
                return-object v0
                :ask
                invoke-static { }, $lookup->manager()$manager
                move-result-object v0
                const/4 v1, 0x0
                invoke-virtual { v0, p0, v1 }, $getInfo
                move-result-object v0
                return-object v0
            """),
            method(lookup, "version", listOf("Landroid/content/Context;"), "I", 2, static, """
                invoke-virtual { p0 }, $contextName
                move-result-object v0
                invoke-static { v0 }, $lookup->ask(Ljava/lang/String;)$PACKAGE_INFO
                move-result-object v0
                iget v0, v0, $VERSION_CODE_FIELD
                return v0
            """),
        )),
        clazz(wrap, methods = listOf(method(wrap, "code", listOf(PACKAGE_INFO), "J", 3, static, """
            invoke-virtual { p0 }, $LONG_VERSION_CODE
            move-result-wide v0
            return-wide v0
        """))),
        clazz(crash, methods = listOf(
            method(crash, "report", listOf("Landroid/content/Context;"), "J", 5, public, """
                invoke-virtual { p1 }, $contextManager
                move-result-object v0
                const-string v1, "$own"
                const/4 v2, 0x0
                invoke-virtual { v0, v1, v2 }, $getInfo
                move-result-object v0
                invoke-static { v0 }, $wrap->code($PACKAGE_INFO)J
                move-result-wide v0
                return-wide v0
            """),
            method(crash, "wide", listOf("Landroid/content/Context;"), "J", 20, public, """
                invoke-virtual/range { p1 .. p1 }, $contextManager
                move-result-object v0
                invoke-virtual/range { p1 .. p1 }, $contextName
                move-result-object v1
                const/4 v2, 0x0
                invoke-virtual { v0, v1, v2 }, $getInfo
                move-result-object v16
                invoke-virtual/range { v16 .. v16 }, $LONG_VERSION_CODE
                move-result-wide v0
                return-wide v0
            """),
        )),
        clazz(item, methods = listOf(method(item, "getPackageName", emptyList(), "Ljava/lang/String;", 2, public,
            "const-string v0, \"$own\"\nreturn-object v0"))),
        clazz(other, methods = listOf(
            readOf(other, "foreign", "const-string v1, \"com.whatsapp\""),
            method(other, "named", listOf("Landroid/content/Context;", "Ljava/lang/String;"), "I", 5, public, """
                invoke-virtual { p1 }, $contextManager
                move-result-object v0
                const/4 v1, 0x0
                invoke-virtual { v0, p2, v1 }, $getInfo
                move-result-object v0
                iget v0, v0, $VERSION_CODE_FIELD
                return v0
            """),
            method(other, "given", listOf(PACKAGE_INFO), "I", 3, public, "iget v0, p1, $VERSION_CODE_FIELD\nreturn v0"),
            method(other, "either", listOf("Landroid/content/Context;", "Z"), "I", 6, public, """
                invoke-virtual { p1 }, $contextName
                move-result-object v1
                if-eqz p2, :own
                const-string v1, "com.whatsapp"
                :own
                invoke-virtual { p1 }, $contextManager
                move-result-object v0
                const/4 v2, 0x0
                invoke-virtual { v0, v1, v2 }, $getInfo
                move-result-object v0
                iget v0, v0, $VERSION_CODE_FIELD
                return v0
            """),
            method(other, "item", listOf(item, "Landroid/content/Context;"), "I", 6, public, """
                invoke-virtual { p1 }, $item->getPackageName()Ljava/lang/String;
                move-result-object v1
                invoke-virtual { p2 }, $contextManager
                move-result-object v0
                const/4 v2, 0x0
                invoke-virtual { v0, v1, v2 }, $getInfo
                move-result-object v0
                iget v0, v0, $VERSION_CODE_FIELD
                return v0
            """),
            method(other, "wrapped", listOf(PACKAGE_INFO), "J", 4, public, """
                const/4 v0, 0x0
                invoke-static { p1 }, $wrap->code($PACKAGE_INFO)J
                move-result-wide v0
                return-wide v0
            """),
        )),
        clazz(kept, methods = listOf(
            readOf(kept, "manifest", "const-string v1, \"$own\"", after = "const-string v2, \"Manifest{package=%s, versionCode=%d}\""),
            readOf(kept, "update", "const-string v1, \"$own\"",
                after = "invoke-static { v0 }, Lcom/google/android/play/core/appupdate/UpdateRequest;->code(I)V"),
        )),
        if (extension) statusClass() else null,
        if (extension && hooks != null) helperClass(hooks, hookFlags, stubs) else null,
    )

    /** A method asking for the PackageInfo of the name [nameLoad] loads, reading its code at 6, then running [after]. */
    private fun readOf(type: String, name: String, nameLoad: String, after: String = "nop") =
        method(type, name, listOf("Landroid/content/Context;"), "I", 5, public, """
            invoke-virtual { p1 }, $contextManager
            move-result-object v0
            $nameLoad
            const/4 v2, 0x0
            invoke-virtual { v0, v1, v2 }, $getInfo
            move-result-object v0
            iget v0, v0, $VERSION_CODE_FIELD
            $after
            return v0
        """)

    private fun startClass(type: String, string: String = START_CHECK, name: String? = null, kept: Boolean = false) =
        clazz(type, methods = listOf(method(type, "check", listOf(app), "V", 5, public, """
            invoke-virtual { p1 }, $app->getPackageManager()$manager
            move-result-object v0
            ${if (name == null) "invoke-virtual { p1 }, $app->getPackageName()Ljava/lang/String;" else "const-string v1, \"$name\""}
            ${if (name == null) "move-result-object v1" else "nop"}
            const/4 v2, 0x0
            invoke-virtual { v0, v1, v2 }, $getInfo
            move-result-object v0
            iget v1, v0, $VERSION_CODE_FIELD
            const-string v2, "$string"
            ${if (kept) "const-string v2, \"Manifest{package=%s}\"" else "nop"}
            return-void
        """)))

    /** The extension's SettingsStatus and VersionCode as the bundle carries them, or a VersionCode missing parts. */
    private fun statusClass(): ClassDef = ExtensionDex.classDef(SETTINGS_STATUS)

    private fun helperClass(hooks: List<String>, hookFlags: Int, stubs: List<String>): ClassDef =
        if (hooks == listOf("read", "readLong") && hookFlags == static && stubs == listOf("real", "raised")) {
            ExtensionDex.classDef(helper)
        } else clazz(helper, methods = hooks.map {
            if (it == "read") method(helper, it, listOf(PACKAGE_INFO), "I", 2, hookFlags, "const/4 v0, 0x0\nreturn v0")
            else method(helper, it, listOf(PACKAGE_INFO), "J", 3, hookFlags, "const-wide/16 v0, 0x0\nreturn-wide v0")
        } + stubs.map { method(helper, it, emptyList(), "I", 1, AccessFlags.STATIC.value, "const/4 v0, 0x0\nreturn v0") })

    private fun clazz(type: String, supertype: String = "Ljava/lang/Object;", methods: List<Method> = emptyList()): ClassDef =
        ImmutableClassDef(type, public, supertype, null, null, null, emptyList(), methods)

    private fun method(owner: String, name: String, parameters: List<String>, result: String, registers: Int, flags: Int,
                       body: String): Method = MutableMethod(ImmutableMethod(owner, name,
        parameters.map { ImmutableMethodParameter(it, null, null) }, result, flags, null, null,
        ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, body.trimIndent())
    }.let(ImmutableMethod::of)
}
