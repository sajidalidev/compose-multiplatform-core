# Releasing the tvOS core fork

Both Reposilite and Maven Central are manual-only. There is no publishing workflow
or self-hosted runner. Ordinary PR checks continue on free GitHub-hosted runners.

## Reposilite: run locally

Use macOS with Xcode and JDK 21. Base library versions are in `scripts/tvos-versions.sh`.
Choose a new dev suffix for each upload; existing remote versions cannot be overwritten.

```bash
export JAVA_HOME=/path/to/jdk-21/Contents/Home
export ANDROIDX_JDK21="$JAVA_HOME"
export DEV_SUFFIX=-dev.20260910.1 # example: choose an unused suffix
bash scripts/publish-tvos-fork-reposilite.sh --local-only --dry-run
bash scripts/publish-tvos-fork-reposilite.sh --local-only
```

The first command only prints the plan. The second builds both tvOS targets and audits
the dependency closure, without credentials or uploads. For an actual upload:

```bash
set -a
source "$HOME/.config/tvos-reposilite.env"
set +a
bash scripts/publish-tvos-fork-reposilite.sh
```

The credentials file supplies `REPOSILITE_URL`, `REPOSILITE_USER`, and `REPOSILITE_TOKEN`.
The upload command checks version availability, builds, audits, uploads, and verifies
remote metadata. When `DEV_SUFFIX` is omitted, it selects an unused dated suffix.
Audit warnings remain visible and do not prove matching stable dependencies exist.
The script rejects CI execution. Your existing local credentials stay on your Mac.

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
