/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.methodsCalling
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val PATCH = "HushGram launch screen"

/** The theme Instagram's launcher activities wear, whose Android 12 version names the launch screen's icon. */
internal const val INSTAGRAM_LAUNCH_THEME = "Base.Theme.Launcher"

/** The theme the patch adds, which the extension's LaunchScreen hands to Android by this name. */
internal const val LAUNCH_THEME = "HushGramLaunchScreen"

/** The drawable the patch adds for HushGram's mark. */
internal const val LAUNCH_MARK = "hushgram_launch_mark"

internal const val SPLASH_ICON = "android:windowSplashScreenAnimatedIcon"
internal const val SPLASH_BRANDING = "android:windowSplashScreenBrandingImage"

internal const val LAUNCH_SCREEN = "$EXTENSION_PACKAGE/misc/LaunchScreen;"
internal const val INSTAGRAM_LOGO = "$LAUNCH_SCREEN->instagramLogo(I)I"

/** The same hook for a check that answers with a boxed Integer, null meaning no animation (452). */
internal const val INSTAGRAM_LOGO_MODE = "$LAUNCH_SCREEN->instagramLogo(Ljava/lang/Integer;)Ljava/lang/Integer;"

/** Instagram's main screen, which every launcher alias opens. */
internal const val MAIN_ACTIVITY = "Lcom/instagram/mainactivity/InstagramMainActivity;"

/**
 * The extra Instagram's start screen hands its main screen when Instagram's logo animation should
 * follow Android's launch screen, as a copy of that screen drawn over the app.
 */
internal const val POST_SPLASH_EXTRA = "extra_post_splash_screen_animation_enabled"

private const val INTENT = "Landroid/content/Intent;"
private const val CONTEXT = "Landroid/content/Context;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val BOXED_INT = "Ljava/lang/Integer;"

/**
 * HushGram's mark, drawn from vectors so it's sharp on every screen: the pink to plum tile of the
 * app's icon with its white H and the ribbon through it. Android 12's launch screen shows an icon in
 * a 160dp square, masked to the middle two thirds, and Instagram's own glyph is 61.333dp across, so
 * the mark is 64dp, centered.
 */
internal val LAUNCH_MARK_XML = """
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:gravity="center" android:width="64dp" android:height="64dp">
        <shape android:shape="rectangle">
            <corners android:radius="15dp" />
            <gradient android:type="linear" android:angle="315" android:startColor="#FFF0186C" android:endColor="#FF3B0A3D" />
        </shape>
    </item>
    <item android:gravity="center" android:width="64dp" android:height="64dp">
        <vector android:width="64dp" android:height="64dp" android:viewportWidth="100" android:viewportHeight="100">
            <path android:fillColor="#FFFFFFFF" android:pathData="M22,15h15a3,3 0 0 1 3,3v64a3,3 0 0 1 -3,3h-15a3,3 0 0 1 -3,-3v-64a3,3 0 0 1 3,-3z" />
            <path android:fillColor="#FFFFFFFF" android:pathData="M63,15h15a3,3 0 0 1 3,3v64a3,3 0 0 1 -3,3h-15a3,3 0 0 1 -3,-3v-64a3,3 0 0 1 3,-3z" />
            <path android:fillColor="#FFFF7A9E" android:pathData="M11,38C22,36 30,46 42,45C54,44 66,36 80,40C84,41 87,44 88,48L88,58C85,53 82,52 78,52C66,50 54,57 42,57C30,57 22,49 11,50Z" />
        </vector>
    </item>
</layer-list>
""".trimStart()

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

private fun Document.styles(): List<Element> {
    val list = getElementsByTagName("style")
    return (0 until list.length).map { list.item(it) as Element }
}

private fun Element.items(): List<Element> {
    val list = getElementsByTagName("item")
    return (0 until list.length).map { list.item(it) as Element }
}

/**
 * Adds [LAUNCH_THEME] to one decoded styles.xml: [INSTAGRAM_LAUNCH_THEME] with [LAUNCH_MARK] as the
 * launch screen's icon and no branding image, so "from Meta" goes too. Everything else, the
 * background color and its dark version among it, comes from Instagram's theme. Refuses unless that
 * theme is there and this one isn't.
 */
internal fun addLaunchTheme(styles: Document) {
    val names = styles.styles().map { it.getAttribute("name") }
    if (INSTAGRAM_LAUNCH_THEME !in names) refuse("res/values/styles.xml has no $INSTAGRAM_LAUNCH_THEME")
    if (LAUNCH_THEME in names) refuse("res/values/styles.xml already has a $LAUNCH_THEME")
    val style = styles.createElement("style")
    style.setAttribute("name", LAUNCH_THEME)
    style.setAttribute("parent", "@style/$INSTAGRAM_LAUNCH_THEME")
    for ((name, value) in listOf(SPLASH_ICON to "@drawable/$LAUNCH_MARK", SPLASH_BRANDING to "@null")) {
        val item = styles.createElement("item")
        item.setAttribute("name", name)
        item.textContent = value
        style.appendChild(item)
    }
    styles.documentElement.appendChild(style)
}

/**
 * Whether one decoded styles.xml gives [INSTAGRAM_LAUNCH_THEME] a launch screen icon, which is what
 * the theme the patch adds replaces.
 */
internal fun namesSplashIcon(styles: Document): Boolean =
    styles.styles().any { style ->
        style.getAttribute("name") == INSTAGRAM_LAUNCH_THEME && style.items().any { it.getAttribute("name") == SPLASH_ICON }
    }

/**
 * Adds [LAUNCH_THEME] and [LAUNCH_MARK]. Refuses unless Instagram's launch theme names the launch
 * screen's icon in its Android 12 version, so a build that drew its launch screen some other way
 * isn't patched blind. Those versions are only read: document() writes its file back, and the
 * patcher re-encodes every file written.
 */
internal fun ResourcePatchContext.addLaunchScreen() {
    // Patcher 2.0 decodes only the files a patch asks for. The mark is asked for so one already in
    // the APK shows up and is refused.
    decode("res/values*/styles.xml", "res/drawable*/$LAUNCH_MARK.xml")
    val res = get("res")
    val v31 = res.listFiles { file -> file.isDirectory && file.name.startsWith("values") && file.name.endsWith("-v31") }
        .orEmpty().map { File(it, "styles.xml") }.filter { it.isFile }
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    if (v31.none { namesSplashIcon(builder.parse(it)) }) {
        refuse("no values-v31 styles.xml gives $INSTAGRAM_LAUNCH_THEME a launch screen icon")
    }
    val mark = File(res, "drawable/$LAUNCH_MARK.xml")
    if (mark.exists()) refuse("res/drawable already has a $LAUNCH_MARK")
    document("res/values/styles.xml").use(::addLaunchTheme)
    mark.parentFile.mkdirs()
    mark.writeText(LAUNCH_MARK_XML)
}

/**
 * The theme and the mark. Only the styles files are decoded, and it touches no color.
 */
private val launchThemePatch = resourcePatch {
    execute {
        addLaunchScreen()
    }
}

/**
 * The one method reading [POST_SPLASH_EXTRA], and the two answers in it the extension filters, by
 * the index of the move-result that takes each one in. [gateBoxed] is true when the check answers
 * with an Integer object (452), false when it answers with a boolean (451).
 */
internal class LogoAnswers(val method: MutableMethod, val extra: Int, val gate: Int, val gateBoxed: Boolean)

/**
 * Finds where Instagram decides whether its own logo animation follows Android's launch screen, and
 * checks the extension, before anything changes.
 *
 * The method is the one loading [POST_SPLASH_EXTRA] and reading it with its one
 * Intent.getBooleanExtra. 451 kept it in [MAIN_ACTIVITY]; 452 moved it into a helper class that
 * Redex names, so the method is found by that string and call rather than by its class. An answer of
 * true draws Instagram's copy of its launch screen over the app. Otherwise its one (Context,
 * UserSession) check, the test that Android is 12 or newer, animations run and Instagram's flag is
 * on, decides whether Instagram pulses its logo inside Android's screen. The check is a static
 * boolean on 451 and an Integer-returning call on 452 (null for no animation). Each answer is taken
 * in by a move-result right after its call.
 */
internal fun BytecodePatchContext.findLogoAnswers(): LogoAnswers {
    // Only methods calling Intent.getBooleanExtra are read, which the patcher's index names. Reading
    // every method in the app was a measured share of patching (#173).
    val holders = methodsCalling(INTENT, "getBooleanExtra").filter { method -> method.code().any { it.loads(POST_SPLASH_EXTRA) } }
    val holder = holders.singleOrNull()
        ?: refuse("expected one method loading \"$POST_SPLASH_EXTRA\" and reading an Intent boolean, found ${holders.size}")
    val code = holder.code()

    val reads = code.indices.filter { code[it].opcode == Opcode.INVOKE_VIRTUAL && code[it].calls(INTENT, "getBooleanExtra") }
    val read = reads.singleOrNull() ?: refuse("expected one Intent.getBooleanExtra in ${holder.where()}, found ${reads.size}")
    // The key is the string loaded last into the register the read passes, at most three instructions before it.
    val key = (code[read] as FiveRegisterInstruction).registerD
    val lastLoad = (maxOf(0, read - 3) until read).lastOrNull { at ->
        val instruction = code[at]
        (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
            (instruction as OneRegisterInstruction).registerA == key
    }
    if (lastLoad == null || !code[lastLoad].loads(POST_SPLASH_EXTRA)) {
        refuse("${holder.where()} reads another extra than \"$POST_SPLASH_EXTRA\"")
    }

    val gates = code.indices.filter { at ->
        val called = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.parameterTypes.map(Any::toString) == listOf(CONTEXT, USER_SESSION) &&
            ((code[at].opcode == Opcode.INVOKE_STATIC && called.returnType == "Z") ||
                (code[at].opcode == Opcode.INVOKE_VIRTUAL && called.returnType == BOXED_INT))
    }
    val gate = gates.singleOrNull()
        ?: refuse("expected one (Context, UserSession) check in ${holder.where()}, found ${gates.size}")
    val boxed = (code[gate] as ReferenceInstruction).reference.let { (it as MethodReference).returnType == BOXED_INT }

    if (code.getOrNull(read + 1)?.opcode != Opcode.MOVE_RESULT) refuse("${holder.where()} takes no answer in after instruction $read")
    if (code.getOrNull(gate + 1)?.opcode != if (boxed) Opcode.MOVE_RESULT_OBJECT else Opcode.MOVE_RESULT) {
        refuse("${holder.where()} takes no answer in after instruction $gate")
    }
    val hook = if (boxed) INSTAGRAM_LOGO_MODE else INSTAGRAM_LOGO
    if (classDefByOrNull(LAUNCH_SCREEN)?.methods?.any {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } != true
    ) {
        refuse("the extension has no public static $hook")
    }
    val method = mutableClassDefBy(holder.definingClass).methods.single {
        it.name == holder.name && it.parameterTypes.map(Any::toString) == holder.parameterTypes.map(Any::toString) &&
            it.returnType == holder.returnType
    }
    return LogoAnswers(method, read + 1, gate + 1, boxed)
}

/**
 * Both answers go through LaunchScreen.instagramLogo right after the move-result that takes each in,
 * the later one first so the earlier index still holds. The range form takes any register, and
 * move-result writes the answer back where Instagram reads it.
 */
internal fun BytecodePatchContext.skipInstagramLogo() {
    val found = findLogoAnswers()
    for (at in listOf(found.extra, found.gate).sortedDescending()) {
        val register = (found.method.code()[at] as OneRegisterInstruction).registerA
        val boxed = found.gateBoxed && at == found.gate
        found.method.addInstructions(
            at + 1,
            """
                invoke-static/range { v$register .. v$register }, ${if (boxed) INSTAGRAM_LOGO_MODE else INSTAGRAM_LOGO}
                ${if (boxed) "move-result-object" else "move-result"} v$register
            """,
        )
    }
}

private fun Instruction.loads(value: String): Boolean =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as StringReference).string == value

private fun Instruction.calls(definingClass: String, name: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
        it.definingClass == definingClass && it.name == name
    } == true

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.where(): String = "$definingClass->$name"

/**
 * HushGram's mark on the screen Android shows while Instagram starts. Included in the default
 * selection with its switch off.
 *
 * Android draws that screen itself, from the launch theme, before any of Instagram's code runs, and
 * takes it down at Instagram's first frame. Instagram's launcher activities wear
 * [INSTAGRAM_LAUNCH_THEME]. The patch adds a theme of its own next to that one, and the extension, on
 * Android 13 and newer, tells Android to use it for later starts while the switch is on
 * (Activity.getSplashScreen().setSplashScreenTheme). Android keeps the answer itself, so the switch
 * costs a start nothing. Android 12 and older only ever use the manifest's theme, so they keep
 * Instagram's screen.
 *
 * 451's main screen can follow Android's screen with Instagram's own logo animation, which put
 * Instagram's logo and "from Meta" on a gray screen right after HushGram's (#5). The patch passes
 * both of the main screen's answers on whether to run it through the extension, which skips it while
 * the switch is on, so HushGram's screen goes straight to Instagram's first frame.
 */
@Suppress("unused")
val launchScreenPatch = bytecodePatch(
    name = "HushGram launch screen",
    description = "Shows HushGram's logo instead of Instagram's on the screen you see while Instagram starts, on " +
        "Android 13 and newer. Starts off. Turn it on in HushGram settings > Layout.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch, launchThemePatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("launchScreen")
        skipInstagramLogo()
        enableStatus("launchScreen")
    }
}
