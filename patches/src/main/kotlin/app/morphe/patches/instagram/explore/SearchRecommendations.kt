/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.feed.back.enumConstantName
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide the Explore grid"

internal const val SEARCH_RECOMMENDATIONS = "$EXTENSION_PACKAGE/explore/SearchRecommendations;"
internal const val RECOMMENDATION_UNITS = "$SEARCH_RECOMMENDATIONS->units(Ljava/lang/Object;)Ljava/lang/Object;"

/** The stub the patch fills with Instagram's own check of a results unit. */
internal const val SEARCH_RECOMMENDATION_READER = "$EXTENSION_PACKAGE/explore/SearchRecommendationReader;"
internal const val IS_RECOMMENDATION = "isRecommendation"
internal const val IS_RECOMMENDATION_SHAPE = "(Ljava/lang/Object;)Z"

/** The second stub, filled with a test of a unit's kind against [HEADER_KIND]. Same shape as the first. */
internal const val IS_HEADER = "isHeader"

/**
 * The kind of results unit that's a section's title. The You may also like title is one, a unit of
 * its own right before the block, and both builders look at the unit after a title to see whether
 * it's the block's.
 */
internal const val HEADER_KIND = "HEADER"

private const val ENUM = "Ljava/lang/Enum;"

/**
 * The marker Instagram's server puts on the You may also like unit at the end of search results.
 * Only the check of a unit for it and a few logging classes load it.
 */
internal const val END_OF_SERP_RECOMMENDATION = "end_of_serp_recommendation"

private const val UNITS_LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"

/** The Kotlin lambda bridge every merged lambda class carries, `invoke(Object, Object)Object`. */
internal const val LAMBDA_INVOKE = "invoke"
internal const val LAMBDA_SHAPE = "($OBJECT$OBJECT)$OBJECT"

private val COPIES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * Instagram's check of a results unit for [END_OF_SERP_RECOMMENDATION] (as a smali reference), the
 * unit's class, the getter of its kind and the [HEADER_KIND] constant (both smali references), and
 * the two builders of the results page it's asked in: a lambda handed the list of units as its
 * second argument, and a static builder taking the list as its one list parameter.
 */
internal class SearchRecommendationSites(
    val predicate: String,
    val unitType: String,
    val kindGetter: String,
    val headerKind: String,
    val lambdaType: String,
    val lambdaRegister: Int,
    val builderType: String,
    val builderName: String,
    val builderParameters: List<String>,
    val builderRegister: Int,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the one static check taking one unit and answering a boolean that loads
 * [END_OF_SERP_RECOMMENDATION], then every method outside its class asking it. Two of them build
 * the results page out of the list of units: the merged lambda whose `invoke` casts its second
 * argument to a list before its first branch, and the static method with one list parameter. Other
 * askers (a timing logger on 454) take no list and are left alone. The check, its class and the
 * unit's class are public, since the extension asks it, the extension carries the stub the check
 * goes in, and nothing may jump back to either builder's start, where the hook goes. Fails before
 * anything changes when any of it isn't so, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findSearchRecommendations(): SearchRecommendationSites {
    val checks = classesLoadingString(END_OF_SERP_RECOMMENDATION).flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.parameterTypes.size == 1 &&
                loadsString(method, END_OF_SERP_RECOMMENDATION)
        }
    }
    val check = checks.singleOrNull()
        ?: refuse("expected one static check of a results unit loading $END_OF_SERP_RECOMMENDATION, found ${checks.size}")
    val where = "${check.definingClass}->${check.name}"
    val unitType = check.parameterTypes.single().toString()
    val owner = classDefByOrNull(check.definingClass)
    val unit = classDefByOrNull(unitType)
    if (owner == null || unit == null || !AccessFlags.PUBLIC.isSet(owner.accessFlags) ||
        !AccessFlags.PUBLIC.isSet(check.accessFlags) || !AccessFlags.PUBLIC.isSet(unit.accessFlags)
    ) {
        refuse("$where or its unit $unitType isn't public")
    }

    // The check reads the unit's kind (an enum) before its marker; the title kind is the enum's HEADER.
    val kindReads = check.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return@mapNotNull null
        val called = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        called.takeIf {
            it.definingClass == unitType && it.parameterTypes.isEmpty() && classDefByOrNull(it.returnType)?.superclass == ENUM
        }
    }.distinctBy { it.toString() }
    val kindRead = kindReads.singleOrNull() ?: refuse("expected $where to read one kind of its unit, found ${kindReads.size}")
    val kind = classDefByOrNull(kindRead.returnType)!!
    val getter = unit.methods.singleOrNull { it.name == kindRead.name && it.shapeText() == "()${kindRead.returnType}" }
    val headers = kind.fields.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.type == kind.type && enumConstantName(kind, it) == HEADER_KIND
    }
    val header = headers.singleOrNull() ?: refuse("expected one $HEADER_KIND kind in ${kind.type}, found ${headers.size}")
    if (getter == null || !AccessFlags.PUBLIC.isSet(getter.accessFlags) || !AccessFlags.PUBLIC.isSet(kind.accessFlags) ||
        !AccessFlags.PUBLIC.isSet(header.accessFlags)
    ) {
        refuse("$kindRead or ${kind.type}'s $HEADER_KIND isn't public")
    }

    val reader = classDefByOrNull(SEARCH_RECOMMENDATION_READER) ?: refuse("the extension has no $SEARCH_RECOMMENDATION_READER")
    for (name in listOf(IS_RECOMMENDATION, IS_HEADER)) {
        val stub = reader.methods.singleOrNull {
            it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.shapeText() == IS_RECOMMENDATION_SHAPE
        } ?: refuse("$SEARCH_RECOMMENDATION_READER has no static $name$IS_RECOMMENDATION_SHAPE")
        if (stub.implementation == null) refuse("$SEARCH_RECOMMENDATION_READER->$name has no code to fill")
    }

    val askers = classesCalling(check.definingClass, check.name, listOf(unitType), "Z")
        .filter { it.type != check.definingClass }
        .flatMap { classDef -> classDef.methods.filter { it.asks(check) } }
    val lambdas = askers.filter { it.name == LAMBDA_INVOKE && it.shapeText() == LAMBDA_SHAPE }
    val lambda = lambdas.singleOrNull()
        ?: refuse("expected one lambda asking $where, found ${lambdas.size}")
    if (!lambda.castsToListFirst(1)) refuse("${lambda.definingClass}->invoke doesn't cast its second argument to a list first")
    val builders = askers.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.count { type -> type.toString() == UNITS_LIST } == 1
    }
    val builder = builders.singleOrNull()
        ?: refuse("expected one static builder taking a list and asking $where, found ${builders.size}")
    for (method in listOf(lambda, builder)) {
        if (0 in method.jumpTargets()) refuse("something in ${method.definingClass}->${method.name} jumps back to its start")
    }

    val builderParameters = builder.parameterTypes.map(CharSequence::toString)
    return SearchRecommendationSites(
        "$where($unitType)Z", unitType, kindRead.toString(), "${kind.type}->${header.name}:${kind.type}",
        lambda.definingClass, lambda.parameterRegisterNumber(1),
        builder.definingClass, builder.name, builderParameters, builder.parameterRegisterNumber(builderParameters.indexOf(UNITS_LIST)),
    )
}

/**
 * Fills [SEARCH_RECOMMENDATION_READER]'s stubs with Instagram's check and a test of the unit's kind
 * against [HEADER_KIND], each asked only of a unit of its class, and starts both builders by passing
 * their list of units through [RECOMMENDATION_UNITS], which hands back the list without the You may
 * also like units and their title while the switch is on. The builder's parameter is a list, so the
 * answer is cast back to one; the lambda's is an object already.
 */
internal fun BytecodePatchContext.hideSearchRecommendations(sites: SearchRecommendationSites) {
    // D8 compiles a stub's `return false` into p0 alone, so each gets the locals it answers in.
    val methods = mutableClassDefBy(SEARCH_RECOMMENDATION_READER).methods
    fun fill(name: String, locals: Int, code: String) {
        val original = methods.single { it.name == name && it.shapeText() == IS_RECOMMENDATION_SHAPE }
        val stub = original.cloneMutable(additionalRegisters = (locals + 1 - original.implementation!!.registerCount).coerceAtLeast(0))
        methods.remove(original)
        methods.add(stub)
        stub.addInstructionsWithLabels(0, code)
    }
    fill(
        IS_RECOMMENDATION, 1,
        """
            instance-of v0, p0, ${sites.unitType}
            if-eqz v0, :not_a_unit
            check-cast p0, ${sites.unitType}
            invoke-static { p0 }, ${sites.predicate}
            move-result v0
            :not_a_unit
            return v0
        """,
    )
    fill(
        IS_HEADER, 2,
        """
            const/4 v0, 0x0
            instance-of v1, p0, ${sites.unitType}
            if-eqz v1, :not_a_header
            check-cast p0, ${sites.unitType}
            invoke-virtual { p0 }, ${sites.kindGetter}
            move-result-object v1
            sget-object p0, ${sites.headerKind}
            if-ne v1, p0, :not_a_header
            const/4 v0, 0x1
            :not_a_header
            return v0
        """,
    )

    val lambda = mutableClassDefBy(sites.lambdaType).methods.single { it.name == LAMBDA_INVOKE && it.shapeText() == LAMBDA_SHAPE }
    lambda.addInstructions(
        0,
        """
            invoke-static/range { v${sites.lambdaRegister} .. v${sites.lambdaRegister} }, $RECOMMENDATION_UNITS
            move-result-object v${sites.lambdaRegister}
        """,
    )

    val builder = mutableClassDefBy(sites.builderType).methods.single {
        it.name == sites.builderName && it.parameterTypes.map(CharSequence::toString) == sites.builderParameters
    }
    builder.addInstructions(
        0,
        """
            invoke-static/range { v${sites.builderRegister} .. v${sites.builderRegister} }, $RECOMMENDATION_UNITS
            move-result-object v${sites.builderRegister}
            check-cast v${sites.builderRegister}, $UNITS_LIST
        """,
    )
}

/** Whether the method's code calls [check] itself. */
private fun Method.asks(check: MethodReference): Boolean =
    implementation?.instructions?.any { instruction ->
        (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            ((instruction as ReferenceInstruction).reference as? MethodReference)?.sameAs(check) == true
    } == true

private fun MethodReference.sameAs(other: MethodReference): Boolean =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

/**
 * Whether declared parameter [index] is copied to a register and cast to a list there before the
 * code's first branch, goto, switch, return or throw: so every path through the method takes it
 * as the list of units.
 */
private fun Method.castsToListFirst(index: Int): Boolean {
    val copies = mutableSetOf(parameterRegisterNumber(index))
    for (instruction in implementation?.instructions?.toList().orEmpty()) {
        if (instruction is OffsetInstruction || !instruction.opcode.canContinue()) return false
        when (instruction.opcode) {
            in COPIES -> {
                val copy = instruction as TwoRegisterInstruction
                if (copy.registerB in copies) copies += copy.registerA else copies -= copy.registerA
            }
            Opcode.CHECK_CAST -> {
                val register = (instruction as OneRegisterInstruction).registerA
                val type = ((instruction as ReferenceInstruction).reference as TypeReference).type
                if (register in copies && type == UNITS_LIST) return true
            }
            else -> if (instruction.opcode.setsRegister()) {
                (instruction as? OneRegisterInstruction)?.registerA?.let { written ->
                    copies -= written
                    if (instruction.opcode.setsWideRegister()) copies -= written + 1
                }
            }
        }
    }
    return false
}

private fun Method.shapeText(): String = parameterTypes.joinToString("", "(", ")") + returnType
