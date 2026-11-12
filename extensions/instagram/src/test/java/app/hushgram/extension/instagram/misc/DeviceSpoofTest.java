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
        initial.put("66119:", new JSONArray().put("7: : custom"));
        Files.write(mcFile.toPath(), initial.toString().getBytes(StandardCharsets.UTF_8));

        DeviceSpoof.onAppStart(RuntimeEnvironment.getApplication());
        assertTrue(markerFile.exists());
        assertTrue(mcFile.exists());

        JSONObject enabled = new JSONObject(new String(Files.readAllBytes(mcFile.toPath()), StandardCharsets.UTF_8));
        assertTrue(enabled.has("99999:"));
        JSONArray c66119 = enabled.getJSONArray("66119:");
        assertEquals(2, c66119.length());
        assertEquals("7: : custom", c66119.getString(0));
        assertEquals("0: : true", c66119.getString(1));
        assertEquals("true", enabled.getJSONArray("68437:").getString(0).endsWith("false") ? "true" : "false");

        Settings.SPOOF_DEVICE.save(false);
        DeviceSpoof.onAppStart(RuntimeEnvironment.getApplication());
        assertFalse(markerFile.exists());
        assertTrue(mcFile.exists());

        JSONObject disabled = new JSONObject(new String(Files.readAllBytes(mcFile.toPath()), StandardCharsets.UTF_8));
        assertTrue(disabled.has("99999:"));
        assertEquals(1, disabled.getJSONArray("66119:").length());
        assertEquals("7: : custom", disabled.getJSONArray("66119:").getString(0));
        assertFalse(disabled.has("110796:"));
    }
}
