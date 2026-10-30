/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.coexist

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

/*
 * Instagram's code names its own package about 130 times, by a string or by a number it asks one
 * of Redex's string pools for, and the copy has to tell those apart. Where the string means the
 * running app (a question to PackageManager about itself, an intent aimed at itself, a check that
 * a caller or process is itself, the name Google Play's services are told) it has to become the
 * copy's, or the copy would read, open or trust the installed Instagram. Where it means Instagram
 * the product (a Play Store page, a list of Meta's apps, the sender named to another Meta app, a
 * link's source) it stays. Every load is sorted by what reads it, never by where it sits, since
 * Redex names the classes differently in each build.
 *
 * Every provider authority Instagram declares moves with the manifest, so every string that names
 * one, bare or as a content URI, moves too: the providers' own matchers and the code that reaches
 * them both use it.
 */

/** A use of Instagram's package name that means the running app. */
internal enum class SelfUse(val what: String) {
    INLINED("getPackageName() call Redex answered at build time"),
    PACKAGE_MANAGER("PackageManager question about the running app"),
    OWN_INTENT("intent or component aimed at the running app"),
    GOOGLE_PLAY("package name handed to Google Play's billing, update or split install service"),
    NAME_CHECK("check against the running app's package or process name"),
    CALLER_CHECK("check that an intent or a caller came from the running app"),
    PROVIDER_OWNER("owner named beside a provider authority that moves"),
    RELEASE_AND_DEBUG("list of the running app's release and debug names"),
    DEX_LOAD_REPORT("package name handed to the report that tells the system which app loaded these dex files"),
}

/**
 * One load of Instagram's package name or of one of its authorities. [to] is what it becomes, or
 * null when it stays; [why] says which.
 */
internal class CloneSite(
    val type: String,
    val method: String,
    val index: Int,
    val register: Int,
    /** A pool's answer moved into [register], rather than a string loaded there. */
    val pooled: Boolean,
    val from: String,
    val to: String?,
    val why: String,
    val use: SelfUse? = null,
) {
    override fun toString() = "$type->$method@$index $from -> ${to ?: "kept"} ($why)"
}

internal class ClonePlan(val clone: String, val sites: List<CloneSite>, val ownPackage: CloneSite?) {
    val rewrites get() = sites.filter { it.to != null }

    /** The loads of Instagram's own package that stay as they are. */
    val kept get() = sites.filter { it.to == null && it.from == INSTAGRAM }

    /** The loads that name a provider authority. Pooled ones follow the pool's answer, which moves itself. */
    val authorities get() = sites.filter { it.from != INSTAGRAM }

    fun count(use: SelfUse) = sites.count { it.use == use }

    /** How many sites of each sort, to tell whether the code changed between two looks at it. */
    fun summary(): Map<String, Int> = sites.groupingBy { it.use?.name ?: if (it.to == null) "kept ${it.from == INSTAGRAM}" else "authority" }
        .eachCount().toSortedMap()

    /** Refuses unless every sort of use the copy depends on was found, and the extension's answer with them. */
    fun requireComplete() {
        if (sites.none { it.from == INSTAGRAM }) refuse("found no code that loads $INSTAGRAM")
        SelfUse.entries.firstOrNull { count(it) == 0 }?.let { refuse("found no ${it.what}") }
        if (rewrites.none { it.use == null }) refuse("found no code that names one of Instagram's provider authorities")
        if (ownPackage == null) refuse("the extension's OwnPackage.name() doesn't load $INSTAGRAM once")
    }
}

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
private val PACKAGE_QUESTIONS = setOf("getApplicationInfo", "getPackageInfo", "getInstallerPackageName", "getInstallSourceInfo")
private val STRING_COMPARES = setOf("equals", "equalsIgnoreCase", "contains", "startsWith")
private val NAME_SOURCES = setOf("getPackageName", "getProcessName", "currentProcessName")
private val CALLER_SOURCES = setOf(
    "Landroid/content/Intent;->getPackage()$STRING",
    "Landroid/app/Activity;->getCallingPackage()$STRING",
)
private const val PROCESS_NAME_FIELD = "processName"

/** Dextricks' native helper that reports a class loader chain to the system on the loading app's behalf. */
private const val DEX_REPORT_ARROW = "Lcom/facebook/common/dextricks/achilles/Achilles\$Arrow;"

private fun Method.signature() = "$name(${parameterTypes.joinToString("")})$returnType"

private fun MethodReference.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.isStringLoad() = opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO

private fun Instruction.string() = ((this as ReferenceInstruction).reference as StringReference).string

private fun isPool(method: Method, code: List<Instruction>) =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == STRING &&
        method.parameterTypes.map(Any::toString) == listOf("I") &&
        code.any { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }

/** The index of each instruction by its code address. */
private fun addresses(code: List<Instruction>): Pair<IntArray, Map<Int, Int>> {
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    return address to (0 until code.size).associateBy { address[it] }
}

private class Load(val text: String, val register: Int, val pooled: Boolean)

/** What Redex's static `(int)String` pools answer, each read once. */
private class Pools(private val context: BytecodePatchContext) {
    private val tables = HashMap<String, Map<Int, String>>()

    fun answer(pool: MethodReference, number: Int): String? = tables.getOrPut("${pool.definingClass}->${pool.name}") {
        val method = context.classDefByOrNull(pool.definingClass)?.methods?.singleOrNull {
            it.name == pool.name && it.returnType == STRING && it.parameterTypes.map(Any::toString) == listOf("I")
        }
        method?.implementation?.instructions?.toList()?.let { answers(it) }.orEmpty()
    }[number]

    /** The first switch whose case for a number loads a string decides that number's answer. */
    private fun answers(code: List<Instruction>): Map<Int, String> {
        val (address, index) = addresses(code)
        val answers = HashMap<Int, String>()
        for (switch in code.indices) {
            if (code[switch].opcode != Opcode.PACKED_SWITCH && code[switch].opcode != Opcode.SPARSE_SWITCH) continue
            val payload = index[address[switch] + (code[switch] as OffsetInstruction).codeOffset]?.let { code[it] } as? SwitchPayload
                ?: continue
            for (case in payload.switchElements) {
                if (case.key in answers) continue
                val load = index[address[switch] + case.offset]?.let { code[it] } ?: continue
                if (load.isStringLoad()) answers[case.key] = load.string()
            }
        }
        return answers
    }

    /**
     * The string [code] puts in a register at [at]: a const-string's, or a pool's answer moved
     * there right after the pool was asked for a constant number. Null for anything else.
     */
    fun loadAt(code: List<Instruction>, at: Int): Load? {
        val load = code[at]
        if (load.isStringLoad()) return Load(load.string(), (load as OneRegisterInstruction).registerA, false)
        if (load.opcode != Opcode.MOVE_RESULT_OBJECT || at < 2) return null
        val call = code[at - 1]
        val pool = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return null
        if (call.opcode != Opcode.INVOKE_STATIC || pool.returnType != STRING || pool.parameterTypes.map(Any::toString) != listOf("I")) return null
        val number = code[at - 2] as? NarrowLiteralInstruction ?: return null
        if ((number as OneRegisterInstruction).registerA != (call as FiveRegisterInstruction).registerC) return null
        val text = answer(pool, number.narrowLiteral) ?: return null
        return Load(text, (load as OneRegisterInstruction).registerA, true)
    }
}

private class Verdict(val use: SelfUse?, val why: String, val comparedElsewhere: Boolean = false)

private fun arguments(instruction: Instruction): List<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG).take(instruction.registerCount)
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    else -> emptyList()
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val first = (this as? OneRegisterInstruction)?.registerA ?: return false
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}

private fun Instruction.reads(register: Int): Boolean {
    if (this is FiveRegisterInstruction || this is RegisterRangeInstruction) return register in arguments(this)
    return (!opcode.setsRegister() && this is OneRegisterInstruction && registerA == register) ||
        (this is TwoRegisterInstruction && registerB == register) ||
        (this is ThreeRegisterInstruction && registerC == register)
}

private val ENDS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE, Opcode.THROW)
private val JUMPS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/** Every instruction reachable from [at] that reads [register] before anything writes it again, in order. */
private fun readers(code: List<Instruction>, at: Int, register: Int): List<Int> {
    val (address, index) = addresses(code)
    val found = sortedSetOf<Int>()
    val seen = HashSet<Int>()
    val work = ArrayDeque(listOf(at + 1))
    while (work.isNotEmpty() && seen.size < 600) {
        val next = work.removeFirst()
        if (next !in code.indices || !seen.add(next)) continue
        val instruction = code[next]
        if (instruction.reads(register)) found += next
        if (instruction.writes(register) || instruction.opcode in ENDS) continue
        if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
            val payload = index[address[next] + (instruction as OffsetInstruction).codeOffset]?.let { code[it] } as? SwitchPayload
            payload?.switchElements?.forEach { case -> index[address[next] + case.offset]?.let(work::addLast) }
        } else if (instruction is OffsetInstruction) {
            index[address[next] + instruction.codeOffset]?.let(work::addLast)
            if (instruction.opcode in JUMPS) continue
        }
        work.addLast(next + 1)
    }
    return found.toList()
}

/** The method or field the value in [register] came from at [at], looking a few instructions back. */
private fun sourceOf(code: List<Instruction>, at: Int, register: Int): String? {
    for (back in at - 1 downTo maxOf(0, at - 16)) {
        val instruction = code[back]
        if (!instruction.writes(register)) continue
        if (instruction.opcode == Opcode.IGET_OBJECT &&
            ((instruction as ReferenceInstruction).reference as FieldReference).name == PROCESS_NAME_FIELD
        ) {
            return PROCESS_NAME_FIELD
        }
        if (instruction.opcode != Opcode.MOVE_RESULT_OBJECT || back == 0) return null
        return ((code[back - 1] as? ReferenceInstruction)?.reference as? MethodReference)?.descriptor()
    }
    return null
}

private fun isPackageNameCall(instruction: Instruction): Boolean {
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val called = (instruction as ReferenceInstruction).reference as MethodReference
    return called.name == "getPackageName" && called.parameterTypes.isEmpty() && called.returnType == STRING
}

private class Sorter(private val pools: Pools, private val authorities: Collection<String>) {
    /** The authority [text] names, bare or as a content URI, or null. */
    fun authorityIn(text: String): String? = authorities.firstOrNull { authority ->
        text == authority || text == "content://$authority" || text.startsWith("content://$authority/")
    }

    /** The string [code] holds in [register] at [at], when it was loaded a few instructions before. */
    private fun stringIn(code: List<Instruction>, at: Int, register: Int): String? {
        for (back in at - 1 downTo maxOf(0, at - 12)) {
            if (code[back].writes(register)) return pools.loadAt(code, back)?.text
        }
        return null
    }

    fun sort(code: List<Instruction>, at: Int, register: Int): Verdict {
        // Redex replaced the call with its answer and left the call behind, unread.
        for (near in maxOf(0, at - 4)..minOf(code.lastIndex, at + 4)) {
            if (isPackageNameCall(code[near]) && code.getOrNull(near + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) {
                return Verdict(SelfUse.INLINED, SelfUse.INLINED.what)
            }
        }
        val verdicts = readers(code, at, register).map { use(code, it, register) }
        if (verdicts.isEmpty()) return Verdict(null, "nothing reads it")
        val self = verdicts.firstOrNull { it.use != null }
        val elsewhere = verdicts.firstOrNull { it.comparedElsewhere }
        return when {
            self != null && elsewhere != null -> Verdict(null, "read as the running app and also ${elsewhere.why}")
            self != null -> self
            else -> verdicts.first()
        }
    }

    private fun use(code: List<Instruction>, at: Int, register: Int): Verdict {
        val instruction = code[at]
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        if (called == null) {
            if (instruction.opcode == Opcode.FILLED_NEW_ARRAY &&
                arguments(instruction).any { it != register && stringIn(code, at, it) == "$INSTAGRAM.debug" }
            ) {
                return Verdict(SelfUse.RELEASE_AND_DEBUG, SelfUse.RELEASE_AND_DEBUG.what)
            }
            val what = if (instruction.opcode == Opcode.FILLED_NEW_ARRAY || instruction.opcode == Opcode.FILLED_NEW_ARRAY_RANGE) {
                "a list of packages"
            } else {
                instruction.opcode.name
            }
            return Verdict(null, "read by $what")
        }
        val arguments = arguments(instruction)
        val position = arguments.indexOf(register)
        val first = called.parameterTypes.firstOrNull()?.toString()
        val self = when {
            called.definingClass == PACKAGE_MANAGER && called.name in PACKAGE_QUESTIONS && position == 1 -> SelfUse.PACKAGE_MANAGER
            called.definingClass == "Landroid/content/Intent;" && position == 1 &&
                (called.name == "setPackage" || called.name == "setClassName" && first == STRING) -> SelfUse.OWN_INTENT
            called.definingClass == "Landroid/content/ComponentName;" && called.name == "<init>" && position == 1 &&
                first == STRING -> SelfUse.OWN_INTENT
            // Redex answered the caller's getPackageName() at build time and wrote the answer over the
            // parameter, so the call is gone from the callee. The report names the loading package.
            called.definingClass == DEX_REPORT_ARROW && called.name == "<init>" && position >= 1 -> SelfUse.DEX_LOAD_REPORT
            called.definingClass == "Landroid/os/Parcel;" && called.name == "writeString" && position == 1 -> SelfUse.GOOGLE_PLAY
            called.definingClass == "Landroid/os/BaseBundle;" && called.name == "putString" && position == 2 &&
                stringIn(code, at, arguments[1]) == "package.name" -> SelfUse.GOOGLE_PLAY
            else -> null
        }
        if (self != null) return Verdict(self, self.what)

        val compares = called.definingClass == STRING && called.name in STRING_COMPARES ||
            instruction.opcode == Opcode.INVOKE_STATIC && called.returnType == "Z" &&
            called.parameterTypes.map(Any::toString) == listOf(OBJECT, OBJECT)
        if (compares && arguments.size == 2 && position >= 0) {
            val source = sourceOf(code, at, arguments[1 - position])
            return when {
                source == PROCESS_NAME_FIELD || source != null && source.substringAfter("->").substringBefore('(') in NAME_SOURCES &&
                    source.endsWith("()$STRING") -> Verdict(SelfUse.NAME_CHECK, SelfUse.NAME_CHECK.what)
                source != null && source in CALLER_SOURCES -> Verdict(SelfUse.CALLER_CHECK, SelfUse.CALLER_CHECK.what)
                else -> Verdict(null, "compared with ${source ?: "a value this can't trace"}", comparedElsewhere = true)
            }
        }
        if (arguments.any { it != register && stringIn(code, at, it)?.let { text -> authorityIn(text) } != null }) {
            return Verdict(SelfUse.PROVIDER_OWNER, SelfUse.PROVIDER_OWNER.what)
        }
        return Verdict(null, "passed to ${called.definingClass}->${called.name}")
    }
}

/** [text], which names [authority], naming the copy's authority instead. */
private fun movedAuthority(text: String, authority: String, clone: String) =
    if (text == authority) cloneAuthority(authority, clone)
    else "content://" + cloneAuthority(authority, clone) + text.removePrefix("content://$authority")

/**
 * Sorts every load of Instagram's package name and of [authorities] in the app's code, and finds
 * the extension's own answer, without changing anything.
 */
internal fun BytecodePatchContext.planClone(clone: String, authorities: Collection<String>): ClonePlan {
    val pools = Pools(this)
    val sorter = Sorter(pools, authorities)
    val types = LinkedHashSet<String>()
    classesLoadingString(INSTAGRAM).mapTo(types) { it.type }
    // A prefix lookup reads the patcher's whole string index, once per authority (#173). Asking once
    // for every content URI and sorting the answer here gives each authority the same classes, since
    // the index holds exactly a class's const-string loads, which is what this reads back.
    val uris = classDefByStrings("content://", StringComparisonType.STARTS_WITH).map { classDef ->
        classDef.type to classDef.methods.flatMap { method ->
            method.implementation?.instructions?.filter { it.isStringLoad() }?.map { it.string() }.orEmpty()
        }.filter { it.startsWith("content://") }
    }
    for (authority in authorities) {
        classDefByStrings(authority, StringComparisonType.EQUALS).mapTo(types) { it.type }
        val prefix = "content://$authority"
        uris.filter { (_, texts) -> texts.any { it.startsWith(prefix) } }.mapTo(types) { (type, _) -> type }
    }
    types.removeAll { it.startsWith(EXTENSION_ROOT) }

    val sites = mutableListOf<CloneSite>()
    for (type in types) for (method in classDefBy(type).methods) {
        val code = method.implementation?.instructions?.toList() ?: continue
        val pool = isPool(method, code)
        for (at in code.indices) {
            val load = pools.loadAt(code, at) ?: continue
            fun site(to: String?, why: String, use: SelfUse? = null) =
                CloneSite(type, method.signature(), at, load.register, load.pooled, load.text, to, why, use)
            val authority = sorter.authorityIn(load.text)
            sites += when {
                authority != null && load.pooled -> site(null, "an authority asked of a string pool, whose answer moves")
                authority != null -> site(movedAuthority(load.text, authority, clone), "a provider authority")
                load.text != INSTAGRAM -> continue
                pool && !load.pooled -> site(null, "a string pool's answer, which each caller sorts on its own")
                else -> sorter.sort(code, at, load.register).let { site(it.use?.let { clone }, it.why, it.use) }
            }
        }
    }
    return ClonePlan(clone, sites, ownPackageSite(clone))
}

/** The extension's OwnPackage.name(), which loads Instagram's package once, or null. */
private fun BytecodePatchContext.ownPackageSite(clone: String): CloneSite? {
    val method = classDefByOrNull(OWN_PACKAGE_CLASS)?.methods?.singleOrNull {
        it.name == "name" && it.parameterTypes.isEmpty() && it.returnType == STRING && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val loads = code.indices.filter { code[it].isStringLoad() && code[it].string() == INSTAGRAM }
    val at = loads.singleOrNull() ?: return null
    return CloneSite(OWN_PACKAGE_CLASS, method.signature(), at, (code[at] as OneRegisterInstruction).registerA, false,
        INSTAGRAM, clone, "the extension's answer to which package is running")
}

/**
 * Writes the copy's name into every site of [plan] that moves, and into the extension's answer.
 * Each site is checked against the code first, so a refusal changes nothing.
 */
internal fun BytecodePatchContext.applyClone(plan: ClonePlan) {
    val sites = plan.rewrites + listOfNotNull(plan.ownPackage)
    val targets = sites.groupBy { it.type }.flatMap { (type, inClass) ->
        val classDef = mutableClassDefBy(type)
        inClass.map { site ->
            val method = classDef.methods.singleOrNull { it.signature() == site.method } ?: refuse("$type has no ${site.method} any more")
            val now = method.implementation?.instructions?.toList()?.getOrNull(site.index)
            val still = now != null && (now as? OneRegisterInstruction)?.registerA == site.register && if (site.pooled) {
                now.opcode == Opcode.MOVE_RESULT_OBJECT
            } else {
                now.isStringLoad() && now.string() == site.from
            }
            if (!still) refuse("the code changed under $site")
            method to site
        }
    }
    for ((method, site) in targets) {
        method.replaceInstruction(site.index, BuilderInstruction21c(Opcode.CONST_STRING, site.register, ImmutableStringReference(site.to!!)))
    }
}
