/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val UNLOCK_EMPLOYEE = "$EXTENSION_PACKAGE/misc/DeveloperOptions;->unlockEmployee()I"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val GATE_SHAPE = "($USER_SESSION)Z"
private const val UNSAFE_CONTEXT = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
private const val EMPLOYEE_KEY = "is_employee"
private const val MAX_GATE_OPCODES = 16
private const val MIN_CALLER_FAN_IN = 3

private val LEGACY_CONFIG_IDS = setOf(
    36310834636390667L,
    36310830341423371L,
    36310856111227168L,
    36310864701161762L,
)

/**
 * Finds Instagram's `is_employee` MobileConfig gate method(s) `(UserSession) -> boolean` using a
 * three-tier strategy: `"is_employee"` string/callee anchor, structural `MobileConfigUnsafeContext`
 * pass-through fan-in ranking, and known legacy MobileConfig IDs. Empty when absent in minimal fixtures.
 */
internal fun BytecodePatchContext.findEmployeeGates(): List<String> {
    findTier1EmployeeGates().takeIf { it.isNotEmpty() }?.let { return it }
    findTier2StructuralGates().takeIf { it.isNotEmpty() }?.let { return it }
    return findTier3LegacyIdGates()
}

internal fun BytecodePatchContext.employeeGatesOrInfo(): List<String> =
    findEmployeeGates().also { gates ->
        if (gates.isEmpty()) {
            patchLog.info("Open developer options: no is_employee gate found in this build; long press and MetaConfig still go in.")
        }
    }

/** Injects [UNLOCK_EMPLOYEE] at index 0 of every discovered employee gate method. */
internal fun BytecodePatchContext.applyEmployeeGates(gates: List<String>) {
    for (gate in gates.distinct()) {
        val owner = gate.substringBefore("->")
        val name = gate.substringAfter("->").substringBefore("(")
        val method = mutableClassDefBy(owner).methods.single { it.name == name && it.shape() == GATE_SHAPE }
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $UNLOCK_EMPLOYEE
                move-result v0
                if-eqz v0, :original
                const/4 v0, 0x1
                return v0
            """,
            ExternalLabel("original", method.getInstruction(0)),
        )
    }
}

private fun BytecodePatchContext.findTier1EmployeeGates(): List<String> {
    val stringHolders = classesLoadingString(EMPLOYEE_KEY).filter { !it.type.startsWith(EXTENSION_ROOT) }
    if (stringHolders.isEmpty()) return emptyList()

    val directCallees = LinkedHashSet<String>()
    val classGates = LinkedHashSet<String>()

    for (clazz in stringHolders) {
        val candidates = clazz.methods.filter { it.isEmployeeGateCandidate() }
        for (method in clazz.methods) {
            if (!loadsString(method, EMPLOYEE_KEY)) continue
            for (instruction in method.code()) {
                val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (ref.definingClass.startsWith(EXTENSION_ROOT) || ref.shape() != GATE_SHAPE) continue
                val target = classDefByOrNull(ref.definingClass)?.methods?.singleOrNull {
                    it.name == ref.name && it.isEmployeeGateCandidate()
                } ?: continue
                directCallees += target.key()
            }
        }
        if (candidates.size in 1..4 && candidates.any { it.callsUnsafeBooleanGate() }) {
            candidates.forEach { classGates += it.key() }
        }
    }
    return (if (directCallees.isNotEmpty()) directCallees else classGates).toList()
}

private fun BytecodePatchContext.findTier2StructuralGates(): List<String> {
    val candidates = mutableListOf<Method>()
    classDefForEach { clazz ->
        if (clazz.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        for (method in clazz.methods) {
            if (method.isEmployeeGateCandidate() && method.code().size <= MAX_GATE_OPCODES && method.callsUnsafeBooleanGate()) {
                candidates += method
            }
        }
    }
    if (candidates.isEmpty()) return emptyList()

    val candidateKeys = candidates.mapTo(HashSet()) { it.key() }
    val callerClasses = HashMap<String, MutableSet<String>>()
    classDefForEach { clazz ->
        if (clazz.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        for (method in clazz.methods) {
            for (instruction in method.code()) {
                val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (ref.shape() != GATE_SHAPE) continue
                val key = "${ref.definingClass}->${ref.name}$GATE_SHAPE"
                if (key in candidateKeys && ref.definingClass != clazz.type) {
                    callerClasses.getOrPut(key) { HashSet() }.add(clazz.type)
                }
            }
        }
    }
    val ranked = callerClasses.entries
        .filter { it.value.size >= MIN_CALLER_FAN_IN }
        .sortedByDescending { it.value.size }
    if (ranked.isEmpty()) return emptyList()
    val topCount = ranked.first().value.size
    return ranked.takeWhile { it.value.size == topCount }.map { it.key }
}

private fun BytecodePatchContext.findTier3LegacyIdGates(): List<String> {
    val matches = LinkedHashSet<String>()
    classDefForEach { clazz ->
        if (clazz.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        for (method in clazz.methods) {
            if (!method.isEmployeeGateCandidate()) continue
            val hits = method.code().any { instruction ->
                instruction is WideLiteralInstruction && instruction.wideLiteral in LEGACY_CONFIG_IDS
            }
            if (hits) matches += method.key()
        }
    }
    return matches.toList()
}

private fun Method.isEmployeeGateCandidate(): Boolean =
    implementation != null && localRegisterCount() >= 1 && shape() == GATE_SHAPE

private fun Method.callsUnsafeBooleanGate(): Boolean = code().any { instruction ->
    val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
    ref.definingClass == UNSAFE_CONTEXT && ref.parameterTypes.map(Any::toString) == listOf("J") && ref.returnType == "Z"
}

private fun Method.shape() = "(${parameterTypes.joinToString("")})$returnType"

private fun MethodReference.shape() = "(${parameterTypes.joinToString("")})$returnType"

private fun Method.key() = "$definingClass->$name$GATE_SHAPE"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
