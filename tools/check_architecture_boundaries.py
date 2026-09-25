#!/usr/bin/env python3
"""Regression gate for PersonalHub capsule, dependency, and persistence ownership boundaries."""

from __future__ import annotations

from collections import defaultdict
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

PROJECT_DEPENDENCY = re.compile(r'project\("(:[^"]+)"\)')
PACKAGE_LINE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*$", re.MULTILINE)
IMPORT_LINE = re.compile(r"^\s*import\s+([^\s]+)(?:\s+as\s+\w+)?\s*$", re.MULTILINE)
TOP_LEVEL_DECLARATION = re.compile(
    r"^(?:(?:public|internal|private|protected|data|sealed|open|abstract|enum|annotation|value|expect|actual|inline|tailrec|operator|infix|suspend|const|lateinit)\s+)*"
    r"(?:class|interface|object|typealias|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)

FQCN_STRING = re.compile(r'"((?:[a-z_][A-Za-z0-9_]*\.){2,}[A-Z][A-Za-z0-9_]*)"')


def project_dependencies(build_file: Path) -> set[str]:
    return set(PROJECT_DEPENDENCY.findall(build_file.read_text()))


def kotlin_package(path: Path) -> str | None:
    match = PACKAGE_LINE.search(path.read_text())
    return match.group(1) if match else None


def kotlin_imports(path: Path) -> list[str]:
    return IMPORT_LINE.findall(path.read_text())


def kotlin_top_level_declarations(path: Path) -> set[str]:
    return set(TOP_LEVEL_DECLARATION.findall(path.read_text()))


# Gradle dependency direction: contracts -> nothing implementation-specific;
# core -> contracts/core only; a feature -> contracts/core and external libs, never another feature.
for build_file in ROOT.rglob("build.gradle.kts"):
    relative = build_file.relative_to(ROOT)
    parts = relative.parts
    dependencies = project_dependencies(build_file)
    if parts[:1] == ("contracts",):
        for dependency in sorted(dependencies):
            if dependency.startswith(":core:") or dependency.startswith(":feature:") or dependency == ":app":
                errors.append(f"{relative} contract module depends on implementation {dependency}")
    elif parts[:1] == ("core",):
        for dependency in sorted(dependencies):
            if dependency.startswith(":feature:") or dependency == ":app":
                errors.append(f"{relative} core module depends on feature/application {dependency}")
    elif len(parts) >= 2 and parts[0] == "feature":
        owner = parts[1]
        for dependency in sorted(dependencies):
            if dependency.startswith(":feature:") and dependency != f":feature:{owner}":
                errors.append(f"{relative} depends on feature implementation {dependency}")
            if dependency == ":app":
                errors.append(f"{relative} depends on host application {dependency}")


# Keep feature-owned persistence contracts out of generic core implementation source.
core_database_source = ROOT / "core/database/src/main/java"
for forbidden in (
    "com/supercontacts",
    "com/wordpulse",
    "com/gernalix/luoghi",
    "com/gernalix/sostanze",
):
    if (core_database_source / forbidden).exists():
        errors.append(f"feature persistence returned to generic core: {forbidden}")

for forbidden_file in ("TimerEntities.kt", "PeoplePhoto.kt"):
    if (core_database_source / "com/gernalix/personalhub/core/database" / forbidden_file).exists():
        errors.append(f"feature database contract returned to generic core: {forbidden_file}")


# Build package and symbol ownership indices from actual Kotlin sources. Contract-owned
# Room entities intentionally retain some legacy package names for compatibility, so a
# package can span contracts + implementation. Exact imported symbols must therefore win
# over package fallback or the gate reports false contract->implementation crossings.
source_roots: dict[str, Path] = {
    "app": ROOT / "app/src/main",
    "contracts:database": ROOT / "contracts/database/src/main",
    "core:database": ROOT / "core/database/src/main",
    "core:hub-context": ROOT / "core/hub-context/src/main",
    "core:alerts": ROOT / "core/alerts/src/main",
    "core:location": ROOT / "core/location/src/main",
    "core:ui": ROOT / "core/ui/src/main",
}
for feature_dir in sorted((ROOT / "feature").glob("*")):
    if feature_dir.is_dir():
        source_roots[f"feature:{feature_dir.name}"] = feature_dir / "src/main"

package_owners: dict[str, set[str]] = defaultdict(set)
symbol_owners: dict[str, set[str]] = defaultdict(set)
source_files: dict[str, list[Path]] = defaultdict(list)
for owner, source_root in source_roots.items():
    if not source_root.exists():
        continue
    for kotlin in source_root.rglob("*.kt"):
        source_files[owner].append(kotlin)
        package = kotlin_package(kotlin)
        if package:
            package_owners[package].add(owner)
            for declaration in kotlin_top_level_declarations(kotlin):
                symbol_owners[f"{package}.{declaration}"].add(owner)


def resolve_import_owner(import_name: str) -> str | None:
    target = import_name.removesuffix(".*")

    # Prefer exact symbol ownership. This is essential for split legacy packages where
    # stable entity/DAO contracts live in :contracts:database while runtime helpers remain
    # in a feature module under the same Kotlin package.
    owners = symbol_owners.get(target)
    if owners and len(owners) == 1:
        return next(iter(owners))

    parts = target.split(".")
    # Imports may target members or symbols we do not parse; walk upward to a uniquely
    # owned package as a conservative fallback.
    for size in range(len(parts), 0, -1):
        package = ".".join(parts[:size])
        owners = package_owners.get(package)
        if owners and len(owners) == 1:
            return next(iter(owners))
    return None


def is_direct_feature_public_surface(import_name: str) -> bool:
    """Only the feature's direct .api or .hub package is host-visible; nested implementation packages are private."""
    target = import_name.removesuffix(".*")
    parts = target.split(".")
    for size in range(len(parts), 0, -1):
        package = ".".join(parts[:size])
        owners = package_owners.get(package)
        if owners and len(owners) == 1:
            owner = next(iter(owners))
            if owner.startswith("feature:"):
                return package.endswith(".api") or package.endswith(".hub")
    return False


# Source-level capsule rules:
# - feature implementations never import another feature implementation;
# - core never imports a feature implementation;
# - contracts never import core/feature/application implementations;
# - the app composition root may see a feature only through the feature's direct api/ or hub/ package.
for owner, files in source_files.items():
    for kotlin in files:
        relative = kotlin.relative_to(ROOT)
        for imported in kotlin_imports(kotlin):
            imported_owner = resolve_import_owner(imported)
            if imported_owner is None or imported_owner == owner:
                continue

            if owner.startswith("feature:") and imported_owner.startswith("feature:"):
                errors.append(f"{relative} crosses feature capsules via import {imported}")
            elif owner.startswith("core:") and imported_owner.startswith("feature:"):
                errors.append(f"{relative} core imports feature implementation {imported}")
            elif owner.startswith("contracts:") and (
                imported_owner == "app" or imported_owner.startswith("core:") or imported_owner.startswith("feature:")
            ):
                errors.append(f"{relative} contract imports implementation {imported}")
            elif owner == "app" and imported_owner.startswith("feature:"):
                if not is_direct_feature_public_surface(imported):
                    errors.append(
                        f"{relative} composition root bypasses feature public surface: {imported} "
                        "(allowed feature surfaces: direct api or hub package only)"
                    )


# Feature code must not hand-build PersonalHub routing URIs. Keeping the scheme/authority
# contract centralized prevents one module from silently drifting to a different link shape.
for owner, files in source_files.items():
    if not owner.startswith("feature:"):
        continue
    for kotlin in files:
        text = kotlin.read_text()
        if "personalhub://module/" in text or '.scheme("personalhub")' in text or ".scheme(HubDeepLinkContract.SCHEME)" in text:
            errors.append(
                f"{kotlin.relative_to(ROOT)} constructs PersonalHub routing URI manually; "
                "use HubDeepLinkContract.moduleUri/featureUri"
            )


# Shared History/Search is host-owned. Features may expose only the public deep-link
# contract; removed per-feature change-history browsers must not return.
legacy_history_ui = (
    "app/src/main/java/com/gernalix/personalhub/HubActivityRegisterScreen.kt",
    "app/src/main/java/com/gernalix/personalhub/capsules/settings/GitHistorySettings.kt",
    "feature/supercontacts/src/main/java/com/supercontacts/app/ui/contacts/ContactHistoryCapsule.kt",
    "feature/luoghi/src/main/java/com/gernalix/luoghi/ui/history/HistoryScreen.kt",
    "feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/timeline/ui/TimelineScreen.kt",
    "feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/timeline/ui/TimelineCapsuleUi.kt",
    "feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/TaskHistoryDialog.kt",
)
for relative in legacy_history_ui:
    if (ROOT / relative).exists():
        errors.append(f"legacy user-facing History/Timeline surface returned: {relative}")

wordpulse_screen = ROOT / "feature/wordpulse/src/main/java/com/wordpulse/app/ui/WordPulseScreen.kt"
if wordpulse_screen.is_file():
    text = wordpulse_screen.read_text()
    for forbidden in ('Timeline("Timeline")', "private fun TimelineTab(", "timeline-word-"):
        if forbidden in text:
            errors.append(f"WordPulse legacy Timeline surface returned: {forbidden}")

hub_settings = ROOT / "app/src/main/java/com/gernalix/personalhub/capsules/settings/HubSettings.kt"
if hub_settings.is_file() and '"git-history"' in hub_settings.read_text():
    errors.append("Settings exposes legacy Git History / Time Machine route")

temporal_search = ROOT / "app/src/main/java/com/gernalix/personalhub/HubTemporalSearchScreen.kt"
if temporal_search.is_file():
    text = temporal_search.read_text()
    for forbidden in ("GitHistory", "git-history:", "temporal_git_history"):
        if forbidden in text:
            errors.append(f"temporal domain search contains technical change-history UI: {forbidden}")

module_history_contracts = {
    "supercontacts": "people",
    "luoghi": "places",
    "multitimetracker": "timer",
    "sostanze": "substances",
    "wordpulse": "wordpulse",
    "soldi": "soldi",
}
for feature_name, module_id in module_history_contracts.items():
    expected = f'moduleHistoryUri("{module_id}")'
    files = source_files.get(f"feature:{feature_name}", [])
    if not any(expected in path.read_text() for path in files):
        errors.append(
            f"feature:{feature_name} lacks shared History/Search entry via HubDeepLinkContract.{expected}"
        )


# Unified History/Search is host-owned. Features may expose domain chronology (sessions,
# visits, intakes, transactions), but change-history entry points must route through the
# public HubDeepLinkContract instead of owning a parallel browser.
legacy_history_surfaces = (
    "app/src/main/java/com/gernalix/personalhub/HubActivityRegisterScreen.kt",
    "app/src/main/java/com/gernalix/personalhub/capsules/settings/GitHistorySettings.kt",
    "feature/supercontacts/src/main/java/com/supercontacts/app/ui/contacts/ContactHistoryCapsule.kt",
    "feature/luoghi/src/main/java/com/gernalix/luoghi/ui/history/HistoryScreen.kt",
    "feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/timeline/ui/TimelineScreen.kt",
    "feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/timeline/ui/TimelineCapsuleUi.kt",
    "feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/TaskHistoryDialog.kt",
)
for relative_name in legacy_history_surfaces:
    if (ROOT / relative_name).exists():
        errors.append(f"legacy parallel History/Timeline UI returned: {relative_name}")

settings_source = ROOT / "app/src/main/java/com/gernalix/personalhub/capsules/settings/HubSettings.kt"
if settings_source.is_file() and '"git-history"' in settings_source.read_text():
    errors.append("HubSettings reintroduced the legacy git-history route")

people_app = ROOT / "feature/supercontacts/src/main/java/com/supercontacts/app/ui/app/SuperContactsApp.kt"
if people_app.is_file():
    people_text = people_app.read_text()
    for legacy_symbol in ("ContactHistoryScreen", "GlobalHistoryScreen", "HistoryCalendarScreen"):
        if f"private fun {legacy_symbol}(" in people_text:
            errors.append(f"People reintroduced parallel history UI {legacy_symbol}")

wordpulse_screen = ROOT / "feature/wordpulse/src/main/java/com/wordpulse/app/ui/WordPulseScreen.kt"
if wordpulse_screen.is_file() and "WordPulseTab.Timeline" in wordpulse_screen.read_text():
    errors.append("WordPulse reintroduced the legacy Timeline tab")

module_history_routes = {
    "feature:supercontacts": 'HubDeepLinkContract.moduleHistoryUri("people")',
    "feature:multitimetracker": 'HubDeepLinkContract.moduleHistoryUri("timer")',
    "feature:luoghi": 'HubDeepLinkContract.moduleHistoryUri("places")',
    "feature:sostanze": 'HubDeepLinkContract.moduleHistoryUri("substances")',
    "feature:wordpulse": 'HubDeepLinkContract.moduleHistoryUri("wordpulse")',
    "feature:soldi": 'HubDeepLinkContract.moduleHistoryUri("soldi")',
}
for owner, route_literal in module_history_routes.items():
    if not any(route_literal in path.read_text() for path in source_files.get(owner, [])):
        errors.append(f"{owner} does not expose the shared module History/Search route")

# String-based component routing must not smuggle implementation class names around import checks.
# Public Android entrypoints are stable host aliases owned by feature manifests.
for kotlin in source_files.get("app", []):
    relative = kotlin.relative_to(ROOT)
    for referenced in FQCN_STRING.findall(kotlin.read_text()):
        referenced_owner = resolve_import_owner(referenced)
        if referenced_owner and referenced_owner.startswith("feature:"):
            errors.append(
                f"{relative} embeds private feature implementation class name: {referenced}"
            )


ANDROID_ATTR = "{http://schemas.android.com/apk/res/android}"
SHORTCUT_ALIAS_PREFIX = "com.gernalix.personalhub.shortcut."
PUBLIC_SHORTCUT_ALIASES = {
    "luoghi": f"{SHORTCUT_ALIAS_PREFIX}PlacesShortcutActivity",
    "multitimetracker": f"{SHORTCUT_ALIAS_PREFIX}TimerShortcutActivity",
    "soldi": f"{SHORTCUT_ALIAS_PREFIX}SoldiShortcutActivity",
    "sostanze": f"{SHORTCUT_ALIAS_PREFIX}SubstancesShortcutActivity",
    "supercontacts": f"{SHORTCUT_ALIAS_PREFIX}PeopleShortcutActivity",
    "wordpulse": f"{SHORTCUT_ALIAS_PREFIX}WordPulseShortcutActivity",
}


def manifest_class_references(manifest: Path) -> list[str]:
    if not manifest.is_file():
        return []
    root = ET.parse(manifest).getroot()
    references: list[str] = []
    for element in root.iter():
        for attribute in ("name", "targetActivity"):
            value = element.attrib.get(ANDROID_ATTR + attribute)
            if value and not value.startswith("."):
                references.append(value)
    return references


# The host manifest owns host/core components only. Each feature owns its Activities,
# providers, receivers, services and public shortcut alias in its own manifest.
app_manifest = ROOT / "app/src/main/AndroidManifest.xml"
for referenced in manifest_class_references(app_manifest):
    if referenced.startswith(SHORTCUT_ALIAS_PREFIX):
        errors.append(
            f"{app_manifest.relative_to(ROOT)} declares feature shortcut alias {referenced}; "
            "move it to the owning feature manifest"
        )
        continue
    referenced_owner = resolve_import_owner(referenced)
    if referenced_owner and referenced_owner.startswith("feature:"):
        errors.append(
            f"{app_manifest.relative_to(ROOT)} declares feature implementation component {referenced}; "
            "move it to the owning feature manifest"
        )

seen_shortcut_aliases: dict[str, list[str]] = defaultdict(list)
for feature_dir in sorted((ROOT / "feature").glob("*")):
    if not feature_dir.is_dir():
        continue
    owner = f"feature:{feature_dir.name}"
    manifest = feature_dir / "src/main/AndroidManifest.xml"
    expected_alias = PUBLIC_SHORTCUT_ALIASES.get(feature_dir.name)
    for referenced in manifest_class_references(manifest):
        if referenced.startswith(SHORTCUT_ALIAS_PREFIX):
            if referenced != expected_alias:
                errors.append(
                    f"{manifest.relative_to(ROOT)} declares shortcut alias not owned by this feature: {referenced}"
                )
            else:
                seen_shortcut_aliases[referenced].append(feature_dir.name)
            continue
        referenced_owner = resolve_import_owner(referenced)
        if referenced_owner and referenced_owner != owner and (
            referenced_owner == "app" or referenced_owner.startswith("feature:")
        ):
            errors.append(
                f"{manifest.relative_to(ROOT)} declares component owned by {referenced_owner}: {referenced}"
            )

for feature_name, alias in PUBLIC_SHORTCUT_ALIASES.items():
    owners = seen_shortcut_aliases.get(alias, [])
    if owners != [feature_name]:
        errors.append(
            f"shortcut alias ownership mismatch for {alias}: expected feature:{feature_name}, found {owners or 'none'}"
        )

# Photo ownership is API-based: standardized picking, square rendering, caching, and
# downsampling belong to :core:ui. Feature modules keep only owner/persistence adapters.
photo_features = ("soldi", "supercontacts", "luoghi")
shared_photo_source = ROOT / "core/ui/src/main/java/com/gernalix/personalhub/core/ui/photo/HubPhotoUi.kt"
shared_photo_text = shared_photo_source.read_text() if shared_photo_source.is_file() else ""
for symbol in ("rememberHubPhotoPicker", "HubSquarePhotoThumbnail", "HubPhotoGrid"):
    if f"fun {symbol}" not in shared_photo_text and f"fun <T> {symbol}" not in shared_photo_text:
        errors.append(f"shared photo engine missing {symbol}")

for feature_name in photo_features:
    build_file = ROOT / f"feature/{feature_name}/build.gradle.kts"
    if ":core:ui" not in project_dependencies(build_file):
        errors.append(f"feature/{feature_name}/build.gradle.kts must depend on :core:ui for shared photos")
    kotlin_files = source_files.get(f"feature:{feature_name}", [])
    texts = [(path, path.read_text()) for path in kotlin_files]
    if not any("com.gernalix.personalhub.core.ui.photo" in text for _, text in texts):
        errors.append(f"feature:{feature_name} does not use the shared photo API")
    for path, text in texts:
        relative = path.relative_to(ROOT)
        if "coil3." in text:
            errors.append(f"{relative} owns Coil photo rendering; use the shared photo API")
        if "ActivityResultContracts.OpenDocument" in text:
            errors.append(f"{relative} owns document photo picking; use rememberHubPhotoPicker")

people_source = ROOT / "feature/supercontacts/src/main"
for legacy_name in ("ContactPhotoStore.kt", "ContactPhotoResolver.kt", "PhotoCapsule.kt"):
    if any(path.name == legacy_name for path in people_source.rglob("*.kt")):
        errors.append(f"People legacy photo pipeline still exists: {legacy_name}")
for path in source_files.get("feature:supercontacts", []):
    text = path.read_text()
    for forbidden_api in ("BitmapFactory", "LruCache", "ContactPhotoCropSpec", "saveCroppedContactPhoto"):
        if forbidden_api in text:
            errors.append(
                f"{path.relative_to(ROOT)} owns legacy photo API {forbidden_api}; "
                "use the shared non-destructive photo engine"
            )


if errors:
    print("ARCHITECTURE_BOUNDARIES=FAIL")
    print("\n".join(sorted(set(errors))))
    sys.exit(1)

print("ARCHITECTURE_BOUNDARIES=PASS")
