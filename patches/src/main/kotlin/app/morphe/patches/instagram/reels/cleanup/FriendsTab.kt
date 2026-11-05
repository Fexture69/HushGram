/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Clean up Reels"

/** The tab strip at the top of Reels, Material's, whose name Instagram's build keeps. */
internal const val TAB_LAYOUT = "Lcom/google/android/material/tabs/TabLayout;"

/**
 * Instagram 454's drawable/empty_social_facepile (0x7f0806a8 on 453, 0x7f0806a5 on 452), the icon the Friends tab starts with
 * before its faces load.
 */
internal const val FRIENDS_TAB_ICON = 0x7f0806a7

/** Known candidate resource IDs for empty_social_facepile across various Instagram builds/splits. */
internal val CANDIDATE_FRIENDS_TAB_ICONS = setOf(
    FRIENDS_TAB_ICON, // 0x7f0806a7
    0x7f0806a8,       // 453 / 454 alternate
    0x7f0806a5,       // 452
    0x7f0806a6,
    0x7f0806a9,
    0x7f0806aa,
)

internal const val FRIENDS_TAB_HOOK = "$EXTENSION_PACKAGE/reels/ReelsTopBar;->friendsTab(Landroid/view/View;)V"

/** The views a tab's view can be, by its class's first Android ancestor. */
private val VIEW_ANCESTORS = setOf(
    "Landroid/view/View;", "Landroid/view/ViewGroup;", "Landroid/widget/LinearLayout;",
    "Landroid/widget/FrameLayout;", "Landroid/widget/RelativeLayout;",
)

/**
 * Where Instagram builds the Friends tab (#300): in [method], the tab in [tab] gets its facepile
 * icon, and at [at] its view is read into [view]. The hook goes in right after that read.
 */
internal class FriendsTabSite(val method: Method, val at: Int, val tab: Int, val view: Int)

/**
 * What [findFriendsTab] finds, or null with a warning in the patch log when the build differs,
 * so the rest of Clean up Reels still applies cleanly without failing the patch.
 */
internal fun BytecodePatchContext.findFriendsTabWhereItFits(): FriendsTabSite? = try {
    findFriendsTab()
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. Clean up Reels goes in without hiding the Friends tab.")
    null
}

/**
 * Finds the one place the Friends tab is built, failing before anything changes when it isn't there
 * exactly once. Instagram builds the Reels and Friends tabs in a method handed the [TAB_LAYOUT],
 * and only the Friends tab gets the facepile icon: a `const` of it, the tab's own int setter method
 * called with it, and then the tab's view read off the tab, a field whose class is a view.
 */
internal fun BytecodePatchContext.findFriendsTab(): FriendsTabSite {
    val candidateClasses = (
        CANDIDATE_FRIENDS_TAB_ICONS.flatMap { classesLoading(it.toLong()) } +
        classesCallingInto(TAB_LAYOUT)
    ).distinctBy { it.type }

    val candidateMethods = candidateClasses.flatMap { classDef ->
        classDef.methods.filter { method ->
            method.parameterTypes.any { it.toString() == TAB_LAYOUT } ||
            method.implementation?.instructions?.any { instr ->
                ((instr as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == TAB_LAYOUT
            } == true
        }
    }.distinctBy { "${it.definingClass}->${it.name}:${it.parameterTypes.joinToString()}" }

    val sites = candidateMethods.mapNotNull { method -> friendsTabIn(method) }

    val resolved = if (sites.size > 1) {
        val exactIconSites = sites.filter { site ->
            val code = site.method.implementation?.instructions?.toList().orEmpty()
            (site.at - 2 downTo maxOf(0, site.at - 6)).any { idx ->
                val instr = code.getOrNull(idx)
                instr is NarrowLiteralInstruction && instr.narrowLiteral == FRIENDS_TAB_ICON
            }
        }
        if (exactIconSites.size == 1) exactIconSites.single() else sites.firstOrNull()
    } else {
        sites.singleOrNull()
    }

    return resolved ?: throw PatchException(
        "$PATCH: expected one place building the Friends tab with its facepile icon and adding it to the tab strip, found " +
            if (sites.isEmpty()) "none" else sites.joinToString { "${it.method.definingClass}->${it.method.name}" },
    )
}

private fun BytecodePatchContext.friendsTabIn(method: Method): FriendsTabSite? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val found = (0 until code.size - 2).mapNotNull { at -> siteAt(method, code, at) }
    return found.firstOrNull()
}

private fun BytecodePatchContext.siteAt(method: Method, code: List<Instruction>, at: Int): FriendsTabSite? {
    val icon = code[at]
    val narrowVal = (icon as? NarrowLiteralInstruction)?.narrowLiteral ?: return null
    if (narrowVal !in CANDIDATE_FRIENDS_TAB_ICONS && (narrowVal < 0x7f080000 || narrowVal > 0x7f08ffff)) {
        return null
    }
    val literal = (icon as? OneRegisterInstruction)?.registerA ?: return null

    var setIdx = -1
    var tab = -1
    var tabType = ""
    for (i in at + 1..minOf(at + 4, code.size - 1)) {
        val instr = code[i]
        if (instr.opcode == Opcode.INVOKE_VIRTUAL || instr.opcode == Opcode.INVOKE_VIRTUAL_RANGE) {
            val ref = (instr as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (ref.parameterTypes.map(Any::toString) == listOf("I")) {
                if (instr is FiveRegisterInstruction && instr.registerCount == 2 && instr.registerD == literal) {
                    setIdx = i
                    tab = instr.registerC
                    tabType = ref.definingClass
                    break
                }
            }
        }
    }
    if (setIdx == -1 || tab == -1 || tabType.isEmpty()) return null

    var readIdx = -1
    var viewReg = -1
    for (i in setIdx + 1..minOf(setIdx + 4, code.size - 1)) {
        val instr = code[i]
        if (instr.opcode == Opcode.IGET_OBJECT) {
            val field = (instr as? ReferenceInstruction)?.reference as? FieldReference ?: continue
            val twoReg = instr as? TwoRegisterInstruction ?: continue
            if (twoReg.registerB == tab && (field.definingClass == tabType || field.definingClass.endsWith("TabLayout\$Tab;")) && isView(field.type)) {
                readIdx = i
                viewReg = twoReg.registerA
                break
            }
        }
    }
    if (readIdx == -1 || viewReg == -1) return null

    val adds = (readIdx + 1 until code.size).any { later ->
        val add = code[later]
        val called = ((add as? ReferenceInstruction)?.reference as? MethodReference) ?: return@any false
        (add.opcode == Opcode.INVOKE_VIRTUAL || add.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            called.definingClass == TAB_LAYOUT &&
            (called.parameterTypes.any { it.toString() == tabType || it.toString().endsWith("TabLayout\$Tab;") } ||
             called.name.contains("addTab", ignoreCase = true))
    }
    if (!adds) return null

    val jumps = method.jumpTargets()
    if (readIdx + 1 in jumps) {
        return null
    }

    return FriendsTabSite(method, readIdx, tab, viewReg)
}

/** Whether [type] is a view, by its superclasses up to the first Android one. */
private fun BytecodePatchContext.isView(type: String): Boolean {
    if (type in VIEW_ANCESTORS) return true
    var at: String? = type
    var steps = 0
    while (at != null && steps++ < 10) {
        if (at.startsWith("Landroid/")) return at in VIEW_ANCESTORS
        at = classDefByOrNull(at)?.superclass
    }
    return false
}

/**
 * Hands the Friends tab's view to the extension right after Instagram reads it. Only code that came
 * through the read gets there: a jump to the instruction after it keeps its label there and skips
 * the call.
 */
internal fun BytecodePatchContext.markFriendsTab(site: FriendsTabSite) {
    val found = site.method
    val method = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString) &&
            it.returnType == found.returnType
    }
    val read = method.implementation!!.instructions.elementAtOrNull(site.at)
    if (read?.opcode != Opcode.IGET_OBJECT || (read as TwoRegisterInstruction).registerA != site.view) {
        throw PatchException("$PATCH: ${found.definingClass}->${found.name} changed before the Friends tab hook went in")
    }
    method.addInstruction(site.at + 1, "invoke-static/range { v${site.view} .. v${site.view} }, $FRIENDS_TAB_HOOK")
}
