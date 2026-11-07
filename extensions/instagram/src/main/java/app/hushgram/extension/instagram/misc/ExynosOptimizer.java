/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.os.Build;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * Hardware detection and optimization profile specifically tailored for the
 * Samsung Galaxy S24+ (SM-S926B / SM-S926N / SM-S9260) running the Samsung Exynos 2400
 * Deca-core chipset (ARMv9-A pure 64-bit) with Samsung Xclipse 940 (AMD RDNA3) GPU.
 *
 * <p>Key optimizations for this target:
 * <ul>
 *   <li><b>120Hz LTPO Synchronization:</b> Requests highest display refresh rate and
 *       explicit preferred frame rate (120Hz) to prevent Samsung's One UI adaptive display governor
 *       from dropping to 60Hz or stuttering during fast feed scrolling.</li>
 *   <li><b>AMD Xclipse 940 GPU Offload (SurfaceView):</b> Prioritizes direct hardware composer
 *       (SurfaceFlinger/HWC) overlays rather than forcing GPU TextureView composition, reducing GPU
 *       workload, thermal throttling, and frame drops in Reels.</li>
 *   <li><b>Exynos 2400 Deca-Core Battery Optimization:</b> Suppresses background heartbeat alarms
 *       and telemetry wake-ups, preventing spurious wake-ups of the high-power Cortex-X4 prime core
 *       and keeping modem/CPU in low-power deep sleep.</li>
 *   <li><b>Storage I/O Lightweighting:</b> Aggressively clears media cache files to keep flash storage
 *       clean and avoid I/O bottlenecks on UFS 4.0 storage.</li>
 * </ul>
 */
public final class ExynosOptimizer {
    /** Exynos 2400 chipset identifier (s5e9945). */
    public static final String EXYNOS_2400_HARDWARE = "s5e9945";

    /** Samsung Galaxy S24+ board code name. */
    public static final String S24_PLUS_BOARD = "e2s";

    /** Samsung Galaxy S24+ model prefix. */
    public static final String S24_PLUS_MODEL_PREFIX = "SM-S926";

    private ExynosOptimizer() {
    }

    /**
     * Answers whether the host device is running a Samsung Exynos 2400 processor.
     */
    public static boolean isExynos2400Device() {
        try {
            String hardware = Build.HARDWARE != null ? Build.HARDWARE.toLowerCase() : "";
            String board = Build.BOARD != null ? Build.BOARD.toLowerCase() : "";
            if (hardware.contains(EXYNOS_2400_HARDWARE) || board.contains(S24_PLUS_BOARD) || board.contains("s5e9945")) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                String soc = Build.SOC_MODEL != null ? Build.SOC_MODEL.toLowerCase() : "";
                if (soc.contains("2400") || soc.contains("s5e9945")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Answers whether the host device is specifically a Samsung Galaxy S24+.
     */
    public static boolean isS24Plus() {
        try {
            String model = Build.MODEL != null ? Build.MODEL : "";
            if (model.startsWith(S24_PLUS_MODEL_PREFIX)) {
                return true;
            }
            return isExynos2400Device() && (Build.DEVICE != null && Build.DEVICE.toLowerCase().contains("e2s"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Applies the complete performance and battery optimization preset designed for
     * Samsung Galaxy S24+ (Exynos 2400).
     *
     * @return summary string detailing the optimized settings.
     */
    public static String applyS24PlusPreset(@Nullable Context context) {
        // 1. Lock 120Hz smooth scrolling for the 120Hz Dynamic AMOLED 2X panel
        Settings.HIGHEST_REFRESH_RATE.save(true);

        // 2. Prioritize hardware video overlays (SurfaceView) on AMD Xclipse 940 GPU
        Settings.HARDWARE_VIDEO_OVERLAYS.save(true);

        // 3. Stop background wakeups to keep Cortex-X4 in deep sleep
        Settings.STOP_HEARTBEAT.save(true);
        Settings.STOP_UPLOAD_ALARM.save(true);

        // 4. Enable automatic media cache pruning for lightweight storage
        Settings.CLEAR_MEDIA_CACHE.save(true);

        // 5. Disable aggressive HDR brightness boost to reduce GPU tone-mapping thermals
        Settings.TURN_OFF_HDR_BOOSTS.save(true);

        Logger.printDebug(() -> "ExynosOptimizer: Applied Samsung Galaxy S24+ Exynos 2400 optimization preset.");

        return "Configured S24+ Exynos 2400 preset: 120Hz refresh rate, SurfaceView hardware acceleration, "
                + "heartbeat wake-up suppression, analytics alarm suppression, and media cache pruning enabled.";
    }
}
