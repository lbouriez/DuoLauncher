#!/usr/bin/env bash
set -euo pipefail

repository_root=$(cd "$(dirname "$0")/.." && pwd -P)

for variable_name in DUO_RELEASE_STORE_FILE DUO_RELEASE_STORE_PASSWORD DUO_RELEASE_KEY_ALIAS DUO_RELEASE_KEY_PASSWORD; do
    if [[ -z "${!variable_name:-}" ]]; then
        echo "Missing required release signing variable: $variable_name" >&2
        exit 1
    fi
done
if [[ ! -f "$DUO_RELEASE_STORE_FILE" ]]; then
    echo "DUO_RELEASE_STORE_FILE does not point to a file." >&2
    exit 1
fi
store_directory=$(cd "$(dirname "$DUO_RELEASE_STORE_FILE")" && pwd -P)
store_file="$store_directory/$(basename "$DUO_RELEASE_STORE_FILE")"
case "$store_file" in
    "$repository_root"/*)
        echo "The release keystore must be stored outside the repository." >&2
        exit 1
        ;;
esac

metadata=$("$repository_root/scripts/gradle.sh" --quiet :app:printReleaseMetadata)
application_id=$(sed -n 's/^applicationId=//p' <<< "$metadata")
version_code=$(sed -n 's/^versionCode=//p' <<< "$metadata")
version_name=$(sed -n 's/^versionName=//p' <<< "$metadata")
[[ -n "$application_id" && -n "$version_code" && -n "$version_name" ]] || {
    echo "Could not read effective release metadata from Gradle." >&2
    exit 1
}
export DUO_VERSION_CODE=${DUO_VERSION_CODE:-$version_code}
export DUO_VERSION_NAME=${DUO_VERSION_NAME:-$version_name}
"$repository_root/scripts/validate-release-config.sh" --require-fork-id

source_commit=${DUO_SOURCE_COMMIT:-$(git -C "$repository_root" rev-parse HEAD)}
if ! git -C "$repository_root" rev-parse --verify --quiet "$source_commit^{commit}" >/dev/null; then
    echo "DUO_SOURCE_COMMIT must identify a commit in this checkout." >&2
    exit 1
fi
build_label=${DUO_BUILD_LABEL:-local}
if [[ ! "$build_label" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$ ]]; then
    echo "DUO_BUILD_LABEL may use only letters, numbers, dots, underscores, or hyphens." >&2
    exit 1
fi
release_name="DuoLauncher-${DUO_VERSION_NAME}-${DUO_VERSION_CODE}-${build_label}"
output_dir=${1:-"$repository_root/dist/$release_name"}
if [[ "$output_dir" != /* ]]; then
    output_dir="$PWD/$output_dir"
fi
if [[ -e "$output_dir" ]]; then
    echo "Refusing to replace existing release output: $output_dir" >&2
    exit 1
fi

"$repository_root/scripts/gradle.sh" :app:assembleRelease :app:bundleRelease
apk_source="$repository_root/app/build/outputs/apk/release/app-release.apk"
aab_source="$repository_root/app/build/outputs/bundle/release/app-release.aab"
mapping_source="$repository_root/app/build/outputs/mapping/release/mapping.txt"
for output in "$apk_source" "$aab_source" "$mapping_source"; do
    [[ -f "$output" ]] || { echo "Expected release output was not produced: $output" >&2; exit 1; }
done

output_parent=$(dirname "$output_dir")
mkdir -p "$output_parent"
staging_dir=$(mktemp -d "$output_parent/.duo-release.XXXXXX")
cleanup() { rm -rf "$staging_dir"; }
trap cleanup EXIT

package_dir="$staging_dir/$release_name"
mkdir -p "$package_dir"
apk_name="$release_name.apk"
aab_name="$release_name.aab"
source_name="$release_name-source.tar.gz"
mapping_name="$release_name-mapping.txt"
cp -p "$apk_source" "$package_dir/$apk_name"
cp -p "$aab_source" "$package_dir/$aab_name"
cp -p "$mapping_source" "$package_dir/$mapping_name"
"$repository_root/scripts/export-public-source.sh" "$staging_dir/source"
(cd "$staging_dir" && COPYFILE_DISABLE=1 tar -czf "$package_dir/$source_name" source)

fingerprint_file="$staging_dir/fingerprint"
"$repository_root/scripts/verify-release-artifacts.sh" \
    --apk "$package_dir/$apk_name" --aab "$package_dir/$aab_name" \
    --application-id "$application_id" --version-code "$version_code" --version-name "$version_name" \
    --fingerprint-out "$fingerprint_file"
fingerprint=$(<"$fingerprint_file")
cat > "$package_dir/BUILD-METADATA.txt" <<EOF
source_commit=$source_commit
application_id=$application_id
version_name=$version_name
version_code=$version_code
signing_certificate_sha256=$fingerprint
EOF

if command -v sha256sum >/dev/null 2>&1; then
    (cd "$package_dir" && sha256sum "$apk_name" "$aab_name" "$source_name" "BUILD-METADATA.txt" > SHA256SUMS.txt)
elif command -v shasum >/dev/null 2>&1; then
    (cd "$package_dir" && shasum -a 256 "$apk_name" "$aab_name" "$source_name" "BUILD-METADATA.txt" > SHA256SUMS.txt)
else
    echo "Neither sha256sum nor shasum is available." >&2
    exit 1
fi

mv "$package_dir" "$output_dir"
trap - EXIT
rm -rf "$staging_dir"
echo "Signed release package created at $output_dir"
