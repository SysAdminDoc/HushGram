/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeMap;

/** Reads only the fixed-label coverage constants stamped into the patched extension. */
public class PatchCoverage {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a patched APK");
        Set<String> wanted = Set.of("sanitizeSharingLinksCoverage", "translatedStartCoverage");
        Set<String> found = new HashSet<>();
        TreeMap<String, String> values = new TreeMap<>();
        MultiDexContainer<? extends DexFile> dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        int definitions = 0;
        for (String entry : dex.getDexEntryNames()) {
            for (ClassDef type : dex.getEntry(entry).getDexFile().getClasses()) {
                if (!type.getType().equals("Lapp/hushgram/extension/instagram/settings/SettingsStatus;")) continue;
                if (++definitions > 1) throw new IllegalStateException("Duplicate SettingsStatus definition");
                for (Method method : type.getMethods()) {
                    if (!wanted.contains(method.getName())) continue;
                    if (!found.add(method.getName()) || !AccessFlags.STATIC.isSet(method.getAccessFlags())
                            || !method.getParameterTypes().isEmpty() || !method.getReturnType().equals("Ljava/lang/String;")
                            || method.getImplementation() == null) {
                        throw new IllegalStateException("Invalid coverage method " + method.getName());
                    }
                    Iterator<? extends Instruction> code = method.getImplementation().getInstructions().iterator();
                    if (!code.hasNext()) throw new IllegalStateException("Empty coverage method");
                    Instruction constant = code.next();
                    if (!code.hasNext()) throw new IllegalStateException("Coverage constant has no return");
                    Instruction returned = code.next();
                    if ((constant.getOpcode() != Opcode.CONST_STRING && constant.getOpcode() != Opcode.CONST_STRING_JUMBO)
                            || returned.getOpcode() != Opcode.RETURN_OBJECT
                            || ((OneRegisterInstruction) constant).getRegisterA() != ((OneRegisterInstruction) returned).getRegisterA()
                            || !(((ReferenceInstruction) constant).getReference() instanceof StringReference)) {
                        throw new IllegalStateException("Coverage is not a constant return: " + method.getName());
                    }
                    String value = ((StringReference) ((ReferenceInstruction) constant).getReference()).getString();
                    if (value.length() > 32768 || !value.matches("[a-z0-9 ,|\\-]*")) {
                        throw new IllegalStateException("Invalid coverage encoding");
                    }
                    if (!value.isEmpty()) values.put(method.getName().replace("Coverage", ""), value);
                }
            }
        }
        if (definitions != 1 || !found.equals(wanted)) throw new IllegalStateException("Coverage methods are missing");
        values.forEach((name, value) -> System.out.println(name + "=" + value));
    }
}
