#!/usr/bin/env python3
"""Update patches-bundle.json for a published GitHub Release in Morphe Manager format."""

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUNDLE_PATH = ROOT / "patches-bundle.json"
CHANGELOG_PATH = ROOT / "CHANGELOG.md"


def extract_latest_changelog(repo: str, tag: str) -> str:
    """Return the latest non-empty changelog section formatted for Morphe Manager."""
    if not CHANGELOG_PATH.is_file():
        return f"## [{tag}](https://github.com/{repo}/releases/tag/{tag})"

    lines = CHANGELOG_PATH.read_text(encoding="utf-8").splitlines()
    sections = []
    current_title = None
    current_body = []

    for line in lines:
        if line.startswith("## "):
            if current_title is not None:
                sections.append((current_title, "\n".join(current_body).strip()))
            current_title = line[3:].strip()
            current_body = []
        elif current_title is not None:
            current_body.append(line)
    if current_title is not None:
        sections.append((current_title, "\n".join(current_body).strip()))

    header_url = f"https://github.com/{repo}/releases/tag/{tag}"
    for title, body in sections:
        if body:
            display = f"## [{tag} ({title})]({header_url})"
            return f"{display}\n\n{body}\n"

    return f"## [{tag}]({header_url})\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Update patches-bundle.json for Morphe Manager.")
    parser.add_argument("--repo", required=True, help="GitHub repository owner/name")
    parser.add_argument("--tag", required=True, help="Release tag name, e.g. v0.0.19-build62")
    parser.add_argument("--version", required=True, help="Patch bundle version, e.g. 0.0.19")
    parser.add_argument(
        "--created-at",
        default=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S"),
        help="UTC timestamp in yyyy-MM-ddTHH:mm:ss format without zone suffix",
    )
    args = parser.parse_args()

    asset_name = f"patches-{args.version}.mpp"
    manifest = {
        "created_at": args.created_at,
        "description": extract_latest_changelog(args.repo, args.tag),
        "download_url": f"https://github.com/{args.repo}/releases/download/{args.tag}/{asset_name}",
        "page_url": f"https://github.com/{args.repo}/releases/tag/{args.tag}",
        "signature_download_url": "",
        "version": args.version,
    }
    BUNDLE_PATH.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    print(f"Updated {BUNDLE_PATH.name} for {args.repo} {args.tag} ({args.created_at})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
