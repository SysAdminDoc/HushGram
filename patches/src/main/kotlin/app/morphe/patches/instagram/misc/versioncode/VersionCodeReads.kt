/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.versioncode

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.util.ControlFlow
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val PATCH = "Change version code"

/** The highest version code Android takes, which no build Meta ships comes near. */
internal const val HIGHEST_VERSION_CODE = Int.MAX_VALUE

internal const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"
private const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
private const val STRING = "Ljava/lang/String;"
private const val APPLICATION_INFO = "Landroid/content/pm/ApplicationInfo;"
internal const val VERSION_CODE_FIELD = "$PACKAGE_INFO->versionCode:I"
internal const val LONG_VERSION_CODE = "$PACKAGE_INFO->getLongVersionCode()J"

private const val VERSION_CODE_HELPER = "$EXTENSION_PACKAGE/misc/VersionCode;"
internal const val READ = "$VERSION_CODE_HELPER->read($PACKAGE_INFO)I"
internal const val READ_LONG = "$VERSION_CODE_HELPER->readLong($PACKAGE_INFO)J"

/** Instagram's start-up check of the installed version code against the one built into its dex. */
internal const val START_CHECK = "Android PackageManager returned version code: %d, apk version code is: %d"

/** How the job scheduler's setup starts the warning it logs when the two codes differ. */
internal const val SCHEDULER_CHECK = "Version Codes do not match!"

/**
 * In the text of the check Instagram's component manager makes of the installed package against the
 * manifest of its own APK file. That manifest carries the raised code too, so the read stays.
 */
internal const val MANIFEST_CHECK = "Manifest{package="

/** Google Play's in-app update library, which the raised code keeps from offering Meta's update. */
internal const val PLAY_UPDATES = "Lcom/google/android/play/core/appupdate/"

private val GET_PACKAGE_INFO = setOf(
    "$PACKAGE_MANAGER->getPackageInfo(${STRING}I)$PACKAGE_INFO",
    "$PACKAGE_MANAGER->getPackageInfo(${STRING}Landroid/content/pm/PackageManager\$PackageInfoFlags;)$PACKAGE_INFO",
)
private val CONTEXT_TYPES = setOf(
    "Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/app/Application;",
    "Landroid/app/Activity;", "Landroid/app/Service;",
)
private val PACKAGE_NAME_FIELDS = setOf(
    "$APPLICATION_INFO->packageName:$STRING", "Landroid/content/pm/PackageItemInfo;->packageName:$STRING",
)
private val VIRTUAL_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)
private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val DIRECT_CALLS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)
private val MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val NULLS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

/** How many copies of a value from one register to another a proof follows. */
private const val MAX_MOVES = 8

/** What becomes of a read of a version code. */
internal enum class Verdict {
    /** Asks about Instagram's own package and now answers the code Meta built. */
    REAL,

    /** Asks about Instagram's own package, and the raised code is the one it has to see. */
    KEPT,

    /** Not proven to ask about Instagram's own package: another app's, or one nothing here can trace. */
    OTHER,
}

/**
 * One read of a version code: [PackageInfo.versionCode], `getLongVersionCode()` or a method that
 * only wraps it, at instruction [index] of [method]. [holder] is the register holding the
 * PackageInfo, [result] the field read's destination.
 */
internal class VersionRead(
    val method: Method,
    val index: Int,
    val holder: Int,
    val result: Int,
    val long: Boolean,
    val verdict: Verdict,
    val why: String,
) {
    override fun toString() = "${method.definingClass}->${method.name} @$index ${if (long) "long" else "int"}: $verdict, $why"
}

/** Everything the patch writes, found and checked before any of it is. */
internal class PreparedVersionCode(
    val reads: List<VersionRead>,
    val real: Int,
    private val realStub: MutableMethod,
    private val raisedStub: MutableMethod,
) {
    fun fill() {
        realStub.returnEarly(real)
        raisedStub.returnEarly(HIGHEST_VERSION_CODE)
    }
}

/**
 * The version code Meta built, from what the patcher read off the APK, refused when there's none
 * or it's already the highest, as on a build this patch has raised before.
 */
internal fun realVersionCode(text: String?): Int {
    val code = text?.trim()?.toIntOrNull()
    if (code == null || code <= 0 || code >= HIGHEST_VERSION_CODE) {
        refuse("Instagram's version code is ${text?.let { "\"$it\"" } ?: "missing"}, not one below $HIGHEST_VERSION_CODE")
    }
    return code
}

/**
 * Finds every read of a version code in a class that asks Android for package info, and which of
 * them ask about [ownPackage]. A read counts as Instagram's own only when every value its
 * PackageInfo can hold comes from `PackageManager.getPackageInfo` on a name that is the package
 * name of a Context, of a Context's ApplicationInfo, or [ownPackage] itself, through a branch, a
 * copy or a small helper that hands its one name argument to getPackageInfo. Anything else stays
 * as it is: another app's version, or one this can't trace.
 */
internal fun BytecodePatchContext.versionCodeReads(ownPackage: String): List<VersionRead> {
    val proof = Proof(this, ownPackage)
    val reads = mutableListOf<VersionRead>()
    for (classDef in classesCalling(PACKAGE_MANAGER, "getPackageInfo")) {
        for (method in classDef.methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            val shapes = code.withIndex().mapNotNull { (index, instruction) -> proof.shapeOf(instruction)?.let { index to it } }
            if (shapes.isEmpty()) continue
            val flow = runCatching { Flow(method) }.getOrNull()
            val kept = keptBecause(code)
            for ((index, shape) in shapes) {
                val own = flow != null && proof.ownPackageInfo(flow, shape.holder, index, 0)
                val verdict = when {
                    !own -> Verdict.OTHER
                    kept != null -> Verdict.KEPT
                    else -> Verdict.REAL
                }
                val why = when (verdict) {
                    Verdict.REAL -> "Instagram's own package"
                    Verdict.KEPT -> kept!!
                    Verdict.OTHER -> "not proven to be Instagram's own package"
                }
                reads += VersionRead(method, index, shape.holder, shape.result, shape.long, verdict, why)
            }
        }
    }
    return reads
}

/**
 * Finds the reads, holds them to Instagram's two checks against the code built into its dex, and
 * finds the extension's hooks and stubs. Changes nothing.
 */
internal fun BytecodePatchContext.prepareVersionCode(ownPackage: String, real: Int): PreparedVersionCode {
    requireStatusMethod("versionCode")
    val helper = classDefByOrNull(VERSION_CODE_HELPER) ?: refuse("the extension has no VersionCode class")
    for (hook in listOf(READ, READ_LONG)) {
        if (helper.methods.none { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }) {
            refuse("the extension has no hook $hook")
        }
    }
    val stubs = mutableClassDefBy(VERSION_CODE_HELPER)
    fun stub(name: String) = stubs.methods.singleOrNull {
        it.name == name && it.parameterTypes.isEmpty() && it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("the extension has no stub $name()")
    val realStub = stub("real")
    val raisedStub = stub("raised")

    val reads = versionCodeReads(ownPackage)
    for ((what, holds) in listOf<Pair<String, (String) -> Boolean>>(
        "start-up check" to { it == START_CHECK },
        "job scheduler check" to { it.startsWith(SCHEDULER_CHECK) },
    )) {
        val methods = reads.map { it.method }.distinct().filter { method -> method.strings().any(holds) }
        if (methods.size != 1) refuse("expected Instagram's $what in one method that reads a version code, found ${methods.size}")
        val checked = reads.filter { it.method == methods.single() }
        if (checked.any { it.verdict != Verdict.REAL }) {
            refuse("Instagram's $what reads a version code this can't prove is its own: ${checked.filter { it.verdict != Verdict.REAL }.joinToString()}")
        }
    }
    return PreparedVersionCode(reads.filter { it.verdict == Verdict.REAL }, real, realStub, raisedStub)
}

/**
 * Sends each read [prepared] found through the extension, fills in the two codes and switches the
 * family on. A field read becomes the hook's call and a move-result into the read's own register;
 * a long read's call is swapped for the hook's, and its move-result-wide stays. The call keeps the
 * read's place, so a branch or a try block that held the read holds the call.
 */
internal fun BytecodePatchContext.applyVersionCode(prepared: PreparedVersionCode) {
    prepared.reads.groupBy { it.method }.forEach { (method, reads) ->
        val target = mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
        }
        for (read in reads.sortedByDescending { it.index }) {
            target.replaceInstruction(read.index, call(if (read.long) READ_LONG else READ, read.holder))
            if (!read.long) target.addInstruction(read.index + 1, "move-result v${read.result}")
        }
    }
    prepared.fill()
    enableStatus("versionCode")
}

private fun call(hook: String, register: Int) =
    if (register <= 15) "invoke-static { v$register }, $hook" else "invoke-static/range { v$register .. v$register }, $hook"

/** Why a method's reads of Instagram's own code keep the raised one, or null when they don't. */
private fun keptBecause(code: List<Instruction>): String? = when {
    code.any { instruction -> instruction.string()?.contains(MANIFEST_CHECK) == true } ->
        "held against the manifest of Instagram's own APK file, which is raised too"
    code.any { instruction ->
        when (val reference = (instruction as? ReferenceInstruction)?.reference) {
            is TypeReference -> reference.type.startsWith(PLAY_UPDATES)
            is MethodReference -> reference.definingClass.startsWith(PLAY_UPDATES)
            else -> false
        }
    } -> "sent to Google Play's update check, which is what the raised code is for"
    else -> null
}

/** A read's registers and kind. */
private class Shape(val holder: Int, val result: Int, val long: Boolean)

/** A method's code with where each instruction can be reached from. */
private class Flow(method: Method) {
    private val flow = ControlFlow.of(method)
    val code: List<Instruction> = flow.instructions
    private val into = Array(code.size) { mutableListOf<Pair<Int, Boolean>>() }

    init {
        for (at in code.indices) {
            flow.normal[at].forEach { into[it] += at to false }
            flow.exceptional[at].forEach { into[it] += at to true }
        }
    }

    /** The instructions that can have last written [register] before [at] runs, and whether the method's entry can reach it unwritten. */
    class Writers(val at: Set<Int>, val entry: Boolean)

    /**
     * Walks back from [at] along every path, stopping at each write of [register]. A write reached
     * through an exception handler counts and is walked past too: the instruction that threw never
     * wrote, so the value from before it can be there as well.
     */
    fun writers(register: Int, at: Int): Writers {
        val found = sortedSetOf<Int>()
        var entry = false
        val queued = BooleanArray(code.size)
        val pending = ArrayDeque<Int>()
        fun back(from: Int) {
            if (queued[from]) return
            queued[from] = true
            if (from == 0) entry = true
            pending += from
        }
        back(at)
        while (pending.isNotEmpty()) {
            for ((before, exceptional) in into[pending.removeLast()]) {
                val writes = code[before].writes(register)
                if (writes) found += before
                if (!writes || exceptional) back(before)
            }
        }
        return Writers(found, entry)
    }
}

private class Proof(private val context: BytecodePatchContext, private val ownPackage: String) {
    private val contexts = HashMap<String, Boolean>()
    private val helpers = HashMap<String, Boolean>()
    private val wrappers = HashMap<String, Boolean>()

    /** The read [instruction] makes, or null when it reads no version code. */
    fun shapeOf(instruction: Instruction): Shape? = when (instruction.opcode) {
        Opcode.IGET -> if (instruction.field() == VERSION_CODE_FIELD) {
            (instruction as TwoRegisterInstruction).let { Shape(it.registerB, it.registerA, long = false) }
        } else null
        in VIRTUAL_CALLS -> if (instruction.method()?.toString() == LONG_VERSION_CODE) {
            instruction.arguments()?.singleOrNull()?.let { Shape(it, -1, long = true) }
        } else null
        in STATIC_CALLS -> if (instruction.method()?.let(::wrapsLongRead) == true) {
            instruction.arguments()?.singleOrNull()?.let { Shape(it, -1, long = true) }
        } else null
        else -> null
    }

    /** Whether every PackageInfo [register] can hold at [at] is Instagram's own. */
    fun ownPackageInfo(flow: Flow, register: Int, at: Int, moves: Int): Boolean {
        val writers = flow.writers(register, at)
        if (writers.entry || writers.at.isEmpty()) return false
        return writers.at.all { index ->
            val instruction = flow.code[index]
            when (instruction.opcode) {
                Opcode.MOVE_RESULT_OBJECT -> index > 0 && ownPackageInfoCall(flow, index - 1)
                in MOVES -> moves < MAX_MOVES &&
                    ownPackageInfo(flow, (instruction as TwoRegisterInstruction).registerB, index, moves + 1)
                else -> false
            }
        }
    }

    private fun ownPackageInfoCall(flow: Flow, at: Int): Boolean {
        val call = flow.code[at]
        val reference = call.method() ?: return false
        val arguments = call.arguments() ?: return false
        if (reference.returnType != PACKAGE_INFO) return false
        if (reference.toString() in GET_PACKAGE_INFO) {
            return call.opcode in VIRTUAL_CALLS && ownPackageName(flow, arguments[1], at, 0)
        }
        val static = call.opcode in STATIC_CALLS
        if (!static && call.opcode !in DIRECT_CALLS) return false
        if (reference.parameterTypes.map(Any::toString) != listOf(STRING)) return false
        return asksForItsArgument(reference, static) && ownPackageName(flow, arguments[if (static) 0 else 1], at, 0)
    }

    /** Whether every name [register] can hold at [at] is Instagram's own package name. */
    private fun ownPackageName(flow: Flow, register: Int, at: Int, moves: Int): Boolean {
        val writers = flow.writers(register, at)
        if (writers.entry || writers.at.isEmpty()) return false
        return writers.at.all { index ->
            val instruction = flow.code[index]
            when (instruction.opcode) {
                Opcode.MOVE_RESULT_OBJECT -> index > 0 && flow.code[index - 1].let { call ->
                    val reference = call.method()
                    call.opcode in VIRTUAL_CALLS && reference != null && reference.name == "getPackageName" &&
                        reference.parameterTypes.isEmpty() && reference.returnType == STRING && isContext(reference.definingClass)
                }
                Opcode.IGET_OBJECT -> instruction.field() in PACKAGE_NAME_FIELDS &&
                    ownApplicationInfo(flow, (instruction as TwoRegisterInstruction).registerB, index, 0)
                Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> instruction.string() == ownPackage
                in MOVES -> moves < MAX_MOVES &&
                    ownPackageName(flow, (instruction as TwoRegisterInstruction).registerB, index, moves + 1)
                else -> false
            }
        }
    }

    /** Whether every ApplicationInfo [register] can hold at [at] is a Context's own. */
    private fun ownApplicationInfo(flow: Flow, register: Int, at: Int, moves: Int): Boolean {
        val writers = flow.writers(register, at)
        if (writers.entry || writers.at.isEmpty()) return false
        return writers.at.all { index ->
            val instruction = flow.code[index]
            when (instruction.opcode) {
                Opcode.MOVE_RESULT_OBJECT -> index > 0 && flow.code[index - 1].let { call ->
                    val reference = call.method()
                    call.opcode in VIRTUAL_CALLS && reference != null && reference.name == "getApplicationInfo" &&
                        reference.parameterTypes.isEmpty() && reference.returnType == APPLICATION_INFO &&
                        isContext(reference.definingClass)
                }
                in MOVES -> moves < MAX_MOVES &&
                    ownApplicationInfo(flow, (instruction as TwoRegisterInstruction).registerB, index, moves + 1)
                else -> false
            }
        }
    }

    /** Whether [type] is Android's Context or one of the app's classes built on it. */
    private fun isContext(type: String): Boolean = contexts.getOrPut(type) {
        var current: String? = type
        var steps = 0
        while (current != null && steps++ < 32) {
            if (current in CONTEXT_TYPES) return@getOrPut true
            current = context.classDefByOrNull(current)?.superclass
        }
        false
    }

    /**
     * Whether [reference] is the app's own method that hands its one name argument, unchanged, to
     * getPackageInfo and returns what comes back, or null.
     */
    private fun asksForItsArgument(reference: MethodReference, static: Boolean): Boolean = helpers.getOrPut(reference.toString()) {
        val helper = context.classDefByOrNull(reference.definingClass)?.methods?.singleOrNull {
            it.name == reference.name && it.returnType == PACKAGE_INFO && it.implementation != null &&
                it.parameterTypes.map(Any::toString) == listOf(STRING) && AccessFlags.STATIC.isSet(it.accessFlags) == static
        } ?: return@getOrPut false
        val flow = runCatching { Flow(helper) }.getOrNull() ?: return@getOrPut false
        val name = helper.parameterRegisterNumber(0)
        var asks = false
        val returns = flow.code.indices.filter { flow.code[it].opcode == Opcode.RETURN_OBJECT }
        val answers = returns.isNotEmpty() && returns.all { at ->
            val writers = flow.writers((flow.code[at] as OneRegisterInstruction).registerA, at)
            !writers.entry && writers.at.isNotEmpty() && writers.at.all { index ->
                val instruction = flow.code[index]
                when {
                    instruction.opcode in NULLS -> (instruction as NarrowLiteralInstruction).narrowLiteral == 0
                    instruction.opcode == Opcode.MOVE_RESULT_OBJECT && index > 0 -> {
                        val call = flow.code[index - 1]
                        val argument = call.arguments()?.getOrNull(1)
                        val untouched = argument == name && flow.writers(name, index - 1).let { it.entry && it.at.isEmpty() }
                        (call.opcode in VIRTUAL_CALLS && call.method()?.toString() in GET_PACKAGE_INFO && untouched).also { asks = asks || it }
                    }
                    else -> false
                }
            }
        }
        answers && asks
    }

    /** Whether [reference] is a static method of the app that only returns its PackageInfo's getLongVersionCode(). */
    private fun wrapsLongRead(reference: MethodReference): Boolean = wrappers.getOrPut(reference.toString()) {
        if (reference.returnType != "J" || reference.parameterTypes.map(Any::toString) != listOf(PACKAGE_INFO)) return@getOrPut false
        val wrapper = context.classDefByOrNull(reference.definingClass)?.methods?.singleOrNull {
            it.name == reference.name && it.returnType == "J" && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypes.map(Any::toString) == listOf(PACKAGE_INFO)
        } ?: return@getOrPut false
        val code = wrapper.implementation?.instructions?.toList() ?: return@getOrPut false
        code.size == 3 && code[0].opcode in VIRTUAL_CALLS && code[0].method()?.toString() == LONG_VERSION_CODE &&
            code[0].arguments() == listOf(wrapper.parameterRegisterNumber(0)) &&
            code[1].opcode == Opcode.MOVE_RESULT_WIDE && code[2].opcode == Opcode.RETURN_WIDE &&
            (code[1] as OneRegisterInstruction).registerA == (code[2] as OneRegisterInstruction).registerA
    }
}

/** Whether running this writes [register], as either half of a wide value too. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

/** The registers a call passes, in order, a wide value taking two. */
private fun Instruction.arguments(): List<Int>? = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> null
}

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.field(): String? = ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString()
private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Method.strings(): List<String> = implementation?.instructions?.mapNotNull { it.string() }.orEmpty()

internal fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
