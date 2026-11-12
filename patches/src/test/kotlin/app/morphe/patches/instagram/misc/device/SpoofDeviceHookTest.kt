/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.device

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patches.instagram.misc.extension.INSTAGRAM_APPLICATION
import app.morphe.patches.shared.misc.extension.EXTENSION_CLASS_DESCRIPTOR
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every SystemProperties, Build field and hasSystemFeature read in Instagram goes through DeviceSpoof. */
class SpoofDeviceHookTest {
    private fun deviceReader(type: String): ClassDef {
        val code = listOf<Instruction>(
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference(SYSTEM_PROPERTIES, "get", listOf("Ljava/lang/String;"), "Ljava/lang/String;"),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE, 0, 2,
                ImmutableMethodReference(
                    SYSTEM_PROPERTIES,
                    "get",
                    listOf("Ljava/lang/String;", "Ljava/lang/String;"),
                    "Ljava/lang/String;",
                ),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL, 2, 1, 0, 0, 0, 0,
                ImmutableMethodReference(PACKAGE_MANAGER, "hasSystemFeature", listOf("Ljava/lang/String;"), "Z"),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 2),
            ImmutableInstruction3rc(
                Opcode.INVOKE_VIRTUAL_RANGE, 1, 3,
                ImmutableMethodReference(
                    APP_PACKAGE_MANAGER,
                    "hasSystemFeature",
                    listOf("Ljava/lang/String;", "I"),
                    "Z",
                ),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 2),
            ImmutableInstruction21c(
                Opcode.SGET_OBJECT, 3,
                ImmutableFieldReference(BUILD, "MODEL", "Ljava/lang/String;"),
            ),
            ImmutableInstruction21c(
                Opcode.SGET_OBJECT, 4,
                ImmutableFieldReference(BUILD, "SOC_MODEL", "Ljava/lang/String;"),
            ),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    type, "read",
                    listOf("Ljava/lang/String;", PACKAGE_MANAGER, "I").map { ImmutableMethodParameter(it, null, null) },
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null, null,
                    ImmutableMethodImplementation(5, code, null, null),
                ),
            ),
        )
    }

    @Test
    fun systemPropertiesFeaturesAndBuildReadsAreHooked() {
        val instagram = "Lfixture/DeviceReader;"
        val extension = DEVICE_SPOOF
        val context = PatchContexts.of(listOf(deviceReader(instagram), deviceReader(extension)))

        val counts = context.applyDeviceSpoofHooks()
        assertEquals(DeviceSpoofCounts(systemProperties = 2, systemFeatures = 2, buildFields = 2), counts)

        val code = context.mutableClassDefBy(instagram).methods.single().instructions()
        assertEquals(Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals(
            "$DEVICE_SPOOF->systemProperty(Ljava/lang/String;)Ljava/lang/String;",
            (code[0] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[2].opcode)
        assertEquals(
            "$DEVICE_SPOOF->systemProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            (code[2] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(Opcode.INVOKE_STATIC, code[4].opcode)
        assertEquals(
            "$DEVICE_SPOOF->hasSystemFeature(${PACKAGE_MANAGER}Ljava/lang/String;)Z",
            (code[4] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[6].opcode)
        assertEquals(
            "$DEVICE_SPOOF->hasSystemFeature(${PACKAGE_MANAGER}Ljava/lang/String;I)Z",
            (code[6] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(Opcode.SGET_OBJECT, code[8].opcode)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[9].opcode)
        assertEquals(
            "$DEVICE_SPOOF->buildModel(Ljava/lang/String;)Ljava/lang/String;",
            (code[9] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[10].opcode)

        assertEquals(Opcode.SGET_OBJECT, code[11].opcode)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[12].opcode)
        assertEquals(
            "$DEVICE_SPOOF->buildSocModel(Ljava/lang/String;)Ljava/lang/String;",
            (code[12] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[13].opcode)

        // Extension's own calls stay untouched to avoid self-recursion.
        val kept = context.mutableClassDefBy(extension).methods.single().instructions()
        assertEquals(11, kept.size)
        assertEquals(Opcode.INVOKE_STATIC, kept[0].opcode)
        assertEquals(
            "$SYSTEM_PROPERTIES->get(Ljava/lang/String;)Ljava/lang/String;",
            (kept[0] as ReferenceInstruction).reference.toString(),
        )
    }

    @Test
    fun appStartHooksImmediatelyAfterSetContext() {
        val shell = ImmutableClassDef(
            INSTAGRAM_APPLICATION, AccessFlags.PUBLIC.value, "Landroid/app/Application;",
            null, null, null, null,
            listOf(
                ImmutableMethod(
                    INSTAGRAM_APPLICATION, "onCreate", emptyList(), "V",
                    AccessFlags.PUBLIC.value, null, null,
                    ImmutableMethodImplementation(
                        1,
                        listOf(
                            ImmutableInstruction3rc(
                                Opcode.INVOKE_STATIC_RANGE, 0, 1,
                                ImmutableMethodReference(
                                    EXTENSION_CLASS_DESCRIPTOR,
                                    "setContext",
                                    listOf("Landroid/content/Context;"),
                                    "V",
                                ),
                            ),
                            ImmutableInstruction10x(Opcode.RETURN_VOID),
                        ),
                        null, null,
                    ),
                ),
            ),
        )
        val context = PatchContexts.of(listOf(shell))
        context.hookApplicationStart()

        val code = context.mutableClassDefBy(INSTAGRAM_APPLICATION).methods.single().instructions()
        assertEquals(3, code.size)
        assertEquals(
            "$EXTENSION_CLASS_DESCRIPTOR->setContext(Landroid/content/Context;)V",
            (code[0] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(ON_APP_START, (code[1] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.RETURN_VOID, code[2].opcode)
    }

    @Test
    fun everyTargetDeclaresItsStandInInExtensionDex() {
        val declared = ExtensionDex.classDef(DEVICE_SPOOF).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$DEVICE_SPOOF->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            .toSet()

        assertTrue("DeviceSpoof lacks $ON_APP_START: $declared", ON_APP_START in declared)
        SYSTEM_PROPERTY_CALLS.values.forEach { target ->
            assertTrue("DeviceSpoof lacks $target: $declared", target in declared)
        }
        HAS_FEATURE_CALLS.values.forEach { target ->
            assertTrue("DeviceSpoof lacks $target: $declared", target in declared)
        }
        BUILD_FIELD_STANDINS.values.forEach { target ->
            assertTrue("DeviceSpoof lacks $target: $declared", target in declared)
        }
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
}
