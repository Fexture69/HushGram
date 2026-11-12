/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployeeGateTest {
    private val userSession = "Lcom/instagram/common/session/UserSession;"
    private val unsafeContext = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val gate = "LX/EmployeeGate;"

    @Test
    fun tier1FindsAndUnlocksIsEmployeeCallee() {
        val gateMethod = method(
            gate, "isEmp", listOf(userSession), "Z", 3,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
        val callerMethod = method(
            "LX/SettingsHost;", "buildMenu", listOf(userSession), "V", 2,
            AccessFlags.PUBLIC.value,
            """
                const-string v0, "is_employee"
                invoke-static { p1 }, $gate->isEmp($userSession)Z
                return-void
            """,
        )
        val patch = PatchContexts.of(
            listOf(
                ImmutableClassDef(gate, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(gateMethod)),
                ImmutableClassDef("LX/SettingsHost;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(callerMethod)),
            ),
        )

        val gates = patch.findEmployeeGates()
        assertEquals(listOf("$gate->isEmp($userSession)Z"), gates)

        patch.applyEmployeeGates(gates)
        val code = patch.classDefBy(gate).methods.single { it.name == "isEmp" }.implementation!!.instructions.toList()
        assertEquals(UNLOCK_EMPLOYEE, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals(Opcode.CONST_4, code[3].opcode)
        assertEquals(1, (code[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, code[4].opcode)
    }

    @Test
    fun tier2FindsStructuralUnsafeContextGateByCallerFanIn() {
        val gateMethod = method(
            gate, "A00", listOf(userSession), "Z", 5,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            """
                const/4 v0, 0x0
                const-wide v1, 0x1234L
                invoke-interface { v0, v1, v2 }, $unsafeContext->B81(J)Z
                move-result v0
                return v0
            """,
        )
        val decoyMethod = method(
            "LX/DecoyGate;", "A01", listOf(userSession), "Z", 5,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            """
                const/4 v0, 0x0
                const-wide v1, 0x5678L
                invoke-interface { v0, v1, v2 }, $unsafeContext->B81(J)Z
                move-result v0
                return v0
            """,
        )
        val callers = (1..3).map { index ->
            ImmutableClassDef(
                "LX/Caller$index;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    method(
                        "LX/Caller$index;", "check", listOf(userSession), "Z", 2,
                        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                        """
                            invoke-static { p0 }, $gate->A00($userSession)Z
                            move-result v0
                            return v0
                        """,
                    ),
                ),
            )
        }
        val singleCaller = ImmutableClassDef(
            "LX/DecoyCaller;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                method(
                    "LX/DecoyCaller;", "check", listOf(userSession), "Z", 2,
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    """
                        invoke-static { p0 }, LX/DecoyGate;->A01($userSession)Z
                        move-result v0
                        return v0
                    """,
                ),
            ),
        )
        val patch = PatchContexts.of(
            listOf(
                ImmutableClassDef(gate, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(gateMethod)),
                ImmutableClassDef("LX/DecoyGate;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(decoyMethod)),
                singleCaller,
            ) + callers,
        )

        assertEquals(listOf("$gate->A00($userSession)Z"), patch.findEmployeeGates())
    }

    @Test
    fun tier3FallsBackToLegacyConfigId() {
        val legacyGate = method(
            gate, "A02", listOf(userSession), "Z", 5,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            """
                const-wide v0, 36310834636390667L
                const/4 v0, 0x0
                return v0
            """,
        )
        val patch = PatchContexts.of(
            listOf(ImmutableClassDef(gate, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(legacyGate))),
        )

        val gates = patch.findEmployeeGates()
        assertTrue(gates.contains("$gate->A02($userSession)Z"))
    }

    private fun method(owner: String, name: String, parameters: List<String>, returnType: String, registers: Int, flags: Int, body: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }
}
