# CI and signed Android releases

This fork builds with Java 17 and Android SDK 36. It has two deliberately different GitHub Actions workflows:

- **Android CI** runs for pull requests and branch pushes without release secrets. It validates the Gradle wrapper, exports and validates the allowlisted public source tree, builds a debug APK, runs unit tests and lint, and compiles the optimized unsigned release variant. Its `duo-launcher-debug-development-*` artifact is for development only.
- **Build signed release** is manual, accepts only this repository's default branch, and builds the exact selected commit. It checks the same exported source, then creates a signed APK and AAB using the stable signing key you configure in GitHub.

CI's debug APK is not a signed release APK. A GitHub runner creates a fresh debug key when no local debug key exists, so that key is not a stable identity for personal updates. Debug builds also use an `.debug` application-ID suffix and therefore do not replace the signed fork installation.

## Required one-time setup

### 1. Enable Actions and choose the fork identity

If GitHub shows an **Actions are disabled** banner in the fork, open the repository's **Actions** tab and enable workflows. In **Settings → Actions → General**, allow actions as appropriate for your fork; this repository pins the actions it uses to reviewed commit SHAs.

Choose a permanent Android application ID before your first distributed fork build, for example a reverse-domain identifier you control such as `dev.example.duolauncher`. Do **not** copy that example unless it is yours. It must contain at least two dot-separated segments, begin every segment with a letter, and use only letters, digits, and underscores in segments.

In **Settings → Secrets and variables → Actions → Variables**, create this repository variable:

| Variable | Value |
| --- | --- |
| `DUO_APPLICATION_ID` | Your chosen valid fork application ID, not `com.jake.duolauncher` |

The signed workflow refuses a missing ID and refuses the upstream ID. Keeping your fork ID stable makes it install alongside upstream and allows later same-signer updates. The Kotlin namespace remains unchanged; Gradle supplies the runtime application ID, and the manifest's task affinity and accessibility package filter follow it.

### 2. Create and protect one permanent keystore

Run this **privately on your own computer**, outside the repository. Pick a memorable alias, your own passwords, and a safe path. Do not paste any password or key into Codex, source files, issues, logs, or chats.

```sh
keytool -genkeypair -v -keystore /safe/private/duolauncher-release.p12 \
  -storetype PKCS12 -alias your-key-alias -keyalg RSA -keysize 4096 -validity 10000
```

On Windows, the repository also includes a helper that generates a cryptographically random password, creates the PKCS12 key, Base64-encodes it, and writes the exact GitHub variable/secret values to a private directory. Supply your permanent fork ID yourself; this command refuses the upstream ID and never writes into the repository:

```powershell
pwsh -File .\scripts\create-release-keystore.ps1 `
  -ApplicationId 'your.permanent.fork.id' `
  -OutputDirectory 'C:\safe\duolauncher-release'
```

The helper prints only private file paths by default. Add `-ShowSecrets` only when you are ready to copy values into GitHub locally. Back up the generated `.p12`, alias, and passwords; then securely remove the temporary `*.base64.txt` and `*-github-secrets.txt` files after entering GitHub's secrets.

PKCS12 is recommended because it is a modern portable format. With PKCS12, use the same key password as the keystore password when prompted; Java's tooling can otherwise ignore a distinct key password. A JKS keystore also works if you already have one. Android trusts the certificate embedded in the APK; it does not need a public CA certificate.

Back up the keystore file, alias, and passwords in secure, separate storage before distributing anything. Losing the key means you cannot ship a compatible update for this application ID. Changing either the application ID or signing identity after distribution creates a different Android app for update purposes.

### 3. Encode the private keystore and create the environment

Encode the keystore without adding it to Git:

```sh
# Linux
base64 -w 0 /safe/private/duolauncher-release.p12 > duolauncher-release.base64

# macOS
base64 -i /safe/private/duolauncher-release.p12 | tr -d '\n' > duolauncher-release.base64

# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\safe\private\duolauncher-release.p12')) | Set-Content -NoNewline -Encoding ascii duolauncher-release.base64
```

Open **Settings → Environments**, create `android-release`, and configure its deployment branch policy to permit only the repository's default branch. Environment configuration is a GitHub setting and cannot be committed in this repository. Optional hardening is to require a reviewer before jobs using this environment receive secrets.

Inside that environment, add these four secrets:

| Secret | Private value |
| --- | --- |
| `DUO_RELEASE_KEYSTORE_BASE64` | Complete contents of `duolauncher-release.base64` |
| `DUO_RELEASE_STORE_PASSWORD` | Keystore password |
| `DUO_RELEASE_KEY_ALIAS` | Alias selected with `keytool` |
| `DUO_RELEASE_KEY_PASSWORD` | Key password (the store password for the PKCS12 example) |

The workflow decodes the keystore only under `RUNNER_TEMP`, outside both the checkout and public-source export. It gives the temporary file restrictive permissions, uses it only in signing/verification steps, and deletes it even after failures. It never generates a production key or falls back to the debug key.

## First signed build

1. Push the pipeline change and wait for **Android CI**. Its debug artifact is useful for development but is not the release APK.
2. Open **Actions → Build signed release → Run workflow**. GitHub must show your fork's default branch; the workflow refuses any other ref.
3. The workflow automatically generates both Android version values from the exact commit: `versionCode` is the full Git-history count plus the checked-in fork offset, and `versionName` is CalVer in the form `YY.MM.versionCode` (for example, `26.09.71`). It never uses a clock at build time or a GitHub run number. Re-running the exact same commit deliberately produces the same version; distribute a newer main commit for an Android update.
4. **Create draft release** is selected by default. Keep it selected to create an unpublished GitHub draft after verification, with notes listing merged pull requests since the preceding fork release. Clear it only when you specifically want artifacts without a draft. Run the workflow.
5. In the successful run's **Artifacts** section, download the signed-build artifact. Install the `.apk` on the phone. Keep the `.aab` for a future Play Console submission. `BUILD-METADATA.txt` and `SHA256SUMS.txt` identify exactly what was built; the separate R8 mapping artifact is for diagnostics.

The workflow uses Android `apksigner` for the APK and confirms package ID, version code/name, release debuggability, test-only status, and configured certificate fingerprint. It uses Java `jarsigner`/`keytool` for the AAB because `apksigner` does not verify Android App Bundles. A normal warning about a valid self-signed owner certificate is not the same thing as an unsigned or invalid bundle.

To update the APK installation later, keep the same `DUO_APPLICATION_ID` and keystore/alias, and build from a newer main commit. The automatic version code will then be higher and Android preserves launcher data while replacing the old version. A lower code reports a version-downgrade failure; a different signing certificate reports a signature mismatch. Uninstalling first loses launcher data, so do not use it as an update workaround unless that loss is acceptable.

The automatic scheme assumes every distributed fork build uses this pipeline. If you ever distribute an APK/AAB with a manually chosen code higher than the generated sequence, raise `VERSION_CODE_OFFSET` in `scripts/generate-release-version.sh` before the next pipeline release. Never lower that offset.

## APK, AAB, signing, and Play

- An **APK** is directly installable on a phone.
- An **AAB** is uploaded to Google Play; Play creates device-specific APKs. It is not normally installed directly.
- **Release signing** proves who built the package and enables Android's same-signer updates. It does not mean Google has reviewed, approved, or published the app.
- A Play **upload key** may be different from the Play app-signing key. Decide whether the certificate used for direct GitHub APKs must also be the signing identity users receive through Play before the first Play submission. A GitHub-installed APK and a Play-installed app are not automatically update-compatible just because this pipeline produced both files.

Before first Play submission, settle the permanent application ID, signing-key backup plan, whether to use Play App Signing, and the relation between your direct-distribution key and Play's app-signing/upload keys. This pipeline does not upload to Google Play and does not publish a production release.

## Optional draft GitHub release

**Create draft release** is selected by default, so GitHub normally creates an unpublished draft after every check and package verification succeeds. The signing job retains read-only token access. A separate publishing job receives only `contents: write`, downloads the verified artifact, and creates a draft with the tag format:

```text
duolauncher-fork-v<versionName>-<versionCode>
```

It refuses to overwrite an existing tag or release. The draft is not published automatically. Its notes list the merged pull requests associated with commits since the prior fork release; the first such release records that there is no prior fork release for comparison. This fork-specific prefix avoids collisions with inherited upstream tags.

## Local signed build and public-source export

The same signing integration can be exercised locally. Keep the key outside the checkout and set the stable fork ID explicitly:

```sh
export DUO_APPLICATION_ID='your.permanent.fork.id'
export DUO_RELEASE_STORE_FILE=/absolute/path/outside/the/repository/duolauncher-release.p12
export DUO_RELEASE_STORE_PASSWORD='set privately in your shell'
export DUO_RELEASE_KEY_ALIAS='your-key-alias'
export DUO_RELEASE_KEY_PASSWORD='set privately in your shell'
./scripts/release-signed.sh
```

The helper builds APK and AAB, verifies them, makes an allowlisted source archive, records metadata, computes checksums, and preserves R8 mapping separately. It refuses keys inside the repository, partial signing configuration, invalid fork identity, invalid versions, and an existing output directory.

`PUBLIC-FILES` remains the source allowlist. The exported source has no `.git`, so workflows capture the exact commit before export and record it in build metadata:

```sh
./scripts/export-public-source.sh /tmp/DuoLauncher-public
./scripts/check-public-source.sh /tmp/DuoLauncher-public
```

The checker rejects symlinks, non-allowlisted files, private paths, key files, and common credential patterns. New public scripts and workflows are explicitly allowlisted; no keystore, local signing properties, or build output is included.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Missing environment secret/configuration | Confirm all four secrets are in `android-release`, the job is allowed on the default branch, and `DUO_APPLICATION_ID` is a repository variable. |
| Invalid password or alias | Re-check the privately stored keystore type, store password, alias, and key password. Recreate the Base64 file from the same keystore, not a new one. |
| Signer mismatch | The APK/AAB was not signed with the configured alias/certificate. Do not replace the permanent key after distribution. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Build again with a version code greater than the installed APK. |
| Existing app will not update | Both application ID and signing certificate must match the installed app. A debug build, upstream install, or Play installation may use a different identity. |
| Workflow is skipped | Launch it from the actual repository default branch. This is intentional; PRs, tags, and arbitrary branches never receive production signing. |

The workflow verifies package mechanics only. It does not claim production readiness, device compatibility, battery behavior, Play approval, or release quality beyond the checks it actually ran.
