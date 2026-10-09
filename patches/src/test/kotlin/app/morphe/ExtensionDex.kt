/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import com.android.tools.smali.dexlib2.iface.value.LongEncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import java.nio.ByteBuffer

/**
 * The Instagram extension as the bundle carries it: the dex R8 wrote, which the Morphe plugin puts
 * among this module's resources as `extensions/instagram.mpe`, so every test run reads the one built
 * from the current sources. A test that reads it sees the descriptors and constants the patched app
 * gets, not the Java that compiles to them.
 */
internal object ExtensionDex {
    private const val PAYLOAD = "extensions/instagram.mpe"

    private val dex: DexBackedDexFile by lazy {
        val bytes = ExtensionDex::class.java.classLoader.getResourceAsStream(PAYLOAD)?.use { it.readBytes() }
            ?: throw AssertionError("$PAYLOAD is not on the test classpath. :patches:processResources copies it there.")
        DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(bytes))
    }

    /** The class of [type] in the payload. */
    fun classDef(type: String): ClassDef =
        dex.classes.firstOrNull { it.type == type } ?: throw AssertionError("$PAYLOAD holds no $type")

    /** Every class in the payload, as the patcher sees them merged into the app. */
    fun classes(): List<ClassDef> = dex.classes.toList()

    /** The value a static final String field of [type] starts with, as javac wrote it into the class. */
    fun stringConstant(type: String, field: String): String {
        val declared = classDef(type).staticFields.firstOrNull { it.name == field }
            ?: throw AssertionError("$type in $PAYLOAD has no static field $field")
        return (declared.initialValue as? StringEncodedValue)?.value
            ?: throw AssertionError("$type.$field in $PAYLOAD starts with no string: ${declared.initialValue}")
    }

    /** The value a static final long field of [type] starts with, as javac wrote it into the class. */
    fun longConstant(type: String, field: String): Long {
        val declared = classDef(type).staticFields.firstOrNull { it.name == field }
            ?: throw AssertionError("$type in $PAYLOAD has no static field $field")
        return (declared.initialValue as? LongEncodedValue)?.value
            ?: throw AssertionError("$type.$field in $PAYLOAD starts with no long: ${declared.initialValue}")
    }

    /** The value a static final int field of [type] starts with, as javac wrote it into the class. */
    fun intConstant(type: String, field: String): Int {
        val declared = classDef(type).staticFields.firstOrNull { it.name == field }
            ?: throw AssertionError("$type in $PAYLOAD has no static field $field")
        return (declared.initialValue as? IntEncodedValue)?.value
            ?: throw AssertionError("$type.$field in $PAYLOAD starts with no int: ${declared.initialValue}")
    }
}
