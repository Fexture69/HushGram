/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Spoof device: Build/property/feature stand-ins and MobileConfig HDR override sync. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class DeviceSpoofTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private File mcDir;
    private File mcFile;
    private File markerFile;

    @Before
    public void setUp() {
        DeviceSpoof.clearEarlyStateForTests();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SPOOF_DEVICE.save(true);
        Settings.SPOOF_DEVICE_PROFILE.save(DeviceSpoof.Profile.PIXEL);
        HookStatus.clear();
        mcDir = new File(RuntimeEnvironment.getApplication().getFilesDir(), "mobileconfig");
        mcFile = new File(mcDir, "mc_overrides.json");
        markerFile = new File(mcDir, "hg_spoof_overrides_applied");
        deleteTree(mcDir);
    }

    @After
    public void tearDown() {
        DeviceSpoof.clearEarlyStateForTests();
        Settings.SPOOF_DEVICE.resetToDefault();
        Settings.SPOOF_DEVICE_PROFILE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        deleteTree(mcDir);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    @Test
    public void pixelAndS26PropertiesMatchExpectedProfiles() {
        assertEquals("Pixel 11 Pro XL", DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.product.model"));
        assertEquals("Pixel 11 Pro XL", DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.product.vendor.model"));
        assertEquals("google", DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.product.system_ext.brand"));
        assertEquals("kodiak", DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.product.name"));
        assertEquals("CD1A.260714.001.A9", DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.build.id"));
        assertEquals("CD1A.260714.001.A9", DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.build.display.id"));
        assertEquals("kodiak", DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.hardware"));
        assertEquals("kodiak", DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.product.board"));
        assertEquals("kodiak", DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.build.product"));
        assertEquals("Tensor G6", DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.soc.model"));
        assertEquals("malibu", DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.board.platform"));
        assertEquals(
                DeviceSpoof.Profile.PIXEL.fingerprint,
                DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.vendor.build.fingerprint")
        );

        assertEquals("samsung", DeviceSpoof.propertyValue(DeviceSpoof.Profile.S26, "ro.product.manufacturer"));
        assertEquals("SM-S948B", DeviceSpoof.propertyValue(DeviceSpoof.Profile.S26, "ro.product.model"));
        assertEquals("QTI", DeviceSpoof.propertyValue(DeviceSpoof.Profile.S26, "ro.soc.manufacturer"));
        assertEquals("SM8850", DeviceSpoof.propertyValue(DeviceSpoof.Profile.S26, "ro.soc.model"));
        assertNull(DeviceSpoof.propertyValue(DeviceSpoof.Profile.S26, "ro.product.device"));
        assertNull(DeviceSpoof.propertyValue(DeviceSpoof.Profile.S26, "ro.build.fingerprint"));
        assertNull(DeviceSpoof.propertyValue(DeviceSpoof.Profile.PIXEL, "ro.unrelated.prop"));
    }

    @Test
    public void buildAndFeatureStandInsRespectSwitchProfileAndPause() {
        assertEquals("Google", DeviceSpoof.buildManufacturer("RealOEM"));
        assertEquals("Pixel 11 Pro XL", DeviceSpoof.buildModel("RealModel"));
        assertEquals("kodiak", DeviceSpoof.buildDevice("RealDevice"));
        assertEquals("kodiak", DeviceSpoof.buildHardware("RealHardware"));
        assertEquals("kodiak", DeviceSpoof.buildBoard("RealBoard"));
        assertEquals("CD1A.260714.001.A9", DeviceSpoof.buildDisplay("RealDisplay"));
        assertTrue(DeviceSpoof.readFeature("com.google.android.feature.PIXEL_2026_EXPERIENCE",
                () -> DeviceSpoof.Profile.PIXEL));
        assertFalse(DeviceSpoof.readFeature("android.hardware.camera", () -> DeviceSpoof.Profile.PIXEL));

        Settings.SPOOF_DEVICE_PROFILE.save(DeviceSpoof.Profile.S26);
        assertEquals("SM-S948B", DeviceSpoof.buildModel("RealModel"));
        assertEquals("RealDevice", DeviceSpoof.buildDevice("RealDevice"));
        assertEquals("RealFingerprint", DeviceSpoof.buildFingerprint("RealFingerprint"));
        assertFalse(DeviceSpoof.readFeature("com.google.android.feature.PIXEL_2026_EXPERIENCE",
                () -> DeviceSpoof.Profile.S26));

        Settings.SPOOF_DEVICE.save(false);
        assertEquals("RealModel", DeviceSpoof.buildModel("RealModel"));
        Settings.SPOOF_DEVICE.save(true);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("RealModel", DeviceSpoof.buildModel("RealModel"));
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() ->
                assertEquals("RealModel", DeviceSpoof.buildModel("RealModel")));
        SettingsContextRule.beforeThePauseIsDecided(() ->
                assertEquals("RealModel", DeviceSpoof.buildModel("RealModel")));

        assertEquals("RealModel", DeviceSpoof.readBuildField("RealModel", p -> p.model, () -> {
            throw new IllegalStateException("boom");
        }));
        assertFalse(HookStatus.missing(FamilyNames.SPOOF_DEVICE).isEmpty());
    }

    @Test
    public void hdrOverridesMergeCleanlyAndRemoveOnlyAddedEntries() throws Exception {
        assertTrue(mcDir.mkdirs());
        JSONObject initial = new JSONObject();
        initial.put("99999:", new JSONArray().put("1: : true"));
        initial.put("66119:ig_named_config", new JSONArray().put("7: : custom").put("0: : false"));
        initial.put("74463:", new JSONArray().put("20: : true"));
        Files.write(mcFile.toPath(), initial.toString().getBytes(StandardCharsets.UTF_8));

        DeviceSpoof.onAppStart(RuntimeEnvironment.getApplication());
        assertTrue(markerFile.exists());
        assertTrue(mcFile.exists());

        JSONObject enabled = new JSONObject(new String(Files.readAllBytes(mcFile.toPath()), StandardCharsets.UTF_8));
        assertTrue(enabled.has("99999:"));
        assertFalse(enabled.has("66119:"));
        JSONArray c66119 = enabled.getJSONArray("66119:ig_named_config");
        assertEquals(2, c66119.length());
        assertEquals("7: : custom", c66119.getString(0));
        assertEquals("0: : true", c66119.getString(1));
        JSONArray c74463 = enabled.getJSONArray("74463:");
        assertEquals(2, c74463.length());
        assertEquals("4: : true", c74463.getString(0));
        assertEquals("20: : false", c74463.getString(1));
        assertTrue(enabled.getJSONArray("68437:").getString(0).endsWith("false"));

        // If Instagram drops an override while the marker remains, next start heals mc_overrides.json.
        enabled.remove("68437:");
        Files.write(mcFile.toPath(), enabled.toString().getBytes(StandardCharsets.UTF_8));
        DeviceSpoof.onAppStart(RuntimeEnvironment.getApplication());
        JSONObject healed = new JSONObject(new String(Files.readAllBytes(mcFile.toPath()), StandardCharsets.UTF_8));
        assertEquals("5: : false", healed.getJSONArray("68437:").getString(0));

        Settings.SPOOF_DEVICE.save(false);
        DeviceSpoof.onAppStart(RuntimeEnvironment.getApplication());
        assertFalse(markerFile.exists());
        assertTrue(mcFile.exists());

        JSONObject disabled = new JSONObject(new String(Files.readAllBytes(mcFile.toPath()), StandardCharsets.UTF_8));
        assertTrue(disabled.has("99999:"));
        assertEquals(1, disabled.getJSONArray("66119:ig_named_config").length());
        assertEquals("7: : custom", disabled.getJSONArray("66119:ig_named_config").getString(0));
        assertFalse(disabled.has("74463:"));
        assertFalse(disabled.has("110796:"));
    }

    @Test
    public void attachBaseContextSpoofsBeforeSettingsReadyAndRespectsPause() throws Exception {
        Settings.SPOOF_DEVICE.save(true);
        Settings.SPOOF_DEVICE_PROFILE.save(DeviceSpoof.Profile.S26);
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertEquals("RealModel", DeviceSpoof.buildModel("RealModel"));
            DeviceSpoof.onAttachBaseContext(RuntimeEnvironment.getApplication());
            assertEquals("SM-S948B", DeviceSpoof.buildModel("RealModel"));
            assertTrue(mcFile.exists());
            assertTrue(markerFile.exists());
        });

        DeviceSpoof.clearEarlyStateForTests();
        deleteTree(mcDir);
        BaseSettings.PAUSED.save(true);
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            DeviceSpoof.onAttachBaseContext(RuntimeEnvironment.getApplication());
            assertEquals("RealModel", DeviceSpoof.buildModel("RealModel"));
            assertFalse(mcFile.exists());
        });
    }
}
