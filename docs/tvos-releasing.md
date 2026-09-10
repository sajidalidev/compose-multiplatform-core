# Releasing the tvOS core fork

Reposilite is automated. Maven Central is always run manually with explicit versions.
This setup publishes **compose-multiplatform-core**. The Compose Gradle plugin and the
redirect plugin remain in their own repositories and keep their own release processes.

## Reposilite: your Mac runs the release job

PR checks use free standard GitHub-hosted runners (`ubuntu-24.04`, `windows-latest`,
`macos-26` and `macos-15`). macOS jobs use smaller heaps and one Gradle worker to fit
standard Apple Silicon runners. There are no paid `large` or `xlarge` runner labels.

The release workflow requires a repository self-hosted runner with labels
`self-hosted`, `macOS`, `ARM64`, `tvos-release`. It only runs on pushes to `tvos-main`
or manual dispatches from that branch; PR workflows do not use this runner.

This Mac's runner is installed separately from the development workspace at:
`~/Library/Developer/GitHubActions/tvos-release`. Its launchd service starts when the
user logs in. The Mac must be awake and connected; queued builds wait while it is offline.
The setup does not change sleep settings or the global Xcode selection.

The runner's `.env` supplies `TVOS_JDK21_HOME`, `ANDROID_HOME` and `ANDROID_SDK_ROOT`.
CI selects Xcode through `DEVELOPER_DIR`, caches Gradle dependencies in its tool cache,
and stages Maven artifacts in a fresh temporary directory for each run. Publishing credentials stay in `~/.config/tvos-reposilite.env` on the Mac
and are loaded only by the upload step. They are not copied into GitHub secrets.

Create the GitHub environment `reposilite`. Optional environment variables:

| Variable | Purpose |
| --- | --- |
| `TVOS_XCODE_PATH` | Defaults to `/Applications/Xcode.app` |
| `REPOSILITE_ENV_FILE` | Alternate path to the local credentials file |
| `REPOSILITE_AUTO_PUBLISH` | Set to `true` after the first successful rehearsal to enable uploads on pushes |

Start with **Actions → tvOS Reposilite → Run workflow**, choose `tvos-main`, and leave
`publish` false. Until `REPOSILITE_AUTO_PUBLISH=true`, pushes also build and audit without
uploading. Manual dispatch with `publish=true` explicitly requests an upload.

Each run appends `-dev.<UTC date>.<run ID>.<attempt>` to the versions in
`scripts/tvos-versions.sh`. A rerun gets a new version. Publishes are serialized and never
canceled midway through an upload. Downloadable logs record the source commit, exact
versions, audit output, and consumer mappings.

A failed build, audit, or remote version check blocks publication. The existing audit's
version-mismatch warnings remain visible; they are not fixes for missing matching releases.

Runner service controls (run from its installation directory):

```bash
./svc.sh status
./svc.sh stop
./svc.sh start
```

Local equivalents:

```bash
export JAVA_HOME=/path/to/jdk-21/Contents/Home
export ANDROIDX_JDK21="$JAVA_HOME"
bash scripts/publish-tvos-fork-reposilite.sh --local-only --dry-run
bash scripts/publish-tvos-fork-reposilite.sh --local-only
```

## Maven Central: manual, explicit versions

Use a release branch based on the intended upstream tag, not the moving `tvos-main`
development branch. Supply a committed release ledger from the sibling
`compose-tvos-redirect` checkout. That ledger gives every library its exact version;
there is no automatic date suffix, latest-version lookup, or CI trigger for Central.
Generate/verify and commit the ledger through that repository's `tools/release/ledger.py`
workflow. Do not hand-edit the ledger to evade checks or republish existing coordinates.

Requirements: macOS, Xcode with tvOS SDK, JDK 21, Python 3, GnuPG, and the sibling redirect
repository containing `tools/release` and `consumer-probe`. The consumer probe's plugin
version is explicit too. Both Central entry points refuse to run when CI is enabled.

Review the exact version and module plan first:

```bash
bash scripts/release-central.sh \
  --ledger ../compose-tvos-redirect/tools/release/release-ledger/1.12.0.json \
  --plugin-version <released-redirect-plugin-version> \
  --dry-run
```

`1.12.0` is an example, not a version to republish. An empty publish set means all eligible
coordinates already exist: choose a genuinely new release ledger, not a force override.
Use `--tools-dir /path/to/compose-tvos-redirect` if the tools checkout is elsewhere.

For the actual release, provide these environment variables from your local secret store:

| Variable | Meaning |
| --- | --- |
| `PUBLISH_SIGNING_KEY` | ASCII-armored PGP private key |
| `PUBLISH_SIGNING_PASSWORD` | Key passphrase; may be empty |
| `CENTRAL_TOKEN` | Base64 of the Central Portal generated user token's `username:password` |

With the same ledger and plugin-version arguments, omit `--dry-run` to build, sign,
validate and run the consumer probe without uploading. Add `--publish` to perform those
steps and upload in the same manual invocation. A previous staging directory is never
silently overwritten; archive it before preparing another release.

The manual flow runs the existing prebuild gate, stages only the selected coordinates,
creates checksums, applies the strict bundle gate, and compiles the consumer probe before
upload. The upload uses Sonatype's `AUTOMATIC` processing **after your manual invocation**
and reports success only at `PUBLISHED`. There is no second Portal button to press.

If you already staged and validated a bundle, upload that exact bundle manually:

```bash
python3 scripts/publish-central.py --bundle build/central-bundle.zip
```

The upload is never automatically retried. If a request times out, check the Portal before
starting another upload. Its deployment ID and latest status are saved in `build/release`.
Resume polling an existing deployment with:

```bash
python3 scripts/publish-central.py --deployment-id <deployment-id>
```

Source documentation: [Sonatype Publisher API](https://central.sonatype.org/publish/publish-portal-api/),
[GitHub runner selection](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job).

## Checks for release tooling changes

```bash
python3 -m unittest discover -s scripts/tests -v
bash -n scripts/release-central.sh scripts/stage-central-bundle.sh scripts/publish-tvos-fork-reposilite.sh
```

Remote credentials, GitHub environment configuration and live publication are not exercised
by these local tests. Run a build-only workflow first when enabling this setup on GitHub.
