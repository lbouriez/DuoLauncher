#!/usr/bin/env bash
set -euo pipefail

# The last successful signed fork build used versionCode 31. Keep this value at or above the
# highest code ever distributed outside this automatic pipeline. Commit count then makes each
# subsequent main commit produce a higher code without relying on GitHub run numbers or clocks.
readonly VERSION_CODE_OFFSET=31
readonly MAX_VERSION_CODE=2100000000

usage() {
    echo "Usage: $0 [commit]" >&2
}

if [[ $# -gt 1 ]]; then
    usage
    exit 2
fi

commit=${1:-HEAD}
commit=$(git rev-parse --verify "${commit}^{commit}") || {
    echo "Cannot resolve release commit: $commit" >&2
    exit 1
}
commit_count=$(git rev-list --count "$commit")
if [[ ! "$commit_count" =~ ^[0-9]+$ ]] || (( 10#$commit_count < 1 )); then
    echo "Git did not return a positive commit count for $commit." >&2
    exit 1
fi

version_code=$((10#$commit_count + VERSION_CODE_OFFSET))
if (( version_code < 1 || version_code > MAX_VERSION_CODE )); then
    echo "Generated versionCode $version_code is outside Android's supported range." >&2
    exit 1
fi

echo "Generated Android versionCode $version_code ($commit_count commits + offset $VERSION_CODE_OFFSET)." >&2
if [[ -n ${GITHUB_OUTPUT:-} ]]; then
    printf 'version_code=%s\n' "$version_code" >> "$GITHUB_OUTPUT"
else
    printf '%s\n' "$version_code"
fi
