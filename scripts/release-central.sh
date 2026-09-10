#!/usr/bin/env bash
# Manual-only Central release: explicit version ledger, validation, then optional upload.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS_DIR="$ROOT_DIR/../compose-tvos-redirect"
LEDGER=""
PLUGIN_VERSION=""
PUBLISH=0
DRY_RUN=0
usage() {
    echo 'Usage: release-central.sh --ledger FILE --plugin-version VERSION [--tools-dir DIR] [--dry-run | --publish]'
    echo 'The ledger supplies every exact library version. Default: stage and validate only.'
}
while [ $# -gt 0 ]; do
    case "$1" in
        --ledger) LEDGER="$2"; shift 2 ;;
        --plugin-version) PLUGIN_VERSION="$2"; shift 2 ;;
        --tools-dir) TOOLS_DIR="$2"; shift 2 ;;
        --publish) PUBLISH=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; exit 2 ;;
    esac
done
if [ "${CI:-false}" != "false" ] || [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo 'ERROR: Maven Central releases are manual only; run this script outside CI.' >&2
    exit 1
fi
if [ -z "$LEDGER" ] || [ -z "$PLUGIN_VERSION" ]; then usage >&2; exit 2; fi
if [ "$PUBLISH" = 1 ] && [ "$DRY_RUN" = 1 ]; then
    echo 'ERROR: choose --dry-run or --publish, not both.' >&2; exit 2
fi
LEDGER="$(cd "$(dirname "$LEDGER")" && pwd)/$(basename "$LEDGER")"
TOOLS_DIR="$(cd "$TOOLS_DIR" && pwd)"
cd "$ROOT_DIR"
if [ "$DRY_RUN" = 1 ]; then
    bash scripts/stage-central-bundle.sh --ledger "$LEDGER" --dry-run
    echo 'Then: strict bundle gate, consumer probe, and (only with --publish) Central upload.'
    exit 0
fi
: "${PUBLISH_SIGNING_KEY:?Set the ASCII-armored signing key}"
: "${PUBLISH_SIGNING_PASSWORD?Set the signing passphrase (empty is allowed)}"
if [ "$PUBLISH" = 1 ]; then : "${CENTRAL_TOKEN:?Set the Central Portal user token}"; fi
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo 'ERROR: commit tracked source changes before releasing.' >&2; exit 1
fi
mkdir -p build/release
python3 "$TOOLS_DIR/tools/release/gate_prebuild.py" --ledger "$LEDGER" --worktree "$ROOT_DIR"
python3 scripts/publish_set.py --ledger "$LEDGER" --format json > build/release/publish-plan.json
bash scripts/stage-central-bundle.sh --ledger "$LEDGER"
python3 "$TOOLS_DIR/tools/release/gate_bundle.py" --ledger "$LEDGER" \
    --bundle build/central-bundle.zip --strict-edges --report build/release/bundle-report.json
bash "$TOOLS_DIR/consumer-probe/run.sh" --ledger "$LEDGER" --plugin-version "$PLUGIN_VERSION" \
    --extra-repo "$ROOT_DIR/build/central-staging-repo" --out "$ROOT_DIR/build/release/consumer"
git rev-parse HEAD > build/release/source-commit.txt
if [ "$PUBLISH" = 1 ]; then
    python3 scripts/publish-central.py --bundle build/central-bundle.zip
else
    echo 'Validated bundle: build/central-bundle.zip. Nothing uploaded.'
    echo 'To publish this exact bundle manually: python3 scripts/publish-central.py --bundle build/central-bundle.zip'
fi
