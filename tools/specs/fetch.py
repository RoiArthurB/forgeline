#!/usr/bin/env python3
"""Downloads the API descriptions the forge clients are generated from, into the modules that use them.

Each forge publishes what its API is: Forgejo and Gitea a Swagger document, GitHub and SourceHut a GraphQL schema,
Tangled and the AT Protocol their lexicons. The clients are generated from copies kept in the repository, so a build
never depends on these servers; this refreshes the copies. Run it from the repository's root, or through the
"API specs" workflow, which commits what changed. Review the diff like any other change: a renamed field shows up
there, and then as a compile error, instead of as a bug.

    python3 tools/specs/fetch.py [tangled] [atproto] [forgejo] [sourcehut] [github]
"""

import json
import pathlib
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
USER_AGENT = "Forgeline specs (+https://github.com/RoiArthurB/forgeline)"

# tangled.org/core, the repository holding Tangled's lexicons, read through Tangled's own index.
TANGLED_INDEX = "https://api.tangled.org/xrpc"
TANGLED_CORE_DID = "did:plc:j5hmlfdrwkvtxm7cjmu7j2is"
TANGLED_REF = "master"

# The AT Protocol lexicons the Tangled client calls on a person's own server (their PDS) and on Bluesky's AppView.
ATPROTO_REF = "main"
ATPROTO_LEXICONS = [
    "com/atproto/identity/resolveHandle.json",
    "com/atproto/repo/createRecord.json",
    "com/atproto/repo/putRecord.json",
    "com/atproto/repo/deleteRecord.json",
    "com/atproto/repo/listRecords.json",
    "com/atproto/repo/defs.json",
    "com/atproto/repo/strongRef.json",
    "com/atproto/server/createSession.json",
    "com/atproto/server/refreshSession.json",
    "com/atproto/server/getSession.json",
    "com/atproto/label/defs.json",
]


def get(url: str, accept: str = "*/*") -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": accept})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def write(path: pathlib.Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    print(f"  {path.relative_to(ROOT)}")


def tangled_tree(path: str) -> list:
    query = urllib.parse.urlencode({"repo": TANGLED_CORE_DID, "ref": TANGLED_REF, "path": path})
    body = get(f"{TANGLED_INDEX}/org.tangled.temp.git.getTree?{query}", "application/json")
    entries = json.loads(body).get("entries") or json.loads(body).get("files") or []
    if not entries:
        raise SystemExit(f"No entries under {path}: {body[:600]!r}")
    return entries


def tangled_blob(path: str) -> bytes:
    query = urllib.parse.urlencode({"repo": TANGLED_CORE_DID, "ref": TANGLED_REF, "path": path})
    return get(f"{TANGLED_INDEX}/org.tangled.temp.git.getBlob?{query}")


def tangled() -> None:
    """Every lexicon under org/tangled, as Tangled's own code builds against them."""
    target = ROOT / "forge/tangled/lexicons"
    pending = ["lexicons/org/tangled"]
    while pending:
        folder = pending.pop()
        for entry in tangled_tree(folder):
            child = f"{folder}/{entry['name']}"
            # Git modes, in octal: 040000 is a folder (Tangled writes it 0040000).
            if int(str(entry.get("mode", "0")), 8) & 0o170000 == 0o040000:
                pending.append(child)
            elif child.endswith(".json"):
                data = tangled_blob(child)
                json.loads(data)  # A page of HTML instead of a lexicon stops here.
                write(target / child.removeprefix("lexicons/"), data)


def atproto() -> None:
    target = ROOT / "forge/tangled/lexicons"
    for path in ATPROTO_LEXICONS:
        url = f"https://raw.githubusercontent.com/bluesky-social/atproto/{ATPROTO_REF}/lexicons/{path}"
        write(target / path, get(url))


def forgejo() -> None:
    """Codeberg's and gitea.com's own Swagger documents: the API each serves today."""
    target = ROOT / "forge/forgejo/spec"
    for name, url in [("forgejo", "https://codeberg.org/swagger.v1.json"), ("gitea", "https://gitea.com/swagger.v1.json")]:
        data = get(url, "application/json")
        json.loads(data)
        write(target / f"{name}.swagger.json", data)


def sourcehut() -> None:
    url = "https://git.sr.ht/~sircmpwn/git.sr.ht/blob/master/api/graph/schema.graphqls"
    write(ROOT / "forge/sourcehut/src/main/graphql/schema.graphqls", get(url))


def github() -> None:
    """GitHub's public GraphQL schema, as GitHub publishes it for github.com."""
    url = "https://docs.github.com/public/fpt/schema.docs.graphql"
    write(ROOT / "forge/github/src/main/graphql/schema.graphqls", get(url))


SOURCES = {"tangled": tangled, "atproto": atproto, "forgejo": forgejo, "sourcehut": sourcehut, "github": github}

if __name__ == "__main__":
    for name in sys.argv[1:] or SOURCES:
        print(name)
        SOURCES[name]()
