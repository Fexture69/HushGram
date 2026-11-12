/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.device

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.INSTAGRAM_APPLICATION
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.declaredInHierarchy
import app.morphe.patches.instagram.misc.settings.sendToStandIn
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.shared.misc.extension.EXTENSION_CLASS_DESCRIPTOR
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Spoof device"
internal const val DEVICE_SPOOF = "$EXTENSION_PACKAGE/misc/DeviceSpoof;"
internal const val ON_APP_START = "$DEVICE_SPOOF->onAppStart(Landroid/content/Context;)V"

internal const val BUILD = "Landroid/os/Build;"
internal const val SYSTEM_PROPERTIES = "Landroid/os/SystemProperties;"
internal const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
internal const val APP_PACKAGE_MANAGER = "Landroid/app/ApplicationPackageManager;"
private const val STRING = "Ljava/lang/String;"

internal val SYSTEM_PROPERTY_CALLS = mapOf(
    "($STRING)$STRING" to "$DEVICE_SPOOF->systemProperty($STRING)$STRING",
    "($STRING$STRING)$STRING" to "$DEVICE_SPOOF->systemProperty($STRING$STRING)$STRING",
)

internal val HAS_FEATURE_CALLS = mapOf(
    "($STRING)Z" to "$DEVICE_SPOOF->hasSystemFeature(${PACKAGE_MANAGER}$STRING)Z",
    "($STRING" + "I)Z" to "$DEVICE_SPOOF->hasSystemFeature(${PACKAGE_MANAGER}$STRING" + "I)Z",
)

internal val BUILD_FIELD_STANDINS = mapOf(
    "MANUFACTURER" to "$DEVICE_SPOOF->buildManufacturer($STRING)$STRING",
    "BRAND" to "$DEVICE_SPOOF->buildBrand($STRING)$STRING",
    "MODEL" to "$DEVICE_SPOOF->buildModel($STRING)$STRING",
    "DEVICE" to "$DEVICE_SPOOF->buildDevice($STRING)$STRING",
    "PRODUCT" to "$DEVICE_SPOOF->buildProduct($STRING)$STRING",
    "FINGERPRINT" to "$DEVICE_SPOOF->buildFingerprint($STRING)$STRING",
    "ID" to "$DEVICE_SPOOF->buildId($STRING)$STRING",
    "SOC_MANUFACTURER" to "$DEVICE_SPOOF->buildSocManufacturer($STRING)$STRING",
    "SOC_MODEL" to "$DEVICE_SPOOF->buildSocModel($STRING)$STRING",
)

internal data class DeviceSpoofCounts(
    val systemProperties: Int,
    val systemFeatures: Int,
    val buildFields: Int,
) {
    val total: Int get() = systemProperties + systemFeatures + buildFields
}

@Suppress("unused")
val spoofDevicePatch = bytecodePatch(
    name = PATCH,
    description = "Makes Instagram see a Pixel 11 Pro XL or Galaxy S26 Ultra, unlocking HDR video downloads " +
        "and other features limited to those phones, and adds the matching developer options. Restart Instagram " +
        "after changing it. Starts off. Turn it on in HushGram settings > Ads and privacy.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("spoofDevice")
        hookApplicationStart()
        val counts = applyDeviceSpoofHooks()
        if (counts.total == 0) {
            throw PatchException("$PATCH: found no Build, SystemProperties or feature checks to spoof")
        }
        enableStatus("spoofDevice")
    }
}

/**
 * Calls {@code DeviceSpoof.onAppStart(Context)} immediately after {@code setContext(Context)} inside
 * the application's {@code onCreate()} so MobileConfig HDR overrides and {@code Build} fields are
 * prepared before Instagram reads them.
 */
internal fun BytecodePatchContext.hookApplicationStart() {
    val onCreate = declaredInHierarchy(INSTAGRAM_APPLICATION, "onCreate")
    val instructions = onCreate.implementation?.instructions?.toList().orEmpty()
    val setContextIndex = instructions.indexOfFirst { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@indexOfFirst false
        call.definingClass == EXTENSION_CLASS_DESCRIPTOR && call.name == "setContext"
    }
    val insertAt = if (setContextIndex >= 0) setContextIndex + 1 else 0
    onCreate.addInstruction(insertAt, "invoke-static/range { p0 .. p0 }, $ON_APP_START")
}

internal fun BytecodePatchContext.applyDeviceSpoofHooks(): DeviceSpoofCounts = DeviceSpoofCounts(
    systemProperties = rerouteSystemProperties(),
    systemFeatures = rerouteSystemFeatures(),
    buildFields = rerouteBuildFields(),
)

internal fun Instruction.systemPropertyStandIn(): String? {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return null
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (call.definingClass != SYSTEM_PROPERTIES || call.name != "get") return null
    val shape = call.parameterTypes.joinToString("", "(", ")") + call.returnType
    return SYSTEM_PROPERTY_CALLS[shape]
}

internal fun Instruction.systemFeatureStandIn(): String? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (call.name != "hasSystemFeature") return null
    if (call.definingClass != PACKAGE_MANAGER && call.definingClass != APP_PACKAGE_MANAGER) return null
    val shape = call.parameterTypes.joinToString("", "(", ")") + call.returnType
    return HAS_FEATURE_CALLS[shape]
}

internal fun Instruction.buildFieldStandIn(): String? {
    if (opcode != Opcode.SGET_OBJECT) return null
    val field = (this as? ReferenceInstruction)?.reference as? FieldReference ?: return null
    if (field.definingClass != BUILD || field.type != STRING) return null
    return BUILD_FIELD_STANDINS[field.name]
}

private fun BytecodePatchContext.rerouteCalls(
    classes: List<ClassDef>,
    standInFor: (Instruction) -> String?,
): Int {
    var count = 0
    for (classDef in classes) {
        if (classDef.type.startsWith(EXTENSION_ROOT)) continue
        for (method in mutableClassDefBy(classDef.type).methods) {
            val sites = method.implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
                standInFor(instruction)?.let { index to it }
            }.orEmpty()
            sites.asReversed().forEach { (index, standIn) -> method.sendToStandIn(index, standIn) }
            count += sites.size
        }
    }
    return count
}

private fun BytecodePatchContext.rerouteSystemProperties(): Int =
    rerouteCalls(classesCalling(SYSTEM_PROPERTIES, "get")) { it.systemPropertyStandIn() }

private fun BytecodePatchContext.rerouteSystemFeatures(): Int = rerouteCalls(
    (classesCalling(PACKAGE_MANAGER, "hasSystemFeature") +
        classesCalling(APP_PACKAGE_MANAGER, "hasSystemFeature")).distinctBy { it.type },
) { it.systemFeatureStandIn() }

private fun BytecodePatchContext.rerouteBuildFields(): Int {
    val classes = BUILD_FIELD_STANDINS.keys
        .flatMap { fieldName -> classesAccessing(BUILD, fieldName, Opcode.SGET_OBJECT) }
        .distinctBy { it.type }
    var count = 0
    for (classDef in classes) {
        if (classDef.type.startsWith(EXTENSION_ROOT)) continue
        for (method in mutableClassDefBy(classDef.type).methods) {
            count += method.spoofBuildFieldReads()
        }
    }
    return count
}

private fun MutableMethod.spoofBuildFieldReads(): Int {
    val sites = implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
        instruction.buildFieldStandIn()?.let { standIn ->
            val reg = (instruction as OneRegisterInstruction).registerA
            Triple(index, reg, standIn)
        }
    }.orEmpty()
    sites.asReversed().forEach { (index, reg, standIn) ->
        addInstructions(
            index + 1,
            """
                invoke-static/range { v$reg .. v$reg }, $standIn
                move-result-object v$reg
            """.trimIndent(),
        )
    }
    return sites.size
}
