/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.Collections;
import java.util.EnumSet;

import app.hushgram.extension.instagram.misc.DeviceSpoof;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Spoof device's rows: under Ads and privacy, switch off to start and device choice gated by it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SpoofDeviceSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before
    public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.SPOOF_DEVICE.resetToDefault();
        Settings.SPOOF_DEVICE_PROFILE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After
    public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SPOOF_DEVICE.resetToDefault();
        Settings.SPOOF_DEVICE_PROFILE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed
                ? EnumSet.of(PatchFamily.SPOOF_DEVICE)
                : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test
    public void missingPatchHasNoRows() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.SPOOF_DEVICE.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.SPOOF_DEVICE_PROFILE.key));
    }

    @Test
    public void bothRowsSitUnderPrivacyAndProfileWaitsForSwitch() throws Exception {
        open(true);
        SwitchPreference toggle = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SPOOF_DEVICE.key);
        HushgramPreferenceFragment.ChoiceRow<?> profile =
                (HushgramPreferenceFragment.ChoiceRow<?>) page.getPreferenceScreen().findPreference(Settings.SPOOF_DEVICE_PROFILE.key);
        assertNotNull(toggle);
        assertNotNull(profile);
        assertEquals("Spoof device", toggle.getTitle().toString());
        assertEquals("Device", profile.getTitle().toString());

        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), toggle);
        assertNotNull(section);
        assertEquals("Ads and privacy", section.getTitle().toString());
        assertSame(section, categoryHolding(page.getPreferenceScreen(), profile));

        assertFalse(toggle.isChecked());
        assertFalse(Settings.SPOOF_DEVICE.get());
        assertFalse("profile waits for the switch", profile.isEnabled());
        assertEquals("Turn on Spoof device to use this choice.", profile.getSummary().toString());

        toggle.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(profile.isEnabled());
        assertTrue(profile.getSummary().toString().contains("Pixel 11 Pro XL"));

        profile.setValue(DeviceSpoof.Profile.S26.name());
        ShadowLooper.idleMainLooper();
        assertEquals(DeviceSpoof.Profile.S26, Settings.SPOOF_DEVICE_PROFILE.savedValue());
        assertTrue(profile.getSummary().toString().contains("Galaxy S26 Ultra"));

        assertEquals(Collections.singletonList(Settings.SPOOF_DEVICE), PatchFamily.SPOOF_DEVICE.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SPOOF_DEVICE.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SPOOF_DEVICE_PROFILE.key));
    }

    @Nullable
    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference target) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference item = group.getPreference(i);
            if (item instanceof PreferenceCategory) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) item, target);
                if (found != null) return found;
                for (int c = 0; c < ((PreferenceCategory) item).getPreferenceCount(); c++) {
                    if (((PreferenceCategory) item).getPreference(c) == target) return (PreferenceCategory) item;
                }
            }
        }
        return null;
    }
}
