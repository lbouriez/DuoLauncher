#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: DUO_APPLICATION_ID=... DUO_VERSION_CODE=... [DUO_VERSION_NAME=...] $0 [--require-fork-id]" >&2
}

require_fork_id=false
if [[ ${1:-} == "--require-fork-id" ]]; then
    require_fork_id=true
    shift
fi
if [[ $# -ne 0 ]]; then
    usage
    exit 2
fi

application_id=${DUO_APPLICATION_ID:-}
version_code=${DUO_VERSION_CODE:-}
version_name=${DUO_VERSION_NAME:-}

if [[ -z "$application_id" ]]; then
    echo "DUO_APPLICATION_ID must be configured." >&2
    exit 1
fi
if [[ ! "$application_id" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]]; then
    echo "DUO_APPLICATION_ID is not a valid Android application ID." >&2
    exit 1
fi
if [[ "$require_fork_id" == true && "$application_id" == "com.jake.duolauncher" ]]; then
    echo "Signed fork releases must not use the upstream application ID com.jake.duolauncher." >&2
    exit 1
fi
if [[ ! "$version_code" =~ ^[0-9]+$ ]] || (( 10#$version_code < 1 || 10#$version_code > 2100000000 )); then
    echo "DUO_VERSION_CODE must be a positive integer from 1 through 2100000000." >&2
    exit 1
fi
if [[ -n "$version_name" && ! "$version_name" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$ ]]; then
    echo "DUO_VERSION_NAME may use only letters, numbers, dots, underscores, or hyphens and be at most 64 characters." >&2
    exit 1
fi

echo "Release configuration is valid."
