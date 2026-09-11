#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BUILD_ROOT = ROOT / "composeApp" / "build"
REPORT_ROOT = ROOT / "build" / "reports" / "android-distribution"
RECEIPT_PATH = ROOT / "build" / "receipts" / "android-distribution-profiles.json"
PROFILE_SOURCE = (
    ROOT
    / "composeApp"
    / "src"
    / "androidMain"
    / "kotlin"
    / "dev"
    / "ed3c"
    / "autowebview"
    / "device"
    / "profile"
    / "AndroidCompiledDistributionProfile.kt"
)
SOURCE_SET_REPORT = BUILD_ROOT / "reports" / "android-distribution" / "source-sets.txt"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
BUILD_TYPES = ("debug", "release")
EXPECTED = {
    "playSafe": {
        "distribution_profile": "PLAY_SAFE",
        "application_id": "dev.ed3c.autowebview",
        "artifact_class": "PLAY_STORE_REVIEW_CANDIDATE",
    },
    "enterprise": {
        "distribution_profile": "ENTERPRISE_SIDELOAD",
        "application_id": "dev.ed3c.autowebview.enterprise",
        "artifact_class": "MANAGED_SIDELOAD",
    },
}

PLAY_SAFE_FORBIDDEN_PERMISSIONS = {
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.READ_SMS",
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.CALL_PHONE",
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.READ_CALENDAR",
    "android.permission.WRITE_CALENDAR",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.PACKAGE_USAGE_STATS",
}

PLAY_SAFE_FORBIDDEN_DEPENDENCY_MARKERS = (
    "rikka.shizuku",
    "moe.shizuku",
    "shizuku-api",
    "libsu",
    "topjohnwu",
)

PLAY_SAFE_FORBIDDEN_APK_MARKERS = (
    b"rikka/shizuku",
    b"moe/shizuku",
    b"ShizukuProvider",
)

DYNAMIC_PROFILE_SOURCE_MARKERS = (
    "System.getenv",
    "System.getProperty",
    "getStringExtra",
    "SharedPreferences",
    "DataStore",
    "RemoteConfig",
    "Mcp",
    "MODEL",
)


@dataclass(frozen=True)
class ManifestFacts:
    permissions: tuple[str, ...]
    declared_permissions: tuple[tuple[str, str], ...]
    exported_components: tuple[tuple[str, str], ...]
    accessibility_services: tuple[str, ...]
    shizuku_components: tuple[str, ...]
    allow_backup: str | None
    uses_cleartext_traffic: str | None


class VerificationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise VerificationError(message)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_git(*args: str) -> str:
    proc = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if proc.returncode != 0:
        fail(f"git {' '.join(args)} failed: {proc.stdout.strip()}")
    return proc.stdout.strip()


def normalize_component_name(name: str, namespace: str) -> str:
    if name.startswith("."):
        return namespace + name
    if "." not in name:
        return namespace + "." + name
    return name


def load_json(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"cannot read JSON {path}: {exc}")
    if not isinstance(data, dict):
        fail(f"JSON object required: {path}")
    return data


def expected_internal_permission(application_id: str) -> str:
    return f"{application_id}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"


def validate_source_contract(profile: str, contract: dict[str, Any]) -> None:
    expected = EXPECTED[profile]
    for field, expected_value in expected.items():
        if contract.get(field) != expected_value:
            fail(f"{profile} {field} mismatch: {contract.get(field)!r} != {expected_value!r}")
    if contract.get("schema") != "kotlin-auto-webview/android-capability-profile/v1":
        fail(f"{profile} capability-profile schema mismatch")
    if contract.get("namespace") != "dev.ed3c.autowebview":
        fail(f"{profile} namespace mismatch")
    if contract.get("distribution_profile") == "ACCESSIBILITY_TOOL":
        fail("ACCESSIBILITY_TOOL cannot be a distributable profile")

    allowed_permissions = contract.get("allowed_permissions")
    if allowed_permissions != ["android.permission.INTERNET"]:
        fail(f"{profile} capability permissions must remain INTERNET-only at C2")

    expected_internal = [expected_internal_permission(expected["application_id"])]
    if contract.get("allowed_internal_signature_permissions") != expected_internal:
        fail(
            f"{profile} internal signature permission contract mismatch: "
            f"{contract.get('allowed_internal_signature_permissions')!r} != {expected_internal!r}"
        )

    if profile == "playSafe":
        if contract.get("allow_accessibility_service") is not False:
            fail("playSafe cannot allow AccessibilityService")
        if contract.get("allow_shizuku") is not False:
            fail("playSafe cannot allow Shizuku")
        if contract.get("allow_root_or_shell") is not False:
            fail("playSafe cannot allow root/shell")
        if contract.get("inbound_mobile_mcp") is not False:
            fail("playSafe cannot allow inbound mobile MCP")


def find_single_apk(profile: str, build_type: str) -> Path:
    candidates = sorted(
        path
        for path in (BUILD_ROOT / "outputs" / "apk").rglob("*.apk")
        if profile.lower() in path.as_posix().lower()
        and build_type.lower() in path.as_posix().lower()
        and "androidtest" not in path.as_posix().lower()
    )
    if len(candidates) != 1:
        fail(
            f"expected one {profile} {build_type} APK, found {len(candidates)}: "
            f"{[str(p) for p in candidates]}"
        )
    return candidates[0]


def find_output_metadata(profile: str, build_type: str) -> Path:
    candidates = sorted(
        path
        for path in (BUILD_ROOT / "outputs" / "apk").rglob("output-metadata.json")
        if profile.lower() in path.as_posix().lower()
        and build_type.lower() in path.as_posix().lower()
    )
    if len(candidates) != 1:
        fail(f"expected one {profile} {build_type} output-metadata.json, found {len(candidates)}")
    return candidates[0]


def application_id_from_metadata(path: Path) -> str:
    metadata = load_json(path)
    app_id = metadata.get("applicationId")
    if not isinstance(app_id, str) or not app_id:
        fail(f"applicationId absent from {path}")
    return app_id


def read_packaged_contract(apk: Path) -> tuple[dict[str, Any], str]:
    with zipfile.ZipFile(apk) as archive:
        name = "assets/capability-profile.json"
        try:
            data = archive.read(name)
        except KeyError:
            fail(f"{apk} does not package {name}")
    try:
        parsed = json.loads(data.decode("utf-8"))
    except Exception as exc:
        fail(f"{apk} packaged capability profile is unreadable: {exc}")
    if not isinstance(parsed, dict):
        fail(f"{apk} packaged capability profile is not an object")
    return parsed, sha256_bytes(data)


def locate_merged_manifest(profile: str, build_type: str) -> Path:
    candidates: list[Path] = []
    for path in (BUILD_ROOT / "intermediates").rglob("AndroidManifest.xml"):
        text = path.as_posix().lower()
        if profile.lower() not in text or build_type.lower() not in text:
            continue
        try:
            root = ET.parse(path).getroot()
        except Exception:
            continue
        application = root.find("application")
        if application is None:
            continue
        if application.findall("activity"):
            candidates.append(path)
    if not candidates:
        fail(f"no parsed merged manifest found for {profile} {build_type}")
    preference = ("packaged_manifests", "merged_manifests", "merged_manifest")
    candidates.sort(
        key=lambda p: (
            next((i for i, marker in enumerate(preference) if marker in p.as_posix()), len(preference)),
            len(p.as_posix()),
            p.as_posix(),
        )
    )
    return candidates[0]


def manifest_facts(path: Path, namespace: str) -> ManifestFacts:
    root = ET.parse(path).getroot()
    application = root.find("application")
    if application is None:
        fail(f"application element missing from {path}")

    permissions = sorted(
        {
            element.get(ANDROID_NS + "name", "")
            for tag in ("uses-permission", "uses-permission-sdk-23")
            for element in root.findall(tag)
            if element.get(ANDROID_NS + "name")
        }
    )
    declared_permissions = sorted(
        (
            element.get(ANDROID_NS + "name", ""),
            element.get(ANDROID_NS + "protectionLevel", ""),
        )
        for element in root.findall("permission")
        if element.get(ANDROID_NS + "name")
    )

    exported: list[tuple[str, str]] = []
    accessibility_services: list[str] = []
    shizuku_components: list[str] = []
    for component_type in ("activity", "activity-alias", "service", "receiver", "provider"):
        for element in application.findall(component_type):
            raw_name = element.get(ANDROID_NS + "name", "")
            if not raw_name:
                continue
            name = normalize_component_name(raw_name, namespace)
            if element.get(ANDROID_NS + "exported") == "true":
                exported.append((component_type, name))
            permission = element.get(ANDROID_NS + "permission", "")
            metadata_names = {
                child.get(ANDROID_NS + "name", "")
                for child in element.findall("meta-data")
            }
            if component_type == "service" and (
                permission == "android.permission.BIND_ACCESSIBILITY_SERVICE"
                or "android.accessibilityservice" in metadata_names
            ):
                accessibility_services.append(name)
            joined = " ".join((name, permission, *metadata_names)).lower()
            if "shizuku" in joined:
                shizuku_components.append(name)

    return ManifestFacts(
        permissions=tuple(permissions),
        declared_permissions=tuple(declared_permissions),
        exported_components=tuple(sorted(exported)),
        accessibility_services=tuple(sorted(accessibility_services)),
        shizuku_components=tuple(sorted(shizuku_components)),
        allow_backup=application.get(ANDROID_NS + "allowBackup"),
        uses_cleartext_traffic=application.get(ANDROID_NS + "usesCleartextTraffic"),
    )


def expected_exported(contract: dict[str, Any]) -> tuple[tuple[str, str], ...]:
    result: list[tuple[str, str]] = []
    namespace = str(contract["namespace"])
    values = contract.get("allowed_exported_components")
    if not isinstance(values, list):
        fail("allowed_exported_components must be a list")
    for item in values:
        if not isinstance(item, dict):
            fail("allowed_exported_components entry must be an object")
        component_type = item.get("type")
        name = item.get("name")
        if not isinstance(component_type, str) or not isinstance(name, str):
            fail("allowed_exported_components entry is incomplete")
        result.append((component_type, normalize_component_name(name, namespace)))
    return tuple(sorted(result))


def validate_manifest(profile: str, contract: dict[str, Any], facts: ManifestFacts) -> None:
    capability_permissions = tuple(sorted(contract.get("allowed_permissions", [])))
    internal_signature_permissions = tuple(
        sorted(contract.get("allowed_internal_signature_permissions", []))
    )
    expected_uses = tuple(sorted(capability_permissions + internal_signature_permissions))
    if facts.permissions != expected_uses:
        fail(f"{profile} permission set mismatch: {facts.permissions} != {expected_uses}")

    expected_declarations = tuple((name, "signature") for name in internal_signature_permissions)
    if facts.declared_permissions != expected_declarations:
        fail(
            f"{profile} internal permission declarations mismatch: "
            f"{facts.declared_permissions} != {expected_declarations}"
        )

    if facts.exported_components != expected_exported(contract):
        fail(
            f"{profile} exported component set mismatch: "
            f"{facts.exported_components} != {expected_exported(contract)}"
        )
    if facts.allow_backup != "false":
        fail(f"{profile} must preserve allowBackup=false")
    if facts.uses_cleartext_traffic != "false":
        fail(f"{profile} must preserve usesCleartextTraffic=false")

    if profile == "playSafe":
        forbidden = sorted(set(facts.permissions).intersection(PLAY_SAFE_FORBIDDEN_PERMISSIONS))
        if forbidden:
            fail(f"playSafe contains forbidden permissions: {forbidden}")
        if facts.accessibility_services:
            fail(f"playSafe contains AccessibilityService declarations: {facts.accessibility_services}")
        if facts.shizuku_components:
            fail(f"playSafe contains Shizuku manifest components: {facts.shizuku_components}")


def validate_dependency_report(profile: str, report: Path) -> str:
    if not report.is_file():
        fail(f"dependency report missing for {profile}: {report}")
    text = report.read_text(encoding="utf-8", errors="replace")
    if profile == "playSafe":
        lower = text.lower()
        markers = [marker for marker in PLAY_SAFE_FORBIDDEN_DEPENDENCY_MARKERS if marker in lower]
        if markers:
            fail(f"playSafe runtime classpath contains forbidden dependency markers: {markers}")
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def validate_apk_markers(profile: str, apk: Path) -> None:
    if profile != "playSafe":
        return
    data = apk.read_bytes()
    hits = [marker.decode("ascii") for marker in PLAY_SAFE_FORBIDDEN_APK_MARKERS if marker in data]
    if hits:
        fail(f"playSafe APK contains forbidden privileged markers: {hits}")


def validate_profile_source(source_text: str) -> None:
    if "BuildConfig.DISTRIBUTION_PROFILE_ID" not in source_text:
        fail("compiled profile binder must consume BuildConfig.DISTRIBUTION_PROFILE_ID")
    if "DistributionProfile.ACCESSIBILITY_TOOL" not in source_text:
        fail("compiled profile binder must explicitly reject ACCESSIBILITY_TOOL")
    found = [marker for marker in DYNAMIC_PROFILE_SOURCE_MARKERS if marker in source_text]
    if found:
        fail(f"compiled profile binder contains runtime override inputs: {found}")


def validate_source_set_report(text: str) -> None:
    header = next((line for line in text.splitlines() if line.startswith("AGP productFlavors=")), "")
    if not header:
        fail("AGP product-flavor report is missing")
    flavors = set(header.split("=", 1)[1].split(","))
    if flavors != {"playSafe", "enterprise"}:
        fail(f"unexpected distributable flavors: {sorted(flavors)}")
    if "accessibilityTool" in text or "AccessibilityTool" in text:
        fail("accessibilityTool source set/variant must remain absent")
    for profile in ("playSafe", "enterprise"):
        if f"sourceSet={profile};" not in text:
            fail(f"AGP source-set report does not include {profile}")


def validate_tasks_report(text: str) -> None:
    required = (
        "assemblePlaySafeDebug",
        "assembleEnterpriseDebug",
        "assemblePlaySafeRelease",
        "assembleEnterpriseRelease",
        "bundlePlaySafeRelease",
        "bundleEnterpriseRelease",
    )
    for task in required:
        if task not in text:
            fail(f"explicit profile task missing: {task}")
    if re.search(r"assembleAccessibilityTool|bundleAccessibilityTool", text):
        fail("accessibilityTool release tasks must not exist")


def verify_identity_bindings() -> tuple[str, str, str, str]:
    source_head = os.environ.get("KAW_C2_SOURCE_HEAD", "")
    source_tree = os.environ.get("KAW_C2_SOURCE_TREE", "")
    if not re.fullmatch(r"[0-9a-f]{40}", source_head):
        fail("KAW_C2_SOURCE_HEAD must bind the exact selected #70 source head")
    if not re.fullmatch(r"[0-9a-f]{40}", source_tree):
        fail("KAW_C2_SOURCE_TREE must bind the exact selected #70 source tree")

    actual_source_tree = run_git("rev-parse", f"{source_head}^{{tree}}")
    if actual_source_tree != source_tree:
        fail(f"selected source tree drift: {actual_source_tree} != {source_tree}")
    proc = subprocess.run(
        ["git", "merge-base", "--is-ancestor", source_head, "HEAD"],
        cwd=ROOT,
        check=False,
    )
    if proc.returncode != 0:
        fail("selected #70 source head is not an ancestor of the C2 evidence head")

    head = run_git("rev-parse", "HEAD")
    tree = run_git("rev-parse", "HEAD^{tree}")
    return source_head, source_tree, head, tree


def verify() -> dict[str, Any]:
    source_head, source_tree, head, tree = verify_identity_bindings()
    validate_profile_source(PROFILE_SOURCE.read_text(encoding="utf-8"))

    if not SOURCE_SET_REPORT.is_file():
        fail(f"source-set report missing: {SOURCE_SET_REPORT}")
    source_set_text = SOURCE_SET_REPORT.read_text(encoding="utf-8")
    validate_source_set_report(source_set_text)

    tasks_report = REPORT_ROOT / "tasks.txt"
    if not tasks_report.is_file():
        fail(f"tasks report missing: {tasks_report}")
    validate_tasks_report(tasks_report.read_text(encoding="utf-8", errors="replace"))

    profiles: dict[str, Any] = {}
    seen_application_ids: set[str] = set()
    for profile, expected in EXPECTED.items():
        source_contract_path = (
            ROOT / "composeApp" / "src" / profile / "assets" / "capability-profile.json"
        )
        contract = load_json(source_contract_path)
        validate_source_contract(profile, contract)

        variants: dict[str, Any] = {}
        profile_application_ids: set[str] = set()
        for build_type in BUILD_TYPES:
            apk = find_single_apk(profile, build_type)
            metadata = find_output_metadata(profile, build_type)
            application_id = application_id_from_metadata(metadata)
            if application_id != expected["application_id"]:
                fail(f"{profile} {build_type} packaged applicationId mismatch: {application_id}")
            profile_application_ids.add(application_id)

            packaged_contract, packaged_contract_sha = read_packaged_contract(apk)
            if packaged_contract != contract:
                fail(
                    f"{profile} {build_type} packaged capability profile differs from source contract"
                )

            manifest_path = locate_merged_manifest(profile, build_type)
            facts = manifest_facts(manifest_path, str(contract["namespace"]))
            validate_manifest(profile, contract, facts)

            dependency_report = REPORT_ROOT / f"{profile}-{build_type}-runtime-classpath.txt"
            dependency_digest = validate_dependency_report(profile, dependency_report)
            validate_apk_markers(profile, apk)

            if profile.lower() not in apk.name.lower() or build_type.lower() not in apk.name.lower():
                fail(f"{profile} {build_type} artifact filename is ambiguous: {apk.name}")

            capability_permissions = tuple(sorted(contract.get("allowed_permissions", [])))
            internal_signature_permissions = tuple(
                sorted(contract.get("allowed_internal_signature_permissions", []))
            )
            variants[build_type] = {
                "application_id": application_id,
                "apk": str(apk.relative_to(ROOT)),
                "apk_sha256": sha256_file(apk),
                "apk_size": apk.stat().st_size,
                "capability_profile_sha256": packaged_contract_sha,
                "manifest": str(manifest_path.relative_to(ROOT)),
                "capability_permissions": list(capability_permissions),
                "internal_signature_permissions": list(internal_signature_permissions),
                "manifest_permission_uses": list(facts.permissions),
                "manifest_permission_declarations": [
                    {"name": name, "protection_level": protection}
                    for name, protection in facts.declared_permissions
                ],
                "exported_components": [
                    {"type": kind, "name": name}
                    for kind, name in facts.exported_components
                ],
                "accessibility_services": list(facts.accessibility_services),
                "shizuku_components": list(facts.shizuku_components),
                "runtime_classpath_sha256": dependency_digest,
            }

        if len(profile_application_ids) != 1:
            fail(f"{profile} application identity differs between debug and release")
        application_id = next(iter(profile_application_ids))
        if application_id in seen_application_ids:
            fail("Play-safe and enterprise package identities must be distinct")
        seen_application_ids.add(application_id)
        profiles[profile] = {
            "distribution_profile": contract["distribution_profile"],
            "artifact_class": contract["artifact_class"],
            "application_id": application_id,
            "variants": variants,
        }

    receipt = {
        "schema": "kotlin-auto-webview/android-distribution-profiles-receipt/v1",
        "state": "PASS",
        "source": {"head": source_head, "tree": source_tree},
        "evidence": {
            "head": head,
            "tree": tree,
            "run_id": os.environ.get("GITHUB_RUN_ID", ""),
            "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
        },
        "profiles": profiles,
        "controls": {
            "source_set_report": "PASS",
            "profile_source_static_override_scan": "PASS",
            "explicit_release_tasks": "PASS",
            "generic_assemble_release": "REJECTED_BY_BUILD_CONFIGURATION",
            "generic_bundle_release": "REJECTED_BY_BUILD_CONFIGURATION",
            "accessibility_tool_variant": "ABSENT",
            "play_safe_accessibility_service": "ABSENT",
            "play_safe_shizuku": "ABSENT",
            "play_safe_broad_permissions": "ABSENT",
            "profile_installer_exported_receiver": "REMOVED_BY_APP_MANIFEST",
            "androidx_internal_receiver_permission": "SIGNATURE_ONLY_AND_APP_SCOPED",
            "every_variant_capability_manifest_and_apk_sha256": "PASS",
        },
        "evidence_ceiling": {
            "maximum_claim": "ANDROID_COMPILE_PACKAGE_PROFILE_SEPARATION_ONLY",
            "google_play_approval": "HUMAN_EXTERNAL_REVIEW_REQUIRED",
            "accessibility_tool_eligibility": "HUMAN_EXTERNAL_REVIEW_REQUIRED",
            "signing": "NOT_EXERCISED",
            "merge": "NOT_EXERCISED",
            "release": "NOT_EXERCISED",
            "physical_device": "NOT_EXERCISED",
            "production_rollout": "NOT_EXERCISED",
        },
    }
    RECEIPT_PATH.parent.mkdir(parents=True, exist_ok=True)
    RECEIPT_PATH.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return receipt


def expect_failure(name: str, function: Callable[..., Any], *args: Any) -> None:
    try:
        function(*args)
    except VerificationError:
        return
    raise VerificationError(f"negative control did not fail: {name}")


def self_test() -> None:
    app_id = "dev.ed3c.autowebview"
    internal_permission = expected_internal_permission(app_id)
    play = {
        "schema": "kotlin-auto-webview/android-capability-profile/v1",
        "distribution_profile": "PLAY_SAFE",
        "artifact_class": "PLAY_STORE_REVIEW_CANDIDATE",
        "namespace": app_id,
        "application_id": app_id,
        "allowed_permissions": ["android.permission.INTERNET"],
        "allowed_internal_signature_permissions": [internal_permission],
        "allowed_exported_components": [
            {"type": "activity", "name": "dev.ed3c.autowebview.MainActivity"}
        ],
        "allow_accessibility_service": False,
        "allow_shizuku": False,
        "allow_root_or_shell": False,
        "inbound_mobile_mcp": False,
    }
    validate_source_contract("playSafe", play)
    good_facts = ManifestFacts(
        permissions=("android.permission.INTERNET", internal_permission),
        declared_permissions=((internal_permission, "signature"),),
        exported_components=(("activity", "dev.ed3c.autowebview.MainActivity"),),
        accessibility_services=(),
        shizuku_components=(),
        allow_backup="false",
        uses_cleartext_traffic="false",
    )
    validate_manifest("playSafe", play, good_facts)

    expect_failure(
        "play-safe broad permission",
        validate_manifest,
        "playSafe",
        play,
        ManifestFacts(
            permissions=(
                "android.permission.INTERNET",
                "android.permission.READ_SMS",
                internal_permission,
            ),
            declared_permissions=good_facts.declared_permissions,
            exported_components=good_facts.exported_components,
            accessibility_services=(),
            shizuku_components=(),
            allow_backup="false",
            uses_cleartext_traffic="false",
        ),
    )
    expect_failure(
        "play-safe accessibility service",
        validate_manifest,
        "playSafe",
        play,
        ManifestFacts(
            permissions=good_facts.permissions,
            declared_permissions=good_facts.declared_permissions,
            exported_components=good_facts.exported_components,
            accessibility_services=("dev.ed3c.BadService",),
            shizuku_components=(),
            allow_backup="false",
            uses_cleartext_traffic="false",
        ),
    )
    expect_failure(
        "internal permission loses signature protection",
        validate_manifest,
        "playSafe",
        play,
        ManifestFacts(
            permissions=good_facts.permissions,
            declared_permissions=((internal_permission, "normal"),),
            exported_components=good_facts.exported_components,
            accessibility_services=(),
            shizuku_components=(),
            allow_backup="false",
            uses_cleartext_traffic="false",
        ),
    )
    expect_failure(
        "unexpected exported library receiver",
        validate_manifest,
        "playSafe",
        play,
        ManifestFacts(
            permissions=good_facts.permissions,
            declared_permissions=good_facts.declared_permissions,
            exported_components=good_facts.exported_components
            + (("receiver", "androidx.profileinstaller.ProfileInstallReceiver"),),
            accessibility_services=(),
            shizuku_components=(),
            allow_backup="false",
            uses_cleartext_traffic="false",
        ),
    )
    expect_failure(
        "accessibility-tool distributable profile",
        validate_source_contract,
        "playSafe",
        {**play, "distribution_profile": "ACCESSIBILITY_TOOL"},
    )
    expect_failure(
        "runtime profile override source",
        validate_profile_source,
        "BuildConfig.DISTRIBUTION_PROFILE_ID\n"
        "DistributionProfile.ACCESSIBILITY_TOOL\n"
        "System.getenv(\"PROFILE\")",
    )

    with tempfile.TemporaryDirectory() as temp:
        report = Path(temp) / "deps.txt"
        report.write_text("org.example:ok:1.0\nrikka.shizuku:api:13\n", encoding="utf-8")
        expect_failure("play-safe Shizuku dependency", validate_dependency_report, "playSafe", report)

    validate_source_set_report(
        "AGP productFlavors=enterprise,playSafe\n"
        "AGP sourceSet=playSafe;manifest=x;java=x;assets=x\n"
        "AGP sourceSet=enterprise;manifest=x;java=x;assets=x\n"
    )
    validate_tasks_report(
        "assemblePlaySafeDebug\nassembleEnterpriseDebug\n"
        "assemblePlaySafeRelease\nassembleEnterpriseRelease\n"
        "bundlePlaySafeRelease\nbundleEnterpriseRelease\n"
    )
    expect_failure(
        "accessibilityTool variant",
        validate_source_set_report,
        "AGP productFlavors=accessibilityTool,enterprise,playSafe\n"
        "AGP sourceSet=playSafe;manifest=x;java=x;assets=x\n"
        "AGP sourceSet=enterprise;manifest=x;java=x;assets=x\n"
        "AGP sourceSet=accessibilityTool;manifest=x;java=x;assets=x\n",
    )
    print("android distribution profile checker self-test: PASS")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("verify", "self-test"))
    args = parser.parse_args(argv)
    try:
        if args.command == "self-test":
            self_test()
        else:
            receipt = verify()
            print(
                "android distribution profile verification: PASS "
                f"head={receipt['evidence']['head']} tree={receipt['evidence']['tree']}"
            )
        return 0
    except VerificationError as exc:
        print(f"android distribution profile verification: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
