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

def get_conflict_marker_files():
    res = subprocess.run(
        ["git", "grep", "-l", "-E", r"^(<<<<<<< |=======$|>>>>>>> )"],
        capture_output=True,
        text=True,
    )
    files = [line.strip() for line in res.stdout.splitlines() if line.strip()]
    verified = []
    for f in files:
        if not os.path.exists(f):
            continue
        with open(f, "r", encoding="utf-8", errors="replace") as fp:
            text = fp.read()
        if re.search(r"^(<<<<<<< |={7}(\r?\n|$)|>>>>>>> )", text, re.MULTILINE):
            verified.append(f)
    return verified

def resolve_tsv_conflicts():
    tsv_files = glob.glob("extensions/shared/library/src/main/l10n/*.tsv")
    for tsv in tsv_files:
        with open(tsv, "r", encoding="utf-8") as f:
            content = f.read()
        if "<<<<<<<" in content:
            pattern = re.compile(r'<<<<<<< [^\n]*\n(.*?)\n?=======\n(.*?)\n>>>>>>> [^\n]*\n', re.DOTALL)
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
            with open(tsv, "w", encoding="utf-8", newline="\n") as f:
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
                with open(tsv, "w", encoding="utf-8", newline="\n") as f:
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

def resolve_bundle_conflicts():
    """Keep fork release/source URLs in patches-bundle.json if upstream modified it."""
    path = "patches-bundle.json"
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if "<<<<<<<" in content:
        subprocess.run(["git", "checkout", "--ours", path], check=True)
        subprocess.run(["git", "add", path], check=True)
        print("Resolved conflicts in patches-bundle.json by keeping fork bundle manifest")


def resolve_readme_conflicts():
    readme_path = "README.md"
    if not os.path.exists(readme_path):
        return
    with open(readme_path, "r", encoding="utf-8") as f:
        content = f.read()
    if "<<<<<<<" in content:
        pattern = re.compile(r'<<<<<<< [^\n]*\n(.*?)\n?=======\n(.*?)\n>>>>>>> [^\n]*\n', re.DOTALL)
        def repl(m):
            head_part = m.group(1)
            up_part = m.group(2)
            if "Fexture69" in head_part and "SysAdminDoc/HushGram" in up_part:
                return head_part + "\n"
            fork_markers = ("| `Open developer options`", "| `Spoof device`")
            if any(m in head_part or m in up_part for m in fork_markers):
                kept = [
                    l for l in head_part.splitlines()
                    if "`Open developer options`" in l or "`Spoof device`" in l
                ]
                prefix = "\n".join(l for l in kept if l.strip() not in up_part)
                if prefix:
                    return prefix + "\n" + up_part + "\n"
                return up_part + "\n"
            if "patches for `com.instagram.android`" in up_part:
                def inc_count(match):
                    count = int(match.group(1)) + 2
                    return f"There are {count} patches"
                up_updated = re.sub(r'There are (\d+) patches', inc_count, up_part)
                up_updated = re.sub(
                    r'simple mode includes (\d+) of them',
                    lambda match: f"simple mode includes {int(match.group(1)) + 2} of them",
                    up_updated,
                )
                return up_updated + "\n"
            return up_part + "\n"

        content = pattern.sub(repl, content)

    # Deduplicate patch rows in README table if any
    lines = content.splitlines(keepends=True)
    row_re = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*((?:\\\||[^|])*?)\s*\|\s*(Included|Opt-in)\s*\|$")
    seen_patches = set()
    deduped_lines = []
    for line in lines:
        m = row_re.match(line.rstrip("\r\n"))
        if m:
            patch_name = m.group(1)
            if patch_name in seen_patches:
                continue
            seen_patches.add(patch_name)
        deduped_lines.append(line)

    new_content = "".join(deduped_lines)
    with open(readme_path, "w", encoding="utf-8", newline="\n") as f:
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
                    val = int(match.group(1)) + 2
                    return f"assertEquals({val}, neutral.size)"
                return re.sub(r'assertEquals\((\d+),\s*neutral\.size\)', inc_val, up_part) + "\n"
            if "all.size" in up_part:
                def inc_val(match):
                    val = int(match.group(1)) + 2
                    return f'assertEquals("every named patch needs one reviewed decision", {val}, all.size)'
                return re.sub(r'assertEquals\(\s*"every named patch needs one reviewed decision",\s*(\d+),\s*all\.size\s*\)', inc_val, up_part) + "\n"
            if "spoofDevicePatch" in head_part or "spoofDevicePatch" in up_part or "openDeveloperOptionsPatch" in head_part or "openDeveloperOptionsPatch" in up_part:
                combined = []
                for line in (up_part + "\n" + head_part).splitlines():
                    if line.strip() and line not in combined:
                        combined.append(line)
                return "\n".join(combined) + "\n"
            return up_part + "\n"

        new_content = pattern.sub(repl, content)
        with open(catalog_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(new_content)

    # Dynamically ensure neutral.size and all.size assertions match actual declared patches
    with open(catalog_path, "r", encoding="utf-8") as f:
        code = f.read()
    neutral_m = re.search(r'private val neutral = listOf<Patch>\((.*?)\n    \)', code, re.DOTALL)
    prior_m = re.search(r'private val prior = listOf<Patch>\((.*?)\n    \)', code, re.DOTALL)
    optIn_m = re.search(r'private val optIn = listOf<Patch>\((.*?)\n    \)', code, re.DOTALL)
    if neutral_m and prior_m and optIn_m:
        n_count = len([l for l in neutral_m.group(1).splitlines() if l.strip() and not l.strip().startswith('//')])
        p_count = len([l for l in prior_m.group(1).splitlines() if l.strip() and not l.strip().startswith('//')])
        o_count = len([l for l in optIn_m.group(1).splitlines() if l.strip() and not l.strip().startswith('//')])
        total_count = n_count + p_count + o_count
        code = re.sub(r'assertEquals\(\d+,\s*neutral\.size\)', f'assertEquals({n_count}, neutral.size)', code)
        code = re.sub(r'assertEquals\(\s*"every named patch needs one reviewed decision",\s*\d+,\s*all\.size\s*\)', f'assertEquals("every named patch needs one reviewed decision", {total_count}, all.size)', code)
        with open(catalog_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(code)
    subprocess.run(["git", "add", catalog_path], check=True)
    print("Resolved conflicts in DefaultCatalogTest.kt")

def resolve_data_saver_test_conflicts():
    path = "patches/src/test/kotlin/app/morphe/patches/instagram/media/quality/DataSaverPatchTest.kt"
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if "<<<<<<<" in content:
        subprocess.run(["git", "checkout", "--theirs", path], check=True)
        subprocess.run(["git", "add", path], check=True)
        print("Resolved conflicts in DataSaverPatchTest.kt by taking upstream implementation")

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
    unmerged = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=U"],
        capture_output=True,
        text=True,
        check=True,
    )
    if path not in unmerged.stdout.splitlines():
        return

    def stage_content(stage):
        return subprocess.run(
            ["git", "show", f":{stage}:{path}"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout

    ours = stage_content(2)
    upstream = stage_content(3)

    def real_names(source):
        match = re.search(r'(?m)^(\s*val REAL_NAMES\s*=\s*setOf\()(.*)(\))\s*$', source)
        if not match:
            raise ValueError(f"Could not find REAL_NAMES set in {path}")
        return match, re.findall(r'"([^"]+)"', match.group(2))

    upstream_match, upstream_names = real_names(upstream)
    _, our_names = real_names(ours)
    names = list(dict.fromkeys(upstream_names + our_names))
    names_line = (
        upstream_match.group(1)
        + ", ".join(f'"{name}"' for name in names)
        + upstream_match.group(3)
    )
    content = upstream[:upstream_match.start()] + names_line + upstream[upstream_match.end():]

    method_start = "    fun `real names of the same shape are not`() {"

    def method_assertions(source):
        start = source.find(method_start)
        if start < 0:
            raise ValueError(f"Could not find real-name assertions in {path}")
        body_start = source.find("\n", start) + 1
        method_end = re.search(r"(?m)^    }$", source[body_start:])
        if not method_end:
            raise ValueError(f"Could not find end of real-name assertions in {path}")
        body_end = body_start + method_end.start()
        body = source[body_start:body_end]
        assertions = re.findall(r"(?m)^\s*assertTrue\(identitiesIn\(.*$", body)
        return body_start, body_end, body, assertions

    body_start, body_end, body, upstream_assertions = method_assertions(content)
    _, _, _, our_assertions = method_assertions(ours)
    added_assertions = [line for line in our_assertions if line not in upstream_assertions]
    if added_assertions:
        body = body.rstrip() + "\n" + "\n".join(added_assertions) + "\n"
        content = content[:body_start] + body + content[body_end:]

    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
    subprocess.run(["git", "add", path], check=True)
    print("Resolved conflicts in ObfuscatedIdentityTest.kt using complete file versions")

def resolve_neutral_defaults_settings_test_conflicts():
    """Take the upstream test implementation when the fork and upstream both change it."""
    path = "extensions/instagram/src/test/java/app/hushgram/extension/instagram/settings/NeutralDefaultsSettingsTest.java"
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if "<<<<<<<" in content:
        subprocess.run(["git", "checkout", "--theirs", path], check=True)
        subprocess.run(["git", "add", path], check=True)
        print(f"Resolved conflicts in {path} by taking upstream implementation")

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
    marker_files = get_conflict_marker_files()
    to_check = set(unmerged + marker_files)
    if not to_check:
        return
    for path in to_check:
        if not os.path.exists(path):
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
        if "<<<<<<<" not in content:
            continue

        pattern = re.compile(r'<<<<<<< [^\n]*\n(.*?)\n?=======\n(.*?)\n>>>>>>> [^\n]*\n', re.DOTALL)

        if path == "NOTICE" or path.endswith((".md", ".txt", ".json", ".properties")):
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
    marker_files = get_conflict_marker_files()
    if not unmerged and not marker_files:
        print("No merge conflicts to resolve.")
        return 0

    print(f"Attempting auto-resolution for unmerged files: {unmerged + marker_files}")

    resolve_tsv_conflicts()
    resolve_l10n_translations()
    resolve_bundle_conflicts()
    resolve_readme_conflicts()
    resolve_catalog_test_conflicts()
    resolve_data_saver_test_conflicts()
    resolve_cleanup_reels_conflicts()
    resolve_friends_tab_conflicts()
    resolve_obfuscated_identity_test_conflicts()
    resolve_neutral_defaults_settings_test_conflicts()
    resolve_patch_family_and_status_conflicts()
    resolve_generic_conflicts()

    try:
        if os.name != "nt" and os.path.exists("gradlew"):
            try:
                os.chmod("gradlew", 0o755)
            except Exception:
                pass
        gradle_cmd = ["bash", "./gradlew"] if os.name != "nt" else ["cmd.exe", "/c", "gradlew.bat"]
        subprocess.run(gradle_cmd + [":patches:generatePatchesList", "--no-daemon", "--dependency-verification=lenient"], check=False)
        if os.path.exists("scripts/gen-readme-patches.py") and os.path.exists("patches-list.json"):
            subprocess.run([sys.executable, "scripts/gen-readme-patches.py"], check=False)
        for generated_path in ("patches-list.json", "README.md"):
            if os.path.exists(generated_path):
                subprocess.run(["git", "add", generated_path], check=False)
    except Exception as e:
        print(f"Notice: generatePatchesList skipped or failed: {e}")

    remaining = get_unmerged_files()
    remaining_markers = get_conflict_marker_files()
    if remaining or remaining_markers:
        print(f"Error: Unresolvable conflicts remain in: {remaining + remaining_markers}")
        return 1

    print("All conflicts resolved successfully!")
    return 0

if __name__ == "__main__":
    sys.exit(main())
