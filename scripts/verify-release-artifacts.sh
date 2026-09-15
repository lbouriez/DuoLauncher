#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
Usage: DUO_RELEASE_STORE_FILE=... DUO_RELEASE_STORE_PASSWORD=... DUO_RELEASE_KEY_ALIAS=... \
  DUO_RELEASE_KEY_PASSWORD=... scripts/verify-release-artifacts.sh \
  --apk FILE --aab FILE --application-id ID --version-code CODE --version-name NAME --fingerprint-out FILE
EOF
}

apk= aab= application_id= version_code= version_name= fingerprint_out=
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk|--aab|--application-id|--version-code|--version-name|--fingerprint-out)
            [[ $# -ge 2 ]] || { usage; exit 2; }
            case "$1" in
                --apk) apk=$2 ;;
                --aab) aab=$2 ;;
                --application-id) application_id=$2 ;;
                --version-code) version_code=$2 ;;
                --version-name) version_name=$2 ;;
                --fingerprint-out) fingerprint_out=$2 ;;
            esac
            shift 2
            ;;
        *) usage; exit 2 ;;
    esac
done

for required in apk aab application_id version_code version_name fingerprint_out; do
    [[ -n ${!required} ]] || { echo "Missing --${required//_/-}." >&2; exit 2; }
done
for required in DUO_RELEASE_STORE_FILE DUO_RELEASE_STORE_PASSWORD DUO_RELEASE_KEY_ALIAS DUO_RELEASE_KEY_PASSWORD; do
    [[ -n ${!required:-} ]] || { echo "Missing required signing variable: $required" >&2; exit 1; }
done
[[ -f "$apk" ]] || { echo "APK was not found: $apk" >&2; exit 1; }
[[ -f "$aab" ]] || { echo "AAB was not found: $aab" >&2; exit 1; }
[[ -f "$DUO_RELEASE_STORE_FILE" ]] || { echo "Keystore was not found." >&2; exit 1; }

sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[[ -n "$sdk_root" ]] || { echo "ANDROID_HOME or ANDROID_SDK_ROOT is required to verify the APK." >&2; exit 1; }
build_tools_dir=$(find "$sdk_root/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort -V | tail -n 1)
[[ -n "$build_tools_dir" ]] || { echo "No Android build-tools installation was found." >&2; exit 1; }
build_tools="$sdk_root/build-tools/$build_tools_dir"
apksigner="$build_tools/apksigner"
aapt="$build_tools/aapt"
apkanalyzer="$sdk_root/cmdline-tools/latest/bin/apkanalyzer"
[[ -x "$apksigner" && -x "$aapt" && -x "$apkanalyzer" ]] || {
    echo "Android SDK tools apksigner, aapt, and apkanalyzer are required." >&2
    exit 1
}

normalize_fingerprint() { tr -d ':[:space:]' | tr '[:lower:]' '[:upper:]'; }
expected_fingerprint=$(keytool -exportcert -rfc -keystore "$DUO_RELEASE_STORE_FILE" \
    -storepass "$DUO_RELEASE_STORE_PASSWORD" -alias "$DUO_RELEASE_KEY_ALIAS" 2>/dev/null \
    | keytool -printcert 2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n 1 | normalize_fingerprint)
[[ -n "$expected_fingerprint" ]] || { echo "Could not read the configured signing certificate." >&2; exit 1; }

apk_certificate_output=$("$apksigner" verify --verbose --print-certs "$apk")
printf '%s\n' "$apk_certificate_output" | grep -q 'Verified using v[1-4] scheme' || {
    echo "APK signature verification did not report a verified signing scheme." >&2
    exit 1
}
# apksigner has varied its leading whitespace across build-tools releases.  Extract
# the digest field rather than relying on the line starting in column one.
apk_fingerprint=$(printf '%s\n' "$apk_certificate_output" \
    | awk '/Signer #1 certificate SHA-256 digest:/ { print $NF; exit }' | normalize_fingerprint)
[[ "$apk_fingerprint" == "$expected_fingerprint" ]] || {
    echo "APK signer does not match the configured signing certificate." >&2
    echo "Expected certificate SHA-256: $expected_fingerprint" >&2
    echo "APK certificate SHA-256: $apk_fingerprint" >&2
    exit 1
}

actual_application_id=$("$apkanalyzer" manifest application-id "$apk")
actual_version_code=$("$apkanalyzer" manifest version-code "$apk")
actual_version_name=$("$apkanalyzer" manifest version-name "$apk")
actual_debuggable=$("$apkanalyzer" manifest debuggable "$apk")
[[ "$actual_application_id" == "$application_id" ]] || { echo "APK application ID did not match." >&2; exit 1; }
[[ "$actual_version_code" == "$version_code" ]] || { echo "APK versionCode did not match." >&2; exit 1; }
[[ "$actual_version_name" == "$version_name" ]] || { echo "APK versionName did not match." >&2; exit 1; }
[[ "$actual_debuggable" == "false" ]] || { echo "Release APK is debuggable." >&2; exit 1; }
if "$aapt" dump badging "$apk" | grep -q "testOnly='true'"; then
    echo "Release APK is test-only." >&2
    exit 1
fi

# jarsigner verifies JAR/AAB signatures. A valid owner-created Android certificate is often
# self-signed, so do not use -strict here: that would turn a normal trust warning into a failure.
jarsigner -verify -certs "$aab" >/dev/null
aab_fingerprint=$(keytool -printcert -jarfile "$aab" 2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n 1 | normalize_fingerprint)
[[ "$aab_fingerprint" == "$expected_fingerprint" ]] || {
    echo "AAB signer does not match the configured signing certificate." >&2
    echo "Expected certificate SHA-256: $expected_fingerprint" >&2
    echo "AAB certificate SHA-256: $aab_fingerprint" >&2
    exit 1
}

umask 077
printf '%s\n' "$expected_fingerprint" > "$fingerprint_out"
echo "Verified APK and AAB signatures and APK package metadata."
