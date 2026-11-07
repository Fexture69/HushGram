/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** Tests the Samsung Galaxy S24+ Exynos 2400 optimization preset and device checks. */
@RunWith(RobolectricTestRunner.class)
public class ExynosOptimizerTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        Settings.HIGHEST_REFRESH_RATE.save(false);
        Settings.STOP_HEARTBEAT.save(false);
        Settings.STOP_UPLOAD_ALARM.save(false);
        Settings.CLEAR_MEDIA_CACHE.save(false);
        Settings.TURN_OFF_HDR_BOOSTS.save(false);
    }

    @After
    public void cleanup() {
        Settings.HIGHEST_REFRESH_RATE.resetToDefault();
        Settings.STOP_HEARTBEAT.resetToDefault();
        Settings.STOP_UPLOAD_ALARM.resetToDefault();
        Settings.CLEAR_MEDIA_CACHE.resetToDefault();
        Settings.TURN_OFF_HDR_BOOSTS.resetToDefault();
    }

    @Test
    public void applyS24PlusPresetConfiguresPerformanceAndBatterySettings() {
        String result = ExynosOptimizer.applyS24PlusPreset(RuntimeEnvironment.getApplication());
        assertTrue(result.contains("S24+ Exynos 2400 preset"));

        assertTrue("120Hz refresh rate must be enabled", Settings.HIGHEST_REFRESH_RATE.get());
        assertTrue("Heartbeat alarm must be stopped", Settings.STOP_HEARTBEAT.get());
        assertTrue("Upload alarm must be stopped", Settings.STOP_UPLOAD_ALARM.get());
        assertTrue("Media cache must be enabled", Settings.CLEAR_MEDIA_CACHE.get());
        assertTrue("HDR boosts must be turned off", Settings.TURN_OFF_HDR_BOOSTS.get());
    }

    @Test
    public void verifyHardwareConstants() {
        assertEquals("s5e9945", ExynosOptimizer.EXYNOS_2400_HARDWARE);
        assertEquals("e2s", ExynosOptimizer.S24_PLUS_BOARD);
        assertEquals("SM-S926", ExynosOptimizer.S24_PLUS_MODEL_PREFIX);
    }
}
