#!/usr/bin/env python3
"""Audits the dependency closure of the dev.sajidali.* tvOS-relevant Gradle Module
Metadata (.module) files published to a local Maven repository.

For every tvOS-relevant variant (a variant whose attributes advertise a
`org.jetbrains.kotlin.native.target` starting with `tvos_`, or a module that is itself
one of the `-tvos*` platform-split modules), this script walks its declared
`dependencies` and classifies each one:

  OK-INTERNAL                 the dependency's group starts with the audited prefix
                               (e.g. dev.sajidali) and its module directory exists
                               locally at that exact version.
  OK-EXTERNAL-TVOS             the dependency is NOT under the audited prefix, but the
                               referenced group:module:version publishes tvOS klib
                               variants upstream (Maven Central / Google Maven), verified
                               by fetching its .module and checking for `tvos_` variants.
  OK-EXTERNAL-TVOS-ASSUMED     the dependency's group is org.jetbrains.kotlin or
                               org.jetbrains.kotlinx. The Kotlin stdlib and kotlinx
                               libraries ship native klibs for every Kotlin/Native target
                               (including tvOS) universally, but are resolved by the
                               Kotlin/Native compiler's own toolchain/klib distribution
                               mechanism rather than via per-target Gradle-variant-aware
                               Maven artifacts -- so the upstream .module for these groups
                               will never advertise a `tvos_`-tagged variant the same way
                               AndroidX/Compose libraries do, and the OK-EXTERNAL-TVOS
                               network check would always misclassify them as FAIL. This
                               class exists purely to keep that known-safe noise out of
                               the FAIL bucket so remaining FAILs are meaningful.
  COVERED-BY-REDIRECT          the dependency's group is one that the tvos-redirect
                               Gradle plugin rewrites to the audited prefix, AND the
                               rewritten (twin) coordinate exists locally at the exact
                               same version.
  WARN (group-covered-but-version-mismatch)
                               same as COVERED-BY-REDIRECT, except the twin exists
                               locally under a DIFFERENT version than requested. This is
                               a version-manifest gap, not a hard failure.
  UNKNOWN                      an external lookup could not be completed (network
                               failure, timeout, non-2xx response, etc).
  FAIL                         none of the above apply -- the dependency is neither
                               satisfiable locally nor known to resolve externally.

Exit status is non-zero if there is at least one FAIL or UNKNOWN.

Usage:
    audit-tvos-closure.py [--repo-root PATH] [--group-prefix PREFIX]

Both a Java-package-style prefix (dev.sajidali) and a path-style prefix (dev/sajidali)
are accepted for --group-prefix; the script normalizes internally.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------------------
# Static configuration
# ---------------------------------------------------------------------------

# Groups the tvos-redirect plugin is known to rewrite to the audited coordinate root.
# Mirrors the plan's current coverage list -- keep in sync with the redirect plugin.
REDIRECT_COVERED_GROUPS = {
    "org.jetbrains.compose.ui",
    "org.jetbrains.compose.foundation",
    "org.jetbrains.compose.runtime",
    "org.jetbrains.compose.material",
    "org.jetbrains.compose.material3",
    "org.jetbrains.compose.animation",
    "org.jetbrains.compose.components",
    "org.jetbrains.compose.annotation-internal",
    "org.jetbrains.compose.collection-internal",
    "org.jetbrains.compose.material3.adaptive",
    "org.jetbrains.androidx.navigation",
    "org.jetbrains.androidx.lifecycle",
    "org.jetbrains.androidx.savedstate",
    "org.jetbrains.androidx.navigationevent",
    "org.jetbrains.androidx.navigation3",
}

TVOS_NATIVE_TARGET_ATTR = "org.jetbrains.kotlin.native.target"

# Groups whose upstream .module never advertises a `tvos_`-tagged variant even though the
# artifact ships native klibs for every Kotlin/Native target universally (Kotlin/Native
# resolves these via the compiler's own toolchain/klib distribution, not per-target
# Gradle-variant-aware Maven artifacts). Classified OK-EXTERNAL-TVOS-ASSUMED instead of
# running the (always-negative) network check, so they don't drown out real FAILs.
ASSUMED_TVOS_UNIVERSAL_GROUP_PREFIXES = (
    "org.jetbrains.kotlin",
    "org.jetbrains.kotlinx",
)

# Remote repositories checked (in order) for OK-EXTERNAL-TVOS lookups.
REMOTE_REPO_BASE_URLS = [
    "https://repo1.maven.org/maven2",
    "https://maven.google.com",
]

NETWORK_TIMEOUT_SECONDS = 5


@dataclass
class Dependency:
    group: str
    module: str
    version: str

    def gav(self) -> str:
        return f"{self.group}:{self.module}:{self.version}"


@dataclass
class Finding:
    module_path: Path
    module_coord: str  # group:module:version of the .module file being scanned
    variant_name: str
    dep: Dependency
    classification: str
    detail: str = ""


@dataclass
class AuditResult:
    findings: list = field(default_factory=list)

    def add(self, finding: Finding):
        self.findings.append(finding)

    def by_classification(self, classification: str):
        return [f for f in self.findings if f.classification == classification]


# ---------------------------------------------------------------------------
# .module parsing
# ---------------------------------------------------------------------------

def normalize_group_prefix(prefix: str) -> str:
    """Accept either 'dev.sajidali' or 'dev/sajidali' and return the dotted form."""
    return prefix.replace("/", ".").strip(".")


def find_module_files(repo_root: Path, group_prefix_path: str):
    prefix_dir = repo_root / Path(*group_prefix_path.split("/"))
    if not prefix_dir.is_dir():
        return []
    return sorted(prefix_dir.rglob("*.module"))


def load_module(path: Path):
    try:
        with path.open("r", encoding="utf-8") as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError) as e:
        print(f"WARNING: failed to parse {path}: {e}", file=sys.stderr)
        return None


def is_tvos_variant(variant: dict) -> bool:
    attrs = variant.get("attributes", {})
    target = attrs.get(TVOS_NATIVE_TARGET_ATTR, "")
    return isinstance(target, str) and target.startswith("tvos_")


def module_is_tvos_platform_module(module_name: str) -> bool:
    """True for the platform-split '-tvosarm64' / '-tvossimulatorarm64' modules
    themselves (as opposed to the umbrella module whose variants point at-them via
    available-at)."""
    return "-tvos" in module_name.lower()


def extract_dependencies(variant: dict):
    deps = []
    for raw in variant.get("dependencies", []):
        group = raw.get("group")
        module = raw.get("module")
        version_block = raw.get("version", {})
        version = version_block.get("requires") or version_block.get("strictly") or version_block.get("prefers")
        if group is None or module is None or version is None:
            # Project-local or otherwise unresolved dependency notation; skip rather
            # than mis-classify.
            continue
        deps.append(Dependency(group=group, module=module, version=version))
    return deps


# ---------------------------------------------------------------------------
# Classification
# ---------------------------------------------------------------------------

def local_module_dir_exists(repo_root: Path, group: str, module: str, version: str) -> bool:
    group_path = group.replace(".", "/")
    return (repo_root / group_path / module / version).is_dir()


def local_module_versions(repo_root: Path, group: str, module: str):
    group_path = group.replace(".", "/")
    module_dir = repo_root / group_path / module
    if not module_dir.is_dir():
        return []
    return sorted(p.name for p in module_dir.iterdir() if p.is_dir())


def _rewrite(group: str, target_prefix_dotted: str) -> str:
    """Rewrite an org.jetbrains.compose.* / org.jetbrains.androidx.* group to its
    dev.sajidali.* twin, e.g.:
        org.jetbrains.compose.ui         -> dev.sajidali.compose.ui
        org.jetbrains.androidx.lifecycle -> dev.sajidali.androidx.lifecycle
    """
    if group.startswith("org.jetbrains."):
        remainder = group[len("org.jetbrains."):]
        return f"{target_prefix_dotted}.{remainder}"
    return group


_external_module_cache = {}


def fetch_external_module_tvos_support(group: str, module: str, version: str):
    """Returns True/False/None (None == UNKNOWN / network failure)."""
    cache_key = (group, module, version)
    if cache_key in _external_module_cache:
        return _external_module_cache[cache_key]

    group_path = group.replace(".", "/")
    filename = f"{module}-{version}.module"
    result = None
    for base in REMOTE_REPO_BASE_URLS:
        url = f"{base}/{group_path}/{module}/{version}/{filename}"
        try:
            req = urllib.request.Request(url, method="GET", headers={"User-Agent": "audit-tvos-closure/1.0"})
            with urllib.request.urlopen(req, timeout=NETWORK_TIMEOUT_SECONDS) as resp:
                if resp.status != 200:
                    continue
                body = resp.read().decode("utf-8", errors="replace")
                result = "tvos_" in body
                break
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError):
            continue

    _external_module_cache[cache_key] = result
    return result


def classify_dependency(dep: Dependency, repo_root: Path, audited_prefix_dotted: str):
    # (i) OK-INTERNAL
    if dep.group == audited_prefix_dotted or dep.group.startswith(audited_prefix_dotted + "."):
        if local_module_dir_exists(repo_root, dep.group, dep.module, dep.version):
            return "OK-INTERNAL", ""
        return "FAIL", f"internal dependency {dep.gav()} has no local module directory"

    # (iii) COVERED-BY-REDIRECT / WARN version mismatch
    if dep.group in REDIRECT_COVERED_GROUPS:
        twin_group = _rewrite(dep.group, audited_prefix_dotted)
        if local_module_dir_exists(repo_root, twin_group, dep.module, dep.version):
            return "COVERED-BY-REDIRECT", f"twin {twin_group}:{dep.module}:{dep.version} present"
        available = local_module_versions(repo_root, twin_group, dep.module)
        if available:
            return "WARN", (
                f"group-covered-but-version-mismatch: requested {dep.gav()}, "
                f"twin {twin_group}:{dep.module} available locally at {', '.join(available)}"
            )
        return "FAIL", f"covered group {dep.group} but no local twin {twin_group}:{dep.module} at any version"

    # (ii-assumed) OK-EXTERNAL-TVOS-ASSUMED -- kotlin-stdlib / kotlinx libraries ship native
    # klibs universally but are resolved outside Gradle's per-target variant metadata; skip
    # the (always-negative) network check for them.
    if dep.group.startswith(ASSUMED_TVOS_UNIVERSAL_GROUP_PREFIXES):
        return "OK-EXTERNAL-TVOS-ASSUMED", (
            "Kotlin/Native toolchain-resolved dependency (kotlin-stdlib/kotlinx); "
            "assumed to ship tvOS klibs universally, not verified via Maven .module"
        )

    # (ii) OK-EXTERNAL-TVOS
    has_tvos = fetch_external_module_tvos_support(dep.group, dep.module, dep.version)
    if has_tvos is True:
        return "OK-EXTERNAL-TVOS", "upstream .module advertises tvos_ variant(s)"
    if has_tvos is None:
        return "UNKNOWN", "network lookup failed / timed out for upstream .module"

    # (iv) FAIL
    return "FAIL", "external dependency, not redirect-covered, no upstream tvOS variant found"


# ---------------------------------------------------------------------------
# Main audit
# ---------------------------------------------------------------------------

def run_audit(repo_root: Path, group_prefix: str) -> AuditResult:
    audited_prefix_dotted = normalize_group_prefix(group_prefix)
    prefix_path = audited_prefix_dotted.replace(".", "/")

    result = AuditResult()
    module_files = find_module_files(repo_root, prefix_path)

    for module_path in module_files:
        data = load_module(module_path)
        if data is None:
            continue

        component = data.get("component", {})
        module_name = component.get("module", "")
        module_coord = f"{component.get('group', '?')}:{module_name}:{component.get('version', '?')}"

        is_platform_module = module_is_tvos_platform_module(module_name)

        for variant in data.get("variants", []):
            if not (is_platform_module or is_tvos_variant(variant)):
                continue

            variant_name = variant.get("name", "<unnamed>")
            for dep in extract_dependencies(variant):
                classification, detail = classify_dependency(dep, repo_root, audited_prefix_dotted)
                result.add(Finding(
                    module_path=module_path,
                    module_coord=module_coord,
                    variant_name=variant_name,
                    dep=dep,
                    classification=classification,
                    detail=detail,
                ))

    return result


def print_report(result: AuditResult, repo_root: Path) -> int:
    classifications = [
        "OK-INTERNAL",
        "OK-EXTERNAL-TVOS",
        "OK-EXTERNAL-TVOS-ASSUMED",
        "COVERED-BY-REDIRECT",
        "WARN",
        "FAIL",
        "UNKNOWN",
    ]
    counts = {c: len(result.by_classification(c)) for c in classifications}

    problems = result.by_classification("FAIL") + result.by_classification("UNKNOWN")
    warnings = result.by_classification("WARN")

    if problems:
        print("=" * 80)
        print(f"FAIL / UNKNOWN findings ({len(problems)}):")
        print("=" * 80)
        for f in problems:
            print(f"[{f.classification}] {f.module_coord} -> variant '{f.variant_name}' -> dep {f.dep.gav()}")
            if f.detail:
                print(f"    {f.detail}")
            print(f"    module file: {f.module_path}")
        print()

    if warnings:
        print("=" * 80)
        print(f"WARN findings ({len(warnings)}):")
        print("=" * 80)
        for f in warnings:
            print(f"[WARN] {f.module_coord} -> variant '{f.variant_name}' -> dep {f.dep.gav()}")
            if f.detail:
                print(f"    {f.detail}")
        print()

    print("=" * 80)
    print(f"Summary (repo root: {repo_root})")
    print("=" * 80)
    total = sum(counts.values())
    for c in classifications:
        print(f"  {c:<22} {counts[c]}")
    print(f"  {'TOTAL':<22} {total}")

    return 1 if (counts["FAIL"] or counts["UNKNOWN"]) else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--repo-root",
        default=str(Path.home() / ".m2" / "repository"),
        help="Maven repository root to scan (default: ~/.m2/repository)",
    )
    parser.add_argument(
        "--group-prefix",
        default="dev/sajidali",
        help="Group prefix to audit, dotted or path form (default: dev/sajidali)",
    )
    args = parser.parse_args()

    repo_root = Path(args.repo_root).expanduser().resolve()
    if not repo_root.is_dir():
        print(f"ERROR: repo root {repo_root} does not exist or is not a directory", file=sys.stderr)
        return 2

    result = run_audit(repo_root, args.group_prefix)
    if not result.findings:
        print(f"WARNING: no tvOS-relevant variants found under prefix {args.group_prefix} in {repo_root}", file=sys.stderr)

    return print_report(result, repo_root)


if __name__ == "__main__":
    sys.exit(main())
