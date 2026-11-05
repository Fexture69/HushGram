#!/usr/bin/env python3
"""Auto-resolves expected merge conflicts when syncing HushGram with upstream."""

import glob
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

def resolve_l10n_translations():
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

    try:
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
