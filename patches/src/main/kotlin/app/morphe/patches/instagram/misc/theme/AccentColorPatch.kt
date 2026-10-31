/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.filterEveryReturn
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
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

private const val PATCH = "Accent color"

internal const val ACCENT_COLOR = "$EXTENSION_PACKAGE/misc/AccentColor;"
internal const val SWAP_COLOR = "$ACCENT_COLOR->color(I)I"
internal const val SWAP_LIST = "$ACCENT_COLOR->list(Landroid/content/res/ColorStateList;)Landroid/content/res/ColorStateList;"
internal const val SWAP_VALUE = "$ACCENT_COLOR->value(ILandroid/util/TypedValue;)I"

/** What AndroidX ResourcesCompat logs when it can't inflate a color state list itself. */
internal const val INFLATE_FAILED = "Failed to inflate ColorStateList, leaving it to the framework"

private const val CONTEXT = "Landroid/content/Context;"
private const val COLOR_STATE_LIST = "Landroid/content/res/ColorStateList;"
private const val THEME = "Landroid/content/res/Resources\$Theme;"
private const val TYPED_VALUE = "Landroid/util/TypedValue;"
private const val RESOURCES = "Landroid/content/res/Resources;"

/** The parameters of Resources' two getColor forms, which getColorStateList shares. */
private val GET_COLOR = listOf(listOf("I"), listOf("I", THEME))

/**
 * Instagram's blue turns into a color picked in HushGram settings, after a restart. Included in
 * the default selection with its switch off.
 *
 * Instagram reads its colors from the theme. Most reads go through one of two small statics taking
 * a Context and a theme attribute, which resolve the attribute to a color resource and answer
 * Context.getColor of it, or one of two that take a fallback color resource as well, and color state
 * lists (a button's colors for each of its states) go through AndroidX ResourcesCompat's loader.
 * Mentions, hashtags and links in captions, comments and search take their color from one more
 * static beside the resolver, which answers the value the attribute resolves to as it stands
 * (TypedValue.data). The patch hands each color those answer, each such value with the TypedValue
 * that says whether it's a color, and each list the loader answers, to the extension. With the
 * switch on it swaps only Instagram's exact accent blues for the matching shade of the color picked,
 * keeping each one's alpha, so a list's pressed, disabled and selected states keep their own look.
 * Every other color goes through untouched.
 *
 * Colors an XML style takes from a theme attribute never pass those reads (the framework reads them
 * through TypedArray), which left 451's bio links and the Activity tab's Follow buttons blue (#8). So
 * the patch also adds theme styles for each color ([accentThemePatch]), and once Instagram has laid
 * its Prism overrides over an activity's theme, or its theme util over a context of its own making
 * such as the tab bar's, the extension lays on the picked color's (see [applyAccentTheme]). Instagram's own resources and color table stay as they are. Its Compose
 * screens, the bio and the Activity tab among them, read neither: their colors come from palettes
 * written into the code, and each accent blue a palette stores goes through the extension as the
 * palette is set up ([swapComposePalettes]).
 */
@Suppress("unused")
val accentColorPatch = bytecodePatch(
    name = "Accent color",
    description = "Swaps Instagram's blue on Follow buttons, links and other highlights for purple, pink, red, orange, " +
        "green, teal or your wallpaper's colors (Material You, on Android 12 and newer). Restart Instagram to see a change. Starts off. Turn it on in HushGram settings > Layout.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch, accentThemePatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("accentColor")
        val attributes = requireAccentAttributes()
        findThemeOverrides()
        findThemeUtil()
        val palettes = findPaletteStores()
        swapAccentColors()
        applyAccentTheme(attributes)
        swapComposePalettes(palettes)
        tintHeaderIcons()
        enableStatus("accentColor")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/**
 * ResourcesCompat's color state list loader, the two statics that read a theme attribute's color,
 * the two that read it with a fallback color, and the one that reads the value an attribute
 * resolves to.
 */
internal class AccentReads(
    val loader: MutableMethod,
    val colorReads: List<MutableMethod>,
    val fallbackReads: List<MutableMethod>,
    val valueRead: MutableMethod,
)

/**
 * Finds every method the patch changes and checks the extension before anything changes.
 *
 * The loader is the one static `(Context, int)ColorStateList` loading [INFLATE_FAILED]. The color
 * reads are the statics taking a Context and an attribute that end by calling an attribute
 * resolver (a static of that shape calling Theme.resolveAttribute and reading TypedValue's
 * resourceId, with no getColor of its own) on their own two parameters, calling getColor on the
 * Context with its answer and returning that. A const and a static check may come first. 450 and
 * 451 have two of them in every build, and any other count means a build this wasn't written for.
 *
 * Both reads ask one resolver, and its class holds the other two kinds. The fallback resolver is
 * its one static `(Context, int, int)int` calling the resolver and asking for no color, and the
 * fallback reads are the statics of that shape ending the way the color reads do with it, two in
 * every 451 build. The value read is its one static `(Context, int)int` that resolves its own
 * attribute against the theme and returns TypedValue.data straight from the TypedValue it filled.
 */
internal fun BytecodePatchContext.findAccentReads(): AccentReads {
    val loaders = classesLoadingString(INFLATE_FAILED).flatMap { classDef ->
        classDef.methods.filter { it.isStaticContextAnd(COLOR_STATE_LIST) && loadsString(it, INFLATE_FAILED) }
    }
    val loader = loaders.singleOrNull()
        ?: refuse("expected one static (Context, int) ColorStateList loading \"$INFLATE_FAILED\", found ${loaders.size}")
    if (loader.code().none { it.opcode == Opcode.RETURN_OBJECT }) refuse("${loader.where()} returns no object")

    val resolvers = classesCalling(THEME, "resolveAttribute").flatMap { classDef ->
        classDef.methods.filter { it.resolvesAttribute() }
    }
    if (resolvers.isEmpty()) refuse("found no static (Context, int) int resolving a theme attribute to its resource")
    val reads = resolvers.flatMap { resolver ->
        classesCalling(resolver.definingClass, resolver.name).flatMap { classDef ->
            classDef.methods.filter { it.readsColorThrough(resolver) }
        }
    }.distinctBy { it.where() }
    if (reads.size != 2) {
        refuse("expected two statics reading a theme attribute's color, found ${reads.size}: ${reads.joinToString { it.where() }}")
    }
    val resolver = reads.map { it.resolverCalled() }.distinct().singleOrNull()
        ?.let { called -> resolvers.single { it.definingClass == called.first && it.name == called.second } }
        ?: refuse("the two color reads ask different resolvers: ${reads.joinToString { it.where() }}")
    val resolverClass = classDefByOrNull(resolver.definingClass) ?: refuse("no class ${resolver.definingClass}")

    val fallbackResolvers = resolverClass.methods.filter { it.resolvesWithFallback(resolver) }
    val fallbackResolver = fallbackResolvers.singleOrNull()
        ?: refuse("expected one static (Context, int, int) int in ${resolver.definingClass} asking ${resolver.name}, found ${fallbackResolvers.size}")
    val fallbackReads = classesCalling(fallbackResolver.definingClass, fallbackResolver.name).flatMap { classDef ->
        classDef.methods.filter { it.readsColorThrough(fallbackResolver) }
    }.distinctBy { it.where() }
    if (fallbackReads.size != 2) {
        refuse("expected two statics reading a theme color with a fallback, found ${fallbackReads.size}: ${fallbackReads.joinToString { it.where() }}")
    }

    val valueReads = resolverClass.methods.filter { it.readsThemeValue() }
    val valueRead = valueReads.singleOrNull()
        ?: refuse("expected one static (Context, int) int in ${resolver.definingClass} answering TypedValue.data, found ${valueReads.size}")
    val dataRead = valueRead.code().takeLast(2)[0] as TwoRegisterInstruction
    if (dataRead.registerA > 15 || dataRead.registerB > 15) refuse("${valueRead.where()} keeps its value or its TypedValue past v15")

    for (hook in listOf(SWAP_COLOR, SWAP_LIST, SWAP_VALUE)) {
        if (classDefByOrNull(ACCENT_COLOR)?.methods?.any {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                    AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
            } != true
        ) {
            refuse("the extension has no public static $hook")
        }
    }
    return AccentReads(mutable(loader), reads.map { mutable(it) }, fallbackReads.map { mutable(it) }, mutable(valueRead))
}

/**
 * Every list the loader returns goes through AccentColor.list, the color each read returns through
 * AccentColor.color, and the value read's answer through AccentColor.value with its TypedValue, at
 * the return's own label so a branch to the return passes through it too. The range form takes any
 * register, and move-result writes the answer back over the value.
 */
internal fun BytecodePatchContext.swapAccentColors() {
    val found = findAccentReads()
    found.loader.filterEveryReturn(PATCH, SWAP_LIST)
    for (read in found.colorReads + found.fallbackReads) {
        val at = read.code().indexOfLast { it.opcode == Opcode.RETURN }
        val register = (read.code()[at] as OneRegisterInstruction).registerA
        read.addInstructionsAtControlFlowLabel(
            at,
            """
                invoke-static/range { v$register .. v$register }, $SWAP_COLOR
                move-result v$register
            """,
        )
    }
    val read = found.valueRead
    val at = read.code().lastIndex
    val data = (read.code()[at] as OneRegisterInstruction).registerA
    val typedValue = (read.code()[at - 1] as TwoRegisterInstruction).registerB
    read.addInstructionsAtControlFlowLabel(
        at,
        """
            invoke-static { v$data, v$typedValue }, $SWAP_VALUE
            move-result v$data
        """,
    )
    for (method in findResourcesColorReads()) {
        if (method.returnType == "I") method.filterEveryColorReturn() else method.filterEveryReturn(PATCH, SWAP_LIST)
    }
}

/**
 * Instagram's own Resources classes override getColor and getColorStateList (the one its Contexts
 * hand out is a subclass of one, holding Meta's string overrides). A color taken straight from a
 * resource comes back through them and never meets the theme reads, the way 451's bio links take
 * the dark theme's link blue by its resource id. Each class directly extending Resources that
 * overrides both getColor forms has them and its getColorStateList forms passed through the
 * extension too. A color AccentColor already swapped comes back as it is, so a read that passes
 * a theme read and one of these ends up the same.
 */
internal fun BytecodePatchContext.findResourcesColorReads(): List<MutableMethod> {
    val owners = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.superclass == RESOURCES && !classDef.type.startsWith(EXTENSION_ROOT) &&
            GET_COLOR.all { parameters -> classDef.overrides("getColor", parameters, "I") }
        ) {
            owners += classDef
        }
    }
    if (owners.isEmpty()) refuse("found no class extending Resources that overrides both getColor forms")
    return owners.flatMap { owner ->
        owner.methods.filter { method ->
            GET_COLOR.any { method.isRead("getColor", it, "I") || method.isRead("getColorStateList", it, COLOR_STATE_LIST) }
        }.map { mutable(it) }
    }
}

private fun ClassDef.overrides(name: String, parameters: List<String>, returns: String): Boolean =
    methods.any { it.isRead(name, parameters, returns) }

private fun Method.isRead(name: String, parameters: List<String>, returns: String): Boolean =
    this.name == name && returnType == returns && parameterTypes.map(Any::toString) == parameters &&
        !AccessFlags.STATIC.isSet(accessFlags) && implementation != null

/** Passes every int this method returns through AccentColor.color, at the return's own label. */
private fun MutableMethod.filterEveryColorReturn() {
    val returns = code().withIndex().filter { it.value.opcode == Opcode.RETURN }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) refuse("${where()} returns no color")
    returns.asReversed().forEach { (index, register) ->
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $SWAP_COLOR
                move-result v$register
            """,
        )
    }
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.isStaticContextAnd(returns: String): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == returns &&
        parameterTypes.map(Any::toString) == listOf(CONTEXT, "I") && implementation != null

/** A static (Context, int) int calling Theme.resolveAttribute, reading TypedValue's resourceId and no color. */
private fun Method.resolvesAttribute(): Boolean {
    if (!isStaticContextAnd("I")) return false
    val code = code()
    return code.any { it.calls(THEME, "resolveAttribute") } && code.none { it.calls(CONTEXT, "getColor") } &&
        code.any { instruction ->
            instruction.opcode == Opcode.IGET &&
                ((instruction as ReferenceInstruction).reference as FieldReference).let {
                    it.definingClass == TYPED_VALUE && it.name == "resourceId"
                }
        }
}

/** A static (Context, int, int) int in the resolver's class calling the resolver and asking for no color. */
private fun Method.resolvesWithFallback(resolver: Method): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == "I" && implementation != null &&
        parameterTypes.map(Any::toString) == listOf(CONTEXT, "I", "I") &&
        code().any { it.opcode == Opcode.INVOKE_STATIC && it.calls(resolver.definingClass, resolver.name) } &&
        code().none { it.calls(CONTEXT, "getColor") }

/**
 * A static (Context, int) int that resolves its own attribute against the theme, asks for no color
 * and ends `iget vA, vB, TypedValue->data:I`, `return vA`, the TypedValue it filled still in vB.
 */
private fun Method.readsThemeValue(): Boolean {
    if (!isStaticContextAnd("I")) return false
    val code = code()
    if (code.size < 2 || code.any { it.calls(CONTEXT, "getColor") }) return false
    val attribute = parameterRegisterNumber(1)
    if (code.none { it.opcode == Opcode.INVOKE_VIRTUAL && it.calls(THEME, "resolveAttribute") && (it as FiveRegisterInstruction).registerD == attribute }) {
        return false
    }
    val (read, ret) = code.takeLast(2)
    if (read.opcode != Opcode.IGET || ret.opcode != Opcode.RETURN) return false
    val field = (read as ReferenceInstruction).reference as FieldReference
    if (field.definingClass != TYPED_VALUE || field.name != "data") return false
    val data = (read as TwoRegisterInstruction).registerA
    return data == (ret as OneRegisterInstruction).registerA && data != read.registerB
}

/** The class and name of the static a color read asks right before getColor. */
private fun Method.resolverCalled(): Pair<String, String> {
    val reference = (code().takeLast(5)[0] as ReferenceInstruction).reference as MethodReference
    return reference.definingClass to reference.name
}

/**
 * Whether this is a static taking a Context and ints like [resolver], ending `invoke-static {p0,
 * p1...}, resolver`, `move-result vA`, `invoke-virtual {p0, vA}, Context->getColor(I)I`,
 * `move-result vB`, `return vB`, after at most a const and a static call that write no parameter.
 */
private fun Method.readsColorThrough(resolver: Method): Boolean {
    val parameters = resolver.parameterTypes.map(Any::toString)
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "I" || implementation == null) return false
    if (parameterTypes.map(Any::toString) != parameters) return false
    val code = code()
    if (code.size !in 5..7) return false
    val own = parameters.indices.map { parameterRegisterNumber(it) }
    val context = own[0]
    val tail = code.takeLast(5)
    val prefix = code.dropLast(5)
    if (prefix.any { it.opcode != Opcode.CONST_4 && it.opcode != Opcode.INVOKE_STATIC }) return false
    if (prefix.any { it.opcode == Opcode.CONST_4 && (it as OneRegisterInstruction).registerA in own }) return false

    val resolve = tail[0]
    val called = (resolve as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (resolve.opcode != Opcode.INVOKE_STATIC || called.definingClass != resolver.definingClass || called.name != resolver.name ||
        called.parameterTypes.map(Any::toString) != parameters || called.returnType != "I"
    ) {
        return false
    }
    val passed = resolve as FiveRegisterInstruction
    val registers = listOf(passed.registerC, passed.registerD, passed.registerE, passed.registerF, passed.registerG)
    if (passed.registerCount != own.size || registers.take(own.size) != own) return false
    if (tail[1].opcode != Opcode.MOVE_RESULT) return false
    val resource = (tail[1] as OneRegisterInstruction).registerA

    val getColor = tail[2]
    if (getColor.opcode != Opcode.INVOKE_VIRTUAL || !getColor.calls(CONTEXT, "getColor")) return false
    val asked = getColor as FiveRegisterInstruction
    if (asked.registerCount != 2 || asked.registerC != context || asked.registerD != resource) return false
    if (tail[3].opcode != Opcode.MOVE_RESULT || tail[4].opcode != Opcode.RETURN) return false
    return (tail[3] as OneRegisterInstruction).registerA == (tail[4] as OneRegisterInstruction).registerA
}

private fun Instruction.calls(definingClass: String, name: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
        it.definingClass == definingClass && it.name == name
    } == true

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.where(): String = "$definingClass->$name"
