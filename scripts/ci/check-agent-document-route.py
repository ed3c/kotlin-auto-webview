#!/usr/bin/env python3
"""Verify the three-node Agent document route and N-class projections."""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

MAX_HOPS = 3
ROUTE_PATTERN = re.compile(r"<!-- agent-document-route\n(.*?)\n-->", re.DOTALL)
REQUIREMENT_PATTERN = re.compile(r"(?m)^### ([A-Z][A-Z0-9_.-]+)$")
ISSUE_REQUIREMENT_PATTERN = re.compile(r"<!-- kaw-requirement: ([A-Z][A-Z0-9_.-]+) -->")
NCLASS_PATHS = (
    "docs/integrations/opendroid/nclass/prompt.md",
    "docs/integrations/opendroid/nclass/plan.md",
)


def validate(root: pathlib.Path) -> list[str]:
    errors: list[str] = []
    agents_path = root / "AGENTS.md"
    contract_path = root / "contracts/system-v1.md"
    template_path = root / ".github/ISSUE_TEMPLATE/repository-mutating-atom.md"

    for path in (agents_path, contract_path, template_path):
        if not path.is_file():
            errors.append(f"missing required route surface: {path.relative_to(root)}")
    if errors:
        return errors

    agents_bytes = agents_path.read_bytes()
    if len(agents_bytes) > 16_384:
        errors.append(f"AGENTS.md is {len(agents_bytes)} bytes; maximum is 16384")
    agents = agents_bytes.decode("utf-8")
    match = ROUTE_PATTERN.search(agents)
    if not match:
        errors.append("AGENTS.md has no agent-document-route declaration")
        route: list[str] = []
    else:
        route = [line.strip() for line in match.group(1).splitlines() if line.strip()]

    if len(route) > MAX_HOPS:
        errors.append(f"Agent document route has {len(route)} nodes; maximum is {MAX_HOPS}")
    expected = ["AGENTS.md", "contracts/system-v1.md", "issue-named executable contract/test"]
    if route and route != expected:
        errors.append(f"Agent document route must be {expected!r}; found {route!r}")

    contract = contract_path.read_text(encoding="utf-8")
    requirement_ids = REQUIREMENT_PATTERN.findall(contract)
    if len(requirement_ids) != len(set(requirement_ids)):
        errors.append("contracts/system-v1.md contains duplicate requirement IDs")
    for required in ("DOC.ROUTE.001", "DOC.N_CLASS.001", "ISSUE.ATOM.001", "EVIDENCE.PHYSICAL.001"):
        if required not in requirement_ids:
            errors.append(f"contracts/system-v1.md is missing requirement {required}")

    template = template_path.read_text(encoding="utf-8")
    bindings = ISSUE_REQUIREMENT_PATTERN.findall(template)
    if not bindings:
        errors.append("Issue template has no kaw-requirement marker")
    for binding in bindings:
        if binding not in requirement_ids:
            errors.append(f"Issue template binds unknown requirement {binding}")

    for relative in NCLASS_PATHS:
        path = root / relative
        if not path.is_file():
            errors.append(f"missing N-class projection: {relative}")
            continue
        content = path.read_text(encoding="utf-8")
        for marker in ("Class: N", "Authority: none", "Mandatory route: no"):
            if marker not in content:
                errors.append(f"{relative} is missing {marker!r}")

    return errors


def selftest() -> int:
    failures = 0

    def write_fixture(root: pathlib.Path, route: list[str]) -> None:
        (root / "contracts").mkdir(parents=True, exist_ok=True)
        (root / ".github/ISSUE_TEMPLATE").mkdir(parents=True, exist_ok=True)
        for relative in NCLASS_PATHS:
            (root / relative).parent.mkdir(parents=True, exist_ok=True)
            (root / relative).write_text("Class: N\nAuthority: none\nMandatory route: no\n", encoding="utf-8")
        rendered = "\n".join(route)
        (root / "AGENTS.md").write_text(f"<!-- agent-document-route\n{rendered}\n-->\n", encoding="utf-8")
        (root / "contracts/system-v1.md").write_text(
            "\n".join(f"### {item}" for item in ("SYSTEM.PURPOSE.001", "DOC.ROUTE.001", "DOC.N_CLASS.001", "ISSUE.ATOM.001", "EVIDENCE.PHYSICAL.001")),
            encoding="utf-8",
        )
        (root / ".github/ISSUE_TEMPLATE/repository-mutating-atom.md").write_text(
            "<!-- kaw-requirement: SYSTEM.PURPOSE.001 -->\n", encoding="utf-8"
        )

    with tempfile.TemporaryDirectory(prefix="agent-document-route-") as workspace:
        root = pathlib.Path(workspace)
        expected = ["AGENTS.md", "contracts/system-v1.md", "issue-named executable contract/test"]
        write_fixture(root, expected)
        if validate(root):
            print("selftest FAIL: valid three-node route was rejected", file=sys.stderr)
            failures += 1
        else:
            print("selftest ok: valid three-node route passes")

        write_fixture(root, [*expected, "docs/architecture/fourth-hop.md"])
        planted = "\n".join(validate(root))
        if "maximum is 3" not in planted:
            print("selftest FAIL: planted fourth hop was not rejected", file=sys.stderr)
            failures += 1
        else:
            print("selftest ok: planted fourth hop is rejected")

    return 1 if failures else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", nargs="?", default=".")
    parser.add_argument("--selftest", action="store_true")
    arguments = parser.parse_args(argv)
    if arguments.selftest:
        return selftest()
    errors = validate(pathlib.Path(arguments.repository))
    for error in errors:
        print(error, file=sys.stderr)
    if not errors:
        print("agent document route: three-node route and N-class boundaries pass")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
