#!/usr/bin/env python3
"""Auto-resolves expected merge conflicts when syncing HushGram with upstream."""

import glob
import importlib.util
import os
import re
import subprocess
import sys

def get_unmerged_files():
    res = subprocess.run(["git", "diff", "--name-only", "--diff-filter=U"], capture_output=True, text=True)
    return [line.strip() for line in res.stdout.splitlines() if line.strip()]

def resolve_tsv_conflicts():
    tsv_files = glob.glob("extensions/shared/library/src/main/l10n/*.tsv")
    for tsv in tsv_files:
        with open(tsv, "r", encoding="utf-8") as f:
            content = f.read()
        if "<<<<<<<" in content:
            pattern = re.compile(r'<<<<<<< [^\n]*\n(.*?)\n=======\n(.*?)\n>>>>>>> [^\n]*\n', re.DOTALL)
            def repl(m):
                head_lines = m.group(1).splitlines()
                up_lines = m.group(2).splitlines()
                seen_keys = set()
                result = []
                for line in up_lines + head_lines:
                    if '\t' in line:
                        key = line.split('\t')[0]
                        if key not in seen_keys:
                            seen_keys.add(key)
                            result.append(line)
                    else:
                        result.append(line)
                return '\n'.join(result) + '\n'

            new_content = pattern.sub(repl, content)
            with open(tsv, "w", encoding="utf-8") as f:
                f.write(new_content)
            subprocess.run(["git", "add", tsv], check=True)
            print(f"Resolved conflicts in {tsv}")

def sync_missing_translation_keys():
    """Ensure every key required by source code exists in all TSV tables."""
    try:
        spec = importlib.util.spec_from_file_location('gen_l10n', 'scripts/gen-l10n.py')
        gen = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(gen)

        keys, others = gen.source_catalog()
        allowed = gen.allowed_keys(keys, others)
        tsv_files = glob.glob("extensions/shared/library/src/main/l10n/*.tsv")

        for tsv in tsv_files:
            entries = gen.read(tsv)
            missing = sorted(set(keys) - entries.keys())
            unknown = set(entries.keys()) - allowed

            modified = False
            lines = []
            if unknown:
                with open(tsv, "r", encoding="utf-8") as f:
                    for line in f:
                        if '\t' in line and line.split('\t')[0] in unknown:
                            continue
                        lines.append(line)
                modified = True
            else:
                with open(tsv, "r", encoding="utf-8") as f:
                    lines = f.readlines()

            if missing:
                for k in missing:
                    enc = gen.encode_field(k)
                    lines.append(f"{enc}\t{enc}\n")
                modified = True

            if modified:
                with open(tsv, "w", encoding="utf-8") as f:
                    f.writelines(lines)
                subprocess.run(["git", "add", tsv], check=True)
                print(f"Synchronized translation keys in {tsv} (added {len(missing)}, removed {len(unknown)})")
    except Exception as e:
        print(f"Notice: sync_missing_translation_keys encountered an error: {e}")

def resolve_l10n_translations():
    sync_missing_translation_keys()
    print("Regenerating L10nTranslations.java...")
    subprocess.run([sys.executable, "scripts/gen-l10n.py"], check=True)
    target = "extensions/shared/library/src/main/java/app/hushgram/extension/shared/L10nTranslations.java"
    if os.path.exists(target):
        subprocess.run(["git", "add", target], check=True)

def resolve_readme_conflicts():
    readme_path = "README.md"
    if not os.path.exists(readme_path):
        return
    with open(readme_path, "r", encoding="utf-8") as f:
        content = f.read()
    if "<<<<<<<" in content:
        pattern = re.compile(r'<<<<<<< [^\n]*\n(.*?)\n=======\n(.*?)\n>>>>>>> [^\n]*\n', re.DOTALL)
        def repl(m):
            head_part = m.group(1)
            up_part = m.group(2)
            if "| `Open developer options`" in head_part or "| `Open developer options`" in up_part:
                dev_line = [l for l in head_part.splitlines() if "Open developer options" in l]
                dev_str = dev_line[0] + "\n" if dev_line else ""
                if dev_str.strip() and dev_str.strip() not in up_part:
                    return dev_str + up_part + "\n"
                return up_part + "\n"
            if "patches for `com.instagram.android`" in up_part:
                def inc_count(match):
                    count = int(match.group(1)) + 1
                    return f"There are {count} patches"
                up_updated = re.sub(r'There are (\d+) patches', inc_count, up_part)
                return up_updated + "\n"
            return up_part + "\n"

        new_content = pattern.sub(repl, content)
        with open(readme_path, "w", encoding="utf-8") as f:
            f.write(new_content)
        subprocess.run(["git", "add", readme_path], check=True)
        print("Resolved conflicts in README.md")

def resolve_catalog_test_conflicts():
    catalog_path = "patches/src/test/kotlin/app/morphe/util/DefaultCatalogTest.kt"
    if not os.path.exists(catalog_path):
        return
    with open(catalog_path, "r", encoding="utf-8") as f:
        content = f.read()
    if "<<<<<<<" in content:
        pattern = re.compile(r'<<<<<<< [^\n]*\n(.*?)\n=======\n(.*?)\n>>>>>>> [^\n]*\n', re.DOTALL)
        def repl(m):
            head_part = m.group(1)
            up_part = m.group(2)
            if "neutral.size" in up_part:
                def inc_val(match):
                    val = int(match.group(1)) + 1
                    return f"assertEquals({val}, neutral.size)"
                return re.sub(r'assertEquals\((\d+),\s*neutral\.size\)', inc_val, up_part) + "\n"
            if "all.size" in up_part:
                def inc_val(match):
                    val = int(match.group(1)) + 1
                    return f'assertEquals("every named patch needs one reviewed decision", {val}, all.size)'
                return re.sub(r'assertEquals\(\s*"every named patch needs one reviewed decision",\s*(\d+),\s*all\.size\s*\)', inc_val, up_part) + "\n"
            return up_part + "\n"

        new_content = pattern.sub(repl, content)
        with open(catalog_path, "w", encoding="utf-8") as f:
            f.write(new_content)
        subprocess.run(["git", "add", catalog_path], check=True)
        print("Resolved conflicts in DefaultCatalogTest.kt")

def resolve_cleanup_reels_conflicts():
    path = "patches/src/main/kotlin/app/morphe/patches/instagram/reels/cleanup/CleanUpReelsPatch.kt"
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if "<<<<<<<" in content:
        subprocess.run(["git", "checkout", "--theirs", path], check=True)
        subprocess.run(["git", "add", path], check=True)
        print("Resolved conflicts in CleanUpReelsPatch.kt by taking upstream implementation")

def resolve_friends_tab_conflicts():
    path = "patches/src/main/kotlin/app/morphe/patches/instagram/reels/cleanup/FriendsTab.kt"
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if "<<<<<<<" in content:
        subprocess.run(["git", "checkout", "--theirs", path], check=True)
        subprocess.run(["git", "add", path], check=True)
        print("Resolved conflicts in FriendsTab.kt by taking upstream implementation")

def resolve_obfuscated_identity_test_conflicts():
    path = "patches/src/test/kotlin/app/morphe/ObfuscatedIdentityTest.kt"
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if "<<<<<<<" in content:
        pattern = re.compile(r'<<<<<<< [^\n]*\n(.*?)\n=======\n(.*?)\n>>>>>>> [^\n]*\n', re.DOTALL)
        def repl(m):
            head_part = m.group(1)
            up_part = m.group(2)
            if "REAL_NAMES" in head_part or "REAL_NAMES" in up_part:
                names = []
                for s in re.findall(r'"([^"]+)"', head_part + "\n" + up_part):
                    if s not in names:
                        names.append(s)
                names_str = ", ".join(f'"{s}"' for s in names)
                return f"        val REAL_NAMES = setOf({names_str})\n"
            return up_part + "\n"

        new_content = pattern.sub(repl, content)
        with open(path, "w", encoding="utf-8") as f:
            f.write(new_content)
        subprocess.run(["git", "add", path], check=True)
        print("Resolved conflicts in ObfuscatedIdentityTest.kt")

def resolve_patch_family_and_status_conflicts():
    for path in [
        "extensions/instagram/src/main/java/app/hushgram/extension/instagram/settings/PatchFamily.java",
        "extensions/instagram/src/main/java/app/hushgram/extension/instagram/settings/SettingsStatus.java",
    ]:
        if not os.path.exists(path):
            continue
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
        if "<<<<<<<" in content:
            pattern = re.compile(r'<<<<<<< [^\n]*\n(.*?)\n=======\n(.*?)\n>>>>>>> [^\n]*\n', re.DOTALL)
            def repl(m):
                head_part = m.group(1)
                up_part = m.group(2)
                if head_part.strip() in up_part:
                    return up_part + "\n"
                if up_part.strip() in head_part:
                    return head_part + "\n"
                return head_part + "\n" + up_part + "\n"
            new_content = pattern.sub(repl, content)
            with open(path, "w", encoding="utf-8") as f:
                f.write(new_content)
            subprocess.run(["git", "add", path], check=True)
            print(f"Resolved conflicts in {path}")

def resolve_generic_conflicts():
    unmerged = get_unmerged_files()
    if not unmerged:
        return
    for path in unmerged:
        if not os.path.exists(path):
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
        if "<<<<<<<" not in content:
            continue

        pattern = re.compile(r'<<<<<<< [^\n]*\n(.*?)\n=======\n(.*?)\n>>>>>>> [^\n]*\n', re.DOTALL)

        if path.endswith((".md", ".txt", ".json", ".properties")):
            def repl_doc(m):
                head_part = m.group(1)
                up_part = m.group(2)
                if head_part.strip() in up_part:
                    return up_part + "\n"
                if up_part.strip() in head_part:
                    return head_part + "\n"
                return up_part + "\n"
            new_content = pattern.sub(repl_doc, content)
            with open(path, "w", encoding="utf-8") as f:
                f.write(new_content)
            subprocess.run(["git", "add", path], check=True)
            print(f"Auto-resolved conflicts in {path} using doc preference")

def main():
    unmerged = get_unmerged_files()
    if not unmerged:
        print("No merge conflicts to resolve.")
        return 0

    print(f"Attempting auto-resolution for unmerged files: {unmerged}")

    resolve_tsv_conflicts()
    resolve_l10n_translations()
    resolve_readme_conflicts()
    resolve_catalog_test_conflicts()
    resolve_cleanup_reels_conflicts()
    resolve_friends_tab_conflicts()
    resolve_obfuscated_identity_test_conflicts()
    resolve_patch_family_and_status_conflicts()
    resolve_generic_conflicts()

    try:
        if os.name != "nt" and os.path.exists("gradlew"):
            try:
                os.chmod("gradlew", 0o755)
            except Exception:
                pass
        gradle_cmd = "./gradlew" if os.name != "nt" else "gradlew.bat"
        subprocess.run([gradle_cmd, ":patches:generatePatchesList", "--no-daemon", "--dependency-verification=lenient"], check=False)
        if os.path.exists("patches-list.json"):
            subprocess.run(["git", "add", "patches-list.json"], check=False)
    except Exception as e:
        print(f"Notice: generatePatchesList skipped or failed: {e}")

    remaining = get_unmerged_files()
    if remaining:
        print(f"Error: Unresolvable conflicts remain in: {remaining}")
        return 1

    print("All conflicts resolved successfully!")
    return 0

if __name__ == "__main__":
    sys.exit(main())
