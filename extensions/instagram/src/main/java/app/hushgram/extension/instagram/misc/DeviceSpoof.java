/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Ported from ReSo7200/InstaEclipse (GPL-3.0):
 * https://github.com/ReSo7200/InstaEclipse
 */
package app.hushgram.extension.instagram.misc;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.Setting;

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

    private static final String KEY_SPOOF_DEVICE = "hushgram_spoof_device";
    private static final String KEY_SPOOF_DEVICE_PROFILE = "hushgram_spoof_device_profile";
    private static final String KEY_PAUSED = "hushgram_paused";
    private static final String KEY_SAFE_MODE = "hushgram_safe_mode";

    private static volatile boolean earlyActive;
    @Nullable private static volatile Profile earlyProfile;

    @Nullable private static volatile Method sysPropGet1 = findSystemPropertiesMethod(String.class);
    @Nullable private static volatile Method sysPropGet2 = findSystemPropertiesMethod(String.class, String.class);

    private DeviceSpoof() {
    }

    /**
     * Runs as early as {@code Application.attachBaseContext(Context)} — before ContentProviders,
     * native libraries and MobileConfig bootstrap — reading the stored spoof preference directly
     * without triggering {@link Settings} static initialization before {@link Utils#setContext}.
     */
    public static void onAttachBaseContext(@Nullable Context context) {
        if (context == null) return;
        try {
            HookStatus.invoked(FamilyNames.SPOOF_DEVICE);
            Profile early = readEarlyProfile(context);
            earlyProfile = early;
            earlyActive = true;
            if (isMainProcess(context)) {
                syncOverrides(context, early != null);
            }
            if (early != null) {
                applyBuildFields(early);
                HookStatus.counted(FamilyNames.SPOOF_DEVICE, SPOOFED);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPOOF_DEVICE, STEP, failure);
        }
    }

    /**
     * Runs at application startup right after {@link Utils#setContext(Context)}: clears any early
     * attachBaseContext cache, reconciles HDR MobileConfig overrides with final settings/pause
     * state, and sets {@link Build} fields when spoofing is active.
     */
    public static void onAppStart(@Nullable Context context) {
        onAppStart(context, DeviceSpoof::activeProfile);
    }

    static void onAppStart(@Nullable Context context, Supplier<Profile> profileSupplier) {
        try {
            HookStatus.invoked(FamilyNames.SPOOF_DEVICE);
            earlyActive = false;
            earlyProfile = null;
            Profile active = profileSupplier.get();
            if (context != null && isMainProcess(context)) {
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

    static void clearEarlyStateForTests() {
        earlyActive = false;
        earlyProfile = null;
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

    public static String buildHardware(String real) {
        return readBuildField(real, p -> p.device);
    }

    public static String buildBoard(String real) {
        return readBuildField(real, p -> p.device);
    }

    public static String buildFingerprint(String real) {
        return readBuildField(real, p -> p.fingerprint);
    }

    public static String buildId(String real) {
        return readBuildField(real, p -> p.buildId);
    }

    public static String buildDisplay(String real) {
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
            case "ro.build.display.id":
                return profile.buildId;
            case "ro.hardware":
            case "ro.product.board":
                return profile.device;
            case "ro.build.product":
                return profile.product;
            default:
                return null;
        }
    }

    // ---- PackageManager.hasSystemFeature stand-ins ------------------------------------------

    public static boolean hasSystemFeature(@Nullable PackageManager pm, String name) {
        return readFeature(name, DeviceSpoof::activeProfile) || (pm != null && pm.hasSystemFeature(name));
    }

    public static boolean hasSystemFeature(@Nullable PackageManager pm, String name, int version) {
        return readFeature(name, DeviceSpoof::activeProfile) || (pm != null && pm.hasSystemFeature(name, version));
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
        if (!Utils.settingsReady()) return earlyActive ? earlyProfile : null;
        if (!Settings.SPOOF_DEVICE.get()) return null;
        return Settings.SPOOF_DEVICE_PROFILE.get();
    }

    @Nullable
    static Profile readEarlyProfile(Context context) {
        try {
            File filesDir = context.getFilesDir();
            if (filesDir != null && new File(filesDir, HushgramPause.MARKER_FILE_NAME).exists()) {
                return null;
            }
            SharedPreferences prefs = context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (prefs == null) return null;
            if (safeBoolean(prefs, KEY_SAFE_MODE) || safeBoolean(prefs, KEY_PAUSED)) return null;
            if (!safeBoolean(prefs, KEY_SPOOF_DEVICE)) return null;
            String savedProfile = safeString(prefs, KEY_SPOOF_DEVICE_PROFILE);
            if (savedProfile != null) {
                for (Profile candidate : Profile.values()) {
                    if (candidate.name().equalsIgnoreCase(savedProfile)) return candidate;
                }
            }
            return Profile.PIXEL;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean safeBoolean(SharedPreferences prefs, String key) {
        try {
            return prefs.getBoolean(key, false);
        } catch (ClassCastException ignored) {
            return false;
        }
    }

    @Nullable
    private static String safeString(SharedPreferences prefs, String key) {
        try {
            return prefs.getString(key, null);
        } catch (ClassCastException ignored) {
            return null;
        }
    }

    private static boolean isMainProcess(@Nullable Context context) {
        if (context == null) return Utils.isMainProcess();
        String packageName = context.getPackageName();
        ApplicationInfo info = context.getApplicationInfo();
        String declared = info != null ? info.processName : null;
        if (declared != null && declared.startsWith(packageName + ":")) return false;
        String actual = processName();
        if (actual == null || actual.isEmpty() || packageName.equals(actual)) return true;
        return !actual.startsWith(packageName + ":");
    }

    @Nullable
    private static String processName() {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                String name = Application.getProcessName();
                if (name != null && !name.isEmpty()) return name;
            } catch (Throwable ignored) {
            }
        }
        StringBuilder name = new StringBuilder();
        try (FileInputStream input = new FileInputStream("/proc/" + Process.myPid() + "/cmdline")) {
            int value;
            while ((value = input.read()) > 0) name.append((char) value);
            return name.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void applyBuildFields(Profile profile) {
        setStaticBuildField("MANUFACTURER", profile.manufacturer);
        setStaticBuildField("BRAND", profile.brand);
        setStaticBuildField("MODEL", profile.model);
        setStaticBuildField("DEVICE", profile.device);
        setStaticBuildField("PRODUCT", profile.product);
        setStaticBuildField("HARDWARE", profile.device);
        setStaticBuildField("BOARD", profile.device);
        setStaticBuildField("FINGERPRINT", profile.fingerprint);
        setStaticBuildField("ID", profile.buildId);
        setStaticBuildField("DISPLAY", profile.buildId);
        setStaticBuildField("SOC_MANUFACTURER", profile.socManufacturer);
        setStaticBuildField("SOC_MODEL", profile.socModel);
    }

    private static void setStaticBuildField(String name, @Nullable String value) {
        if (value == null) return;
        try {
            Field field = Build.class.getField(name);
            if (setViaUnsafe(field, value)) return;
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

    private static boolean setViaUnsafe(Field field, String value) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Object unsafe = null;
            for (String holder : new String[] { "theUnsafe", "THE_ONE" }) {
                try {
                    Field f = unsafeClass.getDeclaredField(holder);
                    f.setAccessible(true);
                    unsafe = f.get(null);
                    if (unsafe != null) break;
                } catch (Throwable ignored) {
                }
            }
            if (unsafe == null) return false;
            Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
            Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            Object base = staticFieldBase.invoke(unsafe, field);
            Object offset = staticFieldOffset.invoke(unsafe, field);
            if (!(offset instanceof Long)) return false;
            putObject.invoke(unsafe, base, (Long) offset, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    private static Method findSystemPropertiesMethod(Class<?>... parameterTypes) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            try {
                Method m = sp.getMethod("get", parameterTypes);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                Method m = sp.getDeclaredMethod("get", parameterTypes);
                m.setAccessible(true);
                return m;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String realSystemProperty(String key) {
        Method m = sysPropGet1;
        if (m == null) {
            m = findSystemPropertiesMethod(String.class);
            if (m != null) sysPropGet1 = m;
        }
        if (m == null) return "";
        try {
            Object value = m.invoke(null, key);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String realSystemProperty(String key, String def) {
        Method m = sysPropGet2;
        if (m == null) {
            m = findSystemPropertiesMethod(String.class, String.class);
            if (m != null) sysPropGet2 = m;
        }
        if (m == null) return def;
        try {
            Object value = m.invoke(null, key, def);
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
        try {
            if (!mcDir.exists() && !mcDir.mkdirs()) return;
            JSONObject root = readJsonObject(mcFile);
            JSONObject hdr = new JSONObject(HDR_OVERRIDES_JSON);
            boolean changed = !mcFile.exists();
            Iterator<String> keys = hdr.keys();
            while (keys.hasNext()) {
                String configPrefix = keys.next();
                String targetKey = findMatchingConfigKey(root, configPrefix);
                JSONArray desired = hdr.getJSONArray(configPrefix);
                JSONArray existing = root.optJSONArray(targetKey);
                if (mergeConfigArray(root, targetKey, existing, desired)) {
                    changed = true;
                }
            }
            if (changed) {
                writeAtomic(mcFile, root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!marker.exists()) {
                writeAtomic(marker, "1".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean mergeConfigArray(
            JSONObject root,
            String targetKey,
            @Nullable JSONArray existing,
            JSONArray desired
    ) throws Exception {
        Set<String> replacedIndexes = new HashSet<>();
        Set<String> desiredSet = new HashSet<>();
        for (int i = 0; i < desired.length(); i++) {
            String item = desired.getString(i);
            replacedIndexes.add(entryIndex(item));
            desiredSet.add(item);
        }
        JSONArray merged = new JSONArray();
        boolean changed = existing == null;
        if (existing != null) {
            for (int i = 0; i < existing.length(); i++) {
                String item = existing.optString(i, null);
                if (item == null) {
                    changed = true;
                    continue;
                }
                if (replacedIndexes.contains(entryIndex(item))) {
                    if (!desiredSet.contains(item)) {
                        changed = true;
                    }
                    continue;
                }
                merged.put(item);
            }
        }
        Set<String> existingSet = existing != null ? toSet(existing) : Collections.emptySet();
        for (int i = 0; i < desired.length(); i++) {
            String item = desired.getString(i);
            if (!existingSet.contains(item)) {
                changed = true;
            }
            merged.put(item);
        }
        if (changed) {
            root.put(targetKey, merged);
        }
        return changed;
    }

    private static String findMatchingConfigKey(JSONObject root, String configPrefix) {
        if (root.has(configPrefix)) return configPrefix;
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String candidate = keys.next();
            if (candidate.startsWith(configPrefix)) return candidate;
        }
        return configPrefix;
    }

    private static String entryIndex(String entry) {
        int colon = entry.indexOf(':');
        return colon < 0 ? entry : entry.substring(0, colon);
    }

    private static void removeOverrides(File mcFile, File marker) {
        if (!marker.exists()) return;
        try {
            if (mcFile.exists()) {
                JSONObject root = readJsonObject(mcFile);
                Map<String, List<String>> hdr = parseHdrMap();
                for (Map.Entry<String, List<String>> entry : hdr.entrySet()) {
                    Set<String> remove = new HashSet<>(entry.getValue());
                    Iterator<String> existingKeys = root.keys();
                    Set<String> matchingKeys = new HashSet<>();
                    while (existingKeys.hasNext()) {
                        String candidate = existingKeys.next();
                        if (candidate.startsWith(entry.getKey())) matchingKeys.add(candidate);
                    }
                    for (String targetKey : matchingKeys) {
                        JSONArray existing = root.optJSONArray(targetKey);
                        if (existing == null) continue;
                        JSONArray kept = new JSONArray();
                        for (int i = 0; i < existing.length(); i++) {
                            String value = existing.optString(i, null);
                            if (value != null && !remove.contains(value)) kept.put(value);
                        }
                        if (kept.length() > 0) {
                            root.put(targetKey, kept);
                        } else {
                            root.remove(targetKey);
                        }
                    }
                }
                if (root.length() > 0) {
                    writeAtomic(mcFile, root.toString().getBytes(StandardCharsets.UTF_8));
                } else {
                    mcFile.delete();
                }
            }
            marker.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void writeAtomic(File target, byte[] bytes) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, target.getName() + ".tmp");
        Files.write(tmp.toPath(), bytes);
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable ignored) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
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
