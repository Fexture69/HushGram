/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.mark;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;

import android.app.Activity;
import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.instagram.misc.DeveloperOptions;
import app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.Row;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;

/** Builds the Developer category of HushGram settings. */
final class DeveloperCategory {
    private DeveloperCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.DEVELOPER_OPTIONS)) {
            PreferenceCategory developer = category(screen, L10n.t("Developer"), SettingsIcons.CODE,
                    L10n.t("Instagram's own testing tools. Change them only if you know what they do."));
            developer.addPreference(toggle(context, Settings.OPEN_DEVELOPER_OPTIONS,
                    L10n.t("Developer options on a long press of Home"),
                    L10n.t("Opens Instagram's own developer options, where you can look at and change its hidden "
                            + "settings. A wrong change can break parts of Instagram until you reset it.")));

            Row openOverrides = new Row(context);
            openOverrides.setKey("hushgram_open_overrides");
            openOverrides.setPersistent(false);
            openOverrides.setTitle(L10n.t("Open MetaConfig overrides"));
            openOverrides.setSummary(L10n.t("Opens Instagram's own editor for its hidden settings (MetaConfig). A wrong override, "
                    + "which is a changed setting, can break parts of Instagram."));
            openOverrides.setOnPreferenceClickListener(row -> {
                Activity activity = page.getActivity();
                if (DeveloperOptions.openOverrides(activity)) {
                    SettingsEntry.onClosedByUser();
                    if (page.getParentFragment() instanceof SettingsDialog) {
                        ((SettingsDialog) page.getParentFragment()).dismissAllowingStateLoss();
                    }
                } else {
                    String unavailable = L10n.t("MetaConfig is unavailable on this screen. Open HushGram settings from Home while signed in.");
                    row.setSummary(unavailable);
                    Utils.showToastShort(unavailable);
                }
                return true;
            });
            developer.addPreference(mark(openOverrides, SettingsIcons.CODE));

            Row openWhitehat = new Row(context);
            openWhitehat.setKey("hushgram_open_whitehat");
            openWhitehat.setPersistent(false);
            openWhitehat.setTitle(L10n.t("Open Whitehat settings"));
            openWhitehat.setSummary(L10n.t("Opens Instagram's own Whitehat settings. Its switch lets Instagram trust the certificates "
                    + "installed on this phone for 24 hours, so you can check the app's traffic. Restart "
                    + "Instagram after you turn it on."));
            openWhitehat.setOnPreferenceClickListener(row -> {
                Activity activity = page.getActivity();
                if (DeveloperOptions.openWhitehat(activity)) {
                    SettingsEntry.onClosedByUser();
                    if (page.getParentFragment() instanceof SettingsDialog) {
                        ((SettingsDialog) page.getParentFragment()).dismissAllowingStateLoss();
                    }
                } else {
                    String unavailable = L10n.t("Whitehat settings are unavailable on this screen. Open HushGram settings from Home while signed in.");
                    row.setSummary(unavailable);
                    Utils.showToastShort(unavailable);
                }
                return true;
            });
            developer.addPreference(mark(openWhitehat, SettingsIcons.CODE));

            if (PatchFamily.flagNamesInBuild()) {
                developer.addPreference(toggle(context, Settings.USE_FLAG_NAMES,
                        L10n.t("Use HushGram's setting names"),
                        L10n.t("MetaConfig shows names from HushGram's own list in place of numbers. The list comes from "
                                + "Instagram 447, so a few newer settings still show as numbers. A list you import is used instead.")));

                Row importMapping = new Row(context);
                importMapping.setKey("hushgram_import_flag_names");
                importMapping.setPersistent(false);
                importMapping.setTitle(L10n.t("Import mapping file"));
                importMapping.setSummary(L10n.t("Pick an Instagram id_name_mapping.json or HushGram text list. "
                        + "Shows names in MetaConfig in place of numbers."));
                importMapping.setOnPreferenceClickListener(row -> {
                    page.pickFlagNames();
                    return true;
                });
                developer.addPreference(mark(importMapping, SettingsIcons.CODE));

                Row removeMapping = new Row(context);
                removeMapping.setKey("hushgram_remove_flag_names");
                removeMapping.setPersistent(false);
                removeMapping.setTitle(L10n.t("Remove imported mapping"));
                removeMapping.setSummary(L10n.t("Forget the imported names. MetaConfig goes back to HushGram's names, "
                        + "or to Instagram's own labels if that switch is off."));
                removeMapping.setOnPreferenceClickListener(row -> {
                    page.flagNames(null, HushgramPreferenceFragment.REMOVE_FLAG_NAMES);
                    return true;
                });
                developer.addPreference(mark(removeMapping, SettingsIcons.CODE));
            }

            if (PatchFamily.overrideExchangeInBuild()) {
                Row exportOverrides = new Row(context);
                exportOverrides.setKey("hushgram_export_overrides");
                exportOverrides.setPersistent(false);
                exportOverrides.setTitle(L10n.t("Export overrides"));
                exportOverrides.setSummary(L10n.t("Saves your overrides (changes to Instagram's hidden settings) for "
                        + "this exact Instagram version."));
                exportOverrides.setOnPreferenceClickListener(row -> {
                    page.pickOverrides(HushgramPreferenceFragment.EXPORT_OVERRIDES);
                    return true;
                });
                developer.addPreference(mark(exportOverrides, SettingsIcons.CODE));
            }

            if (PatchFamily.overrideImportInBuild()) {
                developer.addPreference(toggle(context, Settings.ALLOW_OVERRIDE_IMPORT,
                        L10n.t("Allow importing overrides"),
                        L10n.t("Shows the Import and Restore rows. An import changes Instagram's hidden settings for "
                                + "this signed-in account.")));

                Row importOverrides = new Row(context);
                importOverrides.setKey("hushgram_import_overrides");
                importOverrides.setPersistent(false);
                importOverrides.setTitle(L10n.t("Import overrides"));
                importOverrides.setSummary(L10n.t("Applies a HushGram export, or Instagram's own overrides file. "
                        + "Your current overrides are saved first so Restore can bring them back."));
                importOverrides.setOnPreferenceClickListener(row -> {
                    page.pickOverrides(HushgramPreferenceFragment.IMPORT_OVERRIDES);
                    return true;
                });
                developer.addPreference(mark(importOverrides, SettingsIcons.CODE));

                Row restoreOverrides = new Row(context);
                restoreOverrides.setKey("hushgram_restore_overrides");
                restoreOverrides.setPersistent(false);
                restoreOverrides.setTitle(L10n.t("Restore previous overrides"));
                restoreOverrides.setSummary(L10n.t("Puts back the overrides saved before your last import for this "
                        + "account and Instagram version."));
                restoreOverrides.setOnPreferenceClickListener(row -> {
                    page.exchangeOverrides(null, HushgramPreferenceFragment.RESTORE_OVERRIDES);
                    return true;
                });
                developer.addPreference(mark(restoreOverrides, SettingsIcons.CODE));

                Row discardOverrides = new Row(context);
                discardOverrides.setKey("hushgram_discard_overrides");
                discardOverrides.setPersistent(false);
                discardOverrides.setTitle(L10n.t("Discard saved overrides"));
                discardOverrides.setSummary(L10n.t("Forgets the saved overrides from an earlier import or reset, unblocking new imports."));
                discardOverrides.setOnPreferenceClickListener(row -> {
                    page.exchangeOverrides(null, HushgramPreferenceFragment.DISCARD_OVERRIDES);
                    return true;
                });
                developer.addPreference(mark(discardOverrides, SettingsIcons.CODE));

                Row resetOverrides = new Row(context);
                resetOverrides.setKey("hushgram_reset_overrides");
                resetOverrides.setPersistent(false);
                resetOverrides.setTitle(L10n.t("Reset all overrides"));
                resetOverrides.setSummary(L10n.t("Removes every override from this signed-in account, so Instagram "
                        + "goes back to its own settings. Your current overrides are saved first for Restore."));
                resetOverrides.setOnPreferenceClickListener(row -> {
                    page.exchangeOverrides(null, HushgramPreferenceFragment.RESET_OVERRIDES);
                    return true;
                });
                developer.addPreference(mark(resetOverrides, SettingsIcons.CODE));
            }
        }
    }
}
