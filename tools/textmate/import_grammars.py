#!/usr/bin/env python3
"""Imports the TextMate grammars the file viewer colors code with.

Reads sources.json, fetches each source at its pinned commit, and writes into core/markdown's resources:

- textmate/grammars/<scopeName>.json: every grammar the sources' VS Code extensions contribute, minified;
- textmate/languages.json: each language with what tells a file of it (extensions, file names, name patterns, first
  line), its names in a Markdown fence (id and aliases), and its grammar; plus every grammar and the scopes it injects
  into;
- textmate/NOTICE.md: where each grammar comes from, and its license.

Nothing about a language is written by hand in the app: re-run this after changing sources.json.

    python3 tools/textmate/import_grammars.py
"""

import glob
import json
import os
import plistlib
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, "core", "markdown", "src", "main", "resources", "textmate")


def fetch(source, into):
    """A blobless, sparse checkout of the source at its commit: only the extensions' JSON and grammars."""
    base = source["extensions"].rstrip("/")
    prefix = "" if base == "." else base + "/"
    patterns = [f"/{prefix}package.json", f"/{prefix}**/*.json", f"/{prefix}**/*.tmLanguage"]
    git = lambda *args: subprocess.run(["git", "-C", into, *args], check=True, capture_output=True)
    os.makedirs(into)
    git("init", "-q")
    git("remote", "add", "origin", source["repository"])
    git("sparse-checkout", "set", "--no-cone", *patterns)
    git("fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", source["commit"])
    git("checkout", "-q", "FETCH_HEAD")
    return sorted(os.path.dirname(p) for p in glob.glob(os.path.join(into, base, "package.json")))


def read_grammar(path):
    if path.endswith((".tmLanguage", ".plist")):
        with open(path, "rb") as f:
            return plistlib.load(f)
    with open(path, encoding="utf-8") as f:
        return json.load(f)


RULE_MAPS = ("repository", "captures", "beginCaptures", "endCaptures", "whileCaptures")


def clean(rule):
    """Drops what isn't a rule where only rules belong: VS Code skips a stray comment or misplaced key there, tm4e fails
    on it (text.xml's captures hold a `name`, YAML's a `comment`)."""
    if isinstance(rule, list):
        return [clean(item) for item in rule]
    if not isinstance(rule, dict):
        return rule
    cleaned = {}
    for key, value in rule.items():
        if key in RULE_MAPS and isinstance(value, dict):
            value = {name: item for name, item in value.items() if isinstance(item, dict)}
        elif key == "patterns" and isinstance(value, list):
            value = [item for item in value if isinstance(item, dict)]
        cleaned[key] = clean(value)
    return cleaned


def merge_list(into, key, values):
    for value in values or []:
        if value not in into.setdefault(key, []):
            into[key].append(value)


def main():
    with open(os.path.join(HERE, "sources.json"), encoding="utf-8") as f:
        sources = json.load(f)["sources"]

    languages, grammars, notices = {}, {}, []
    work = tempfile.mkdtemp()
    try:
        for index, source in enumerate(sources):
            print(f"Fetching {source['name']} at {source['commit'][:12]}", file=sys.stderr)
            components = []
            for extension in fetch(source, os.path.join(work, str(index))):
                with open(os.path.join(extension, "package.json"), encoding="utf-8") as f:
                    contributes = json.load(f).get("contributes", {})
                for grammar in contributes.get("grammars", []):
                    scope = grammar["scopeName"]
                    # A grammar several languages share (YAML for Docker Compose, INI for properties) is kept once.
                    if scope not in grammars:
                        grammars[scope] = {
                            "scopeName": scope,
                            "file": f"grammars/{scope}.json",
                            "injectTo": grammar.get("injectTo", []),
                            "content": clean(read_grammar(os.path.normpath(os.path.join(extension, grammar["path"])))),
                        }
                    if grammar.get("language"):
                        languages.setdefault(grammar["language"], {"id": grammar["language"]}).setdefault("scopeName", scope)
                for language in contributes.get("languages", []):
                    entry = languages.setdefault(language["id"], {"id": language["id"]})
                    for key in ("aliases", "extensions", "filenames", "filenamePatterns"):
                        merge_list(entry, key, language.get(key))
                    if language.get("firstLine") and "firstLine" not in entry:
                        entry["firstLine"] = language["firstLine"]
                # Credit where an extension's grammars come from; its other components aren't shipped.
                manifest = os.path.join(extension, "cgmanifest.json")
                if contributes.get("grammars") and os.path.exists(manifest):
                    with open(manifest, encoding="utf-8") as f:
                        for registration in json.load(f).get("registrations", []):
                            git = registration.get("component", {}).get("git", {})
                            license = registration.get("license", "")
                            if isinstance(license, dict):
                                license = license.get("type", "")
                            if git.get("repositoryUrl"):
                                components.append((git.get("name", ""), git["repositoryUrl"], license))
            notices.append((source, sorted(set(components))))
    finally:
        shutil.rmtree(work, ignore_errors=True)

    # Only what this script writes is replaced: the themes beside it are the app's own.
    shutil.rmtree(os.path.join(OUT, "grammars"), ignore_errors=True)
    os.makedirs(os.path.join(OUT, "grammars"))
    for grammar in grammars.values():
        with open(os.path.join(OUT, grammar["file"]), "w", encoding="utf-8") as f:
            json.dump(grammar.pop("content"), f, separators=(",", ":"), ensure_ascii=False)

    # A language without a grammar (plain text, a log format VS Code only names) has nothing to color.
    known = sorted((l for l in languages.values() if "scopeName" in l), key=lambda l: l["id"])
    index = {
        "languages": known,
        "grammars": sorted(grammars.values(), key=lambda g: g["scopeName"]),
    }
    with open(os.path.join(OUT, "languages.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, indent=1, ensure_ascii=False)
        f.write("\n")

    with open(os.path.join(OUT, "NOTICE.md"), "w", encoding="utf-8") as f:
        f.write("# TextMate grammars\n\nThe file viewer colors code with these grammars, imported by `tools/textmate/import_grammars.py`.\n")
        for source, components in notices:
            f.write(f"\n## {source['name']}\n\n{source['repository']} at {source['commit']}, {source['license']}.\n")
            if components:
                f.write("\nIts grammars come from:\n\n")
                for name, url, license in components:
                    f.write(f"- {name or url}: {url}{f' ({license})' if license else ''}\n")

    print(f"{len(grammars)} grammars, {len(known)} languages", file=sys.stderr)


if __name__ == "__main__":
    main()
