/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Ported from ReSo7200/InstaEclipse (GPL-3.0):
 * https://github.com/ReSo7200/InstaEclipse
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Spoof device" patch.
 *
 * <p>Spoofs Android device identity ({@link Build} fields, {@code SystemProperties}, and Pixel
 * {@link PackageManager#hasSystemFeature} checks) to a {@link Profile#PIXEL Google Pixel 11 Pro XL}
 * or {@link Profile#S26 Samsung Galaxy S26 Ultra}, and manages the matching HDR MobileConfig
 * developer overrides in {@code mobileconfig/mc_overrides.json}.
 *
 * <p>With the switch off, HushGram paused, safe mode active, settings not ready yet, or any
 * failure, every property, feature check and {@link Build} read falls back to what the phone
 * reports, and any previously added HDR overrides are cleanly removed at startup.
 */
public final class DeviceSpoof {
    /** Device profiles available to spoof as. */
    public enum Profile {
        PIXEL(
                "Google",
                "google",
                "Pixel 11 Pro XL",
                "kodiak",
                "kodiak",
                "google/kodiak/kodiak:17/CD1A.260714.001.A9/15938155:user/release-keys",
                "CD1A.260714.001.A9",
                "Google",
                "Tensor G6",
                "malibu",
                "com.google.android.feature.PIXEL_EXPERIENCE",
                "com.google.android.feature.PIXEL_2026_EXPERIENCE",
                "com.google.android.feature.GOOGLE_BUILD",
                "com.google.android.feature.GOOGLE_EXPERIENCE"
        ),
        S26(
                "samsung",
                "samsung",
                "SM-S948B",
                null,
                null,
                null,
                null,
                "QTI",
                "SM8850",
                null
        );

        public final String manufacturer;
        public final String brand;
        public final String model;
        @Nullable public final String device;
        @Nullable public final String product;
        @Nullable public final String fingerprint;
        @Nullable public final String buildId;
        public final String socManufacturer;
        public final String socModel;
        @Nullable public final String platform;
        public final Set<String> features;

        Profile(
                String manufacturer,
                String brand,
                String model,
                @Nullable String device,
                @Nullable String product,
                @Nullable String fingerprint,
                @Nullable String buildId,
                String socManufacturer,
                String socModel,
                @Nullable String platform,
                String... features
        ) {
            this.manufacturer = manufacturer;
            this.brand = brand;
            this.model = model;
            this.device = device;
            this.product = product;
            this.fingerprint = fingerprint;
            this.buildId = buildId;
            this.socManufacturer = socManufacturer;
            this.socModel = socModel;
            this.platform = platform;
            this.features = features.length == 0
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new HashSet<>(Arrays.asList(features)));
        }
    }

    static final String SPOOFED = "device check spoofed";
    static final String STEP = "device spoof";

    static final String HDR_OVERRIDES_JSON =
            "{\"66119:\":[\"0: : true\"],\"110796:\":[\"92: : true\"],"
                    + "\"74463:\":[\"4: : true\",\"20: : false\"],"
                    + "\"86550:\":[\"9: : true\"],\"77821:\":[\"42: : true\"],"
                    + "\"107732:\":[\"12: : true\"],"
                    + "\"124768:\":[\"32: : true\",\"12: : true\"],"
                    + "\"102818:\":[\"2: : true\",\"6: : false\",\"1: : true\",\"0: : true\",\"3: : true\"],"
                    + "\"50233:\":[\"11: : true\"],\"120903:\":[\"0: : true\"],"
                    + "\"124767:\":[\"31: : true\",\"13: : true\"],"
                    + "\"64571:\":[\"12: : true\"],"
                    + "\"78165:\":[\"381: : false\",\"245: : true\",\"167: : false\",\"110: : true\",\"97: : false\"],"
                    + "\"68437:\":[\"5: : false\"]}";

    private static final String MC_DIR = "mobileconfig";
    private static final String MC_FILE = "mc_overrides.json";
    private static final String MC_MARKER = "hg_spoof_overrides_applied";

    private static final Pattern PROP_PRODUCT =
            Pattern.compile("^ro\\.product\\.(?:[a-z_]+\\.)?(model|brand|manufacturer|device|name)$");
    private static final Pattern PROP_FINGERPRINT =
            Pattern.compile("^ro\\.(?:[a-z_]+\\.)?build\\.fingerprint$");

    @Nullable private static final Method SYS_PROP_GET_1 = findSystemPropertiesMethod(String.class);
    @Nullable private static final Method SYS_PROP_GET_2 = findSystemPropertiesMethod(String.class, String.class);

    private DeviceSpoof() {
    }

    /**
     * Runs at application startup right after {@link Utils#setContext(Context)}: syncs the HDR
     * MobileConfig overrides and reflectively sets {@link Build} fields when spoofing is active.
     */
    public static void onAppStart(@Nullable Context context) {
        onAppStart(context, DeviceSpoof::activeProfile);
    }

    static void onAppStart(@Nullable Context context, Supplier<Profile> profileSupplier) {
        try {
            HookStatus.invoked(FamilyNames.SPOOF_DEVICE);
            Profile active = profileSupplier.get();
            if (context != null && Utils.isMainProcess()) {
                syncOverrides(context, active != null);
            }
            if (active != null) {
                applyBuildFields(active);
                HookStatus.counted(FamilyNames.SPOOF_DEVICE, SPOOFED);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPOOF_DEVICE, STEP, failure);
        }
    }

    // ---- Build field stand-ins --------------------------------------------------------------

    public static String buildManufacturer(String real) {
        return readBuildField(real, p -> p.manufacturer);
    }

    public static String buildBrand(String real) {
        return readBuildField(real, p -> p.brand);
    }

    public static String buildModel(String real) {
        return readBuildField(real, p -> p.model);
    }

    public static String buildDevice(String real) {
        return readBuildField(real, p -> p.device);
    }

    public static String buildProduct(String real) {
        return readBuildField(real, p -> p.product);
    }

    public static String buildFingerprint(String real) {
        return readBuildField(real, p -> p.fingerprint);
    }

    public static String buildId(String real) {
        return readBuildField(real, p -> p.buildId);
    }

    public static String buildSocManufacturer(String real) {
        return readBuildField(real, p -> p.socManufacturer);
    }

    public static String buildSocModel(String real) {
        return readBuildField(real, p -> p.socModel);
    }

    static String readBuildField(String real, Function<Profile, String> field) {
        return readBuildField(real, field, DeviceSpoof::activeProfile);
    }

    static String readBuildField(String real, Function<Profile, String> field, Supplier<Profile> profileSupplier) {
        try {
            HookStatus.invoked(FamilyNames.SPOOF_DEVICE);
            Profile profile = profileSupplier.get();
            if (profile == null) return real;
            String value = field.apply(profile);
            if (value == null) return real;
            HookStatus.counted(FamilyNames.SPOOF_DEVICE, SPOOFED);
            return value;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPOOF_DEVICE, STEP, failure);
            return real;
        }
    }

    // ---- SystemProperties stand-ins ---------------------------------------------------------

    public static String systemProperty(String key) {
        String spoofed = readProperty(key, DeviceSpoof::activeProfile);
        return spoofed != null ? spoofed : realSystemProperty(key);
    }

    public static String systemProperty(String key, String def) {
        String spoofed = readProperty(key, DeviceSpoof::activeProfile);
        return spoofed != null ? spoofed : realSystemProperty(key, def);
    }

    @Nullable
    static String readProperty(@Nullable String key, Supplier<Profile> profileSupplier) {
        try {
            HookStatus.invoked(FamilyNames.SPOOF_DEVICE);
            if (key == null) return null;
            Profile profile = profileSupplier.get();
            if (profile == null) return null;
            String value = propertyValue(profile, key);
            if (value != null) HookStatus.counted(FamilyNames.SPOOF_DEVICE, SPOOFED);
            return value;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPOOF_DEVICE, STEP, failure);
            return null;
        }
    }

    @Nullable
    public static String propertyValue(Profile profile, String key) {
        Matcher product = PROP_PRODUCT.matcher(key);
        if (product.matches()) {
            switch (product.group(1)) {
                case "model":
                    return profile.model;
                case "brand":
                    return profile.brand;
                case "manufacturer":
                    return profile.manufacturer;
                case "device":
                    return profile.device;
                case "name":
                    return profile.product;
                default:
                    return null;
            }
        }
        if (PROP_FINGERPRINT.matcher(key).matches()) return profile.fingerprint;
        switch (key) {
            case "ro.soc.manufacturer":
                return profile.socManufacturer;
            case "ro.soc.model":
                return profile.socModel;
            case "ro.board.platform":
                return profile.platform;
            case "ro.build.id":
                return profile.buildId;
            default:
                return null;
        }
    }

    // ---- PackageManager.hasSystemFeature stand-ins ------------------------------------------

    public static boolean hasSystemFeature(PackageManager pm, String name) {
        return readFeature(name, DeviceSpoof::activeProfile) || pm.hasSystemFeature(name);
    }

    public static boolean hasSystemFeature(PackageManager pm, String name, int version) {
        return readFeature(name, DeviceSpoof::activeProfile) || pm.hasSystemFeature(name, version);
    }

    static boolean readFeature(@Nullable String name, Supplier<Profile> profileSupplier) {
        try {
            HookStatus.invoked(FamilyNames.SPOOF_DEVICE);
            if (name == null) return false;
            Profile profile = profileSupplier.get();
            if (profile == null || !profile.features.contains(name)) return false;
            HookStatus.counted(FamilyNames.SPOOF_DEVICE, SPOOFED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPOOF_DEVICE, STEP, failure);
            return false;
        }
    }

    // ---- Profile lookup & reflection --------------------------------------------------------

    @Nullable
    private static Profile activeProfile() {
        if (!Utils.settingsReady() || !Settings.SPOOF_DEVICE.get()) return null;
        return Settings.SPOOF_DEVICE_PROFILE.get();
    }

    private static void applyBuildFields(Profile profile) {
        setStaticBuildField("MANUFACTURER", profile.manufacturer);
        setStaticBuildField("BRAND", profile.brand);
        setStaticBuildField("MODEL", profile.model);
        setStaticBuildField("DEVICE", profile.device);
        setStaticBuildField("PRODUCT", profile.product);
        setStaticBuildField("FINGERPRINT", profile.fingerprint);
        setStaticBuildField("ID", profile.buildId);
        setStaticBuildField("SOC_MANUFACTURER", profile.socManufacturer);
        setStaticBuildField("SOC_MODEL", profile.socModel);
    }

    private static void setStaticBuildField(String name, @Nullable String value) {
        if (value == null) return;
        try {
            Field field = Build.class.getField(name);
            if (Modifier.isFinal(field.getModifiers())) {
                try {
                    Field accessFlags = Field.class.getDeclaredField("accessFlags");
                    accessFlags.setAccessible(true);
                    accessFlags.setInt(field, field.getModifiers() & ~Modifier.FINAL);
                } catch (Throwable ignored) {
                    // Android runtime or JVM may forbid clearing final modifiers; bytecode hooks still cover reads.
                }
            }
            field.setAccessible(true);
            field.set(null, value);
        } catch (Throwable ignored) {
            // Unavailable fields (such as SOC_* before Android 12) or strict security managers fail open.
        }
    }

    @Nullable
    private static Method findSystemPropertiesMethod(Class<?>... parameterTypes) {
        try {
            return Class.forName("android.os.SystemProperties").getMethod("get", parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String realSystemProperty(String key) {
        if (SYS_PROP_GET_1 == null) return "";
        try {
            Object value = SYS_PROP_GET_1.invoke(null, key);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String realSystemProperty(String key, String def) {
        if (SYS_PROP_GET_2 == null) return def;
        try {
            Object value = SYS_PROP_GET_2.invoke(null, key, def);
            return value instanceof String ? (String) value : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    // ---- MobileConfig HDR override sync -----------------------------------------------------

    static synchronized void syncOverrides(Context context, boolean enable) {
        File mcDir = new File(context.getFilesDir(), MC_DIR);
        File mcFile = new File(mcDir, MC_FILE);
        File marker = new File(mcDir, MC_MARKER);
        if (!enable) {
            removeOverrides(mcFile, marker);
            return;
        }
        if (marker.exists() && mcFile.exists()) return;
        try {
            if (!mcDir.exists() && !mcDir.mkdirs()) return;
            JSONObject root = readJsonObject(mcFile);
            JSONObject hdr = new JSONObject(HDR_OVERRIDES_JSON);
            Iterator<String> keys = hdr.keys();
            while (keys.hasNext()) {
                String configKey = keys.next();
                JSONArray desired = hdr.getJSONArray(configKey);
                JSONArray existing = root.optJSONArray(configKey);
                if (existing == null) existing = new JSONArray();
                Set<String> present = toSet(existing);
                for (int i = 0; i < desired.length(); i++) {
                    String item = desired.getString(i);
                    if (present.add(item)) existing.put(item);
                }
                root.put(configKey, existing);
            }
            Files.write(mcFile.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            if (!marker.exists()) Files.write(marker.toPath(), new byte[0]);
        } catch (Throwable ignored) {
        }
    }

    private static void removeOverrides(File mcFile, File marker) {
        if (!marker.exists()) return;
        try {
            if (mcFile.exists()) {
                JSONObject root = readJsonObject(mcFile);
                Map<String, List<String>> hdr = parseHdrMap();
                for (Map.Entry<String, List<String>> entry : hdr.entrySet()) {
                    JSONArray existing = root.optJSONArray(entry.getKey());
                    if (existing == null) continue;
                    Set<String> remove = new HashSet<>(entry.getValue());
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < existing.length(); i++) {
                        String value = existing.optString(i, null);
                        if (value != null && !remove.contains(value)) kept.put(value);
                    }
                    if (kept.length() > 0) {
                        root.put(entry.getKey(), kept);
                    } else {
                        root.remove(entry.getKey());
                    }
                }
                if (root.length() > 0) {
                    Files.write(mcFile.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
                } else {
                    mcFile.delete();
                }
            }
            marker.delete();
        } catch (Throwable ignored) {
        }
    }

    private static JSONObject readJsonObject(File file) throws Exception {
        if (!file.exists()) return new JSONObject();
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static Set<String> toSet(JSONArray array) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String item = array.optString(i, null);
            if (item != null) set.add(item);
        }
        return set;
    }

    private static Map<String, List<String>> parseHdrMap() throws Exception {
        Map<String, List<String>> map = new LinkedHashMap<>();
        JSONObject hdr = new JSONObject(HDR_OVERRIDES_JSON);
        Iterator<String> keys = hdr.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONArray array = hdr.getJSONArray(key);
            String[] values = new String[array.length()];
            for (int i = 0; i < array.length(); i++) values[i] = array.getString(i);
            map.put(key, Arrays.asList(values));
        }
        return map;
    }
}
