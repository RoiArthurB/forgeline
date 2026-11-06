#!/usr/bin/env bash
# Prints a release's version.txt from its tag (vX.Y.Z): the version name, and the version code worked out from it as
# X × 10000 + Y × 100 + Z. The release workflow builds with both and attaches the file to the GitHub release, where
# F-Droid's update check reads it (docs/DISTRIBUTION.md).
#
# Every code must be higher than the one before. If the formula ever changes, its weights can only grow.
set -euo pipefail

tag="${1:?usage: version.sh vX.Y.Z}"
if [[ ! "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "::error::$tag isn't a vX.Y.Z tag: see docs/RELEASING.md" >&2
    exit 1
fi
major=$((10#${BASH_REMATCH[1]}))
minor=$((10#${BASH_REMATCH[2]}))
patch=$((10#${BASH_REMATCH[3]}))
if ((minor > 99 || patch > 99)); then
    echo "::error::$tag has a minor or patch number above 99, which the version code can't hold: see tools/release/version.sh" >&2
    exit 1
fi

echo "versionName=${tag#v}"
echo "versionCode=$((major * 10000 + minor * 100 + patch))"
