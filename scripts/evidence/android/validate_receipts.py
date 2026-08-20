#!/usr/bin/env python3
"""Validate every Android OpenDroid evidence receipt in the fixed build/public locations."""

from __future__ import annotations

import json
from pathlib import Path

from evidence_contract import EvidenceContractError, load_bindings, validate_receipt

ROOT = Path(__file__).resolve().parents[3]
RECEIPT_DIRS = (
    ROOT / "build" / "receipts" / "android-opendroid",
    ROOT / "receipts" / "android" / "opendroid",
)


def main() -> int:
    bindings = load_bindings()
    paths: list[Path] = []
    for directory in RECEIPT_DIRS:
        if directory.is_dir():
            paths.extend(sorted(directory.glob("*.json")))
    if not paths:
        raise EvidenceContractError("no Android OpenDroid receipts found in fixed receipt locations")

    seen: set[str] = set()
    for path in paths:
        receipt = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(receipt, dict):
            raise EvidenceContractError(f"receipt is not an object: {path}")
        receipt_id = receipt.get("receipt_id")
        if not isinstance(receipt_id, str) or not receipt_id:
            raise EvidenceContractError(f"receipt_id absent: {path}")
        if receipt_id in seen:
            raise EvidenceContractError(f"duplicate receipt_id: {receipt_id}")
        seen.add(receipt_id)
        validate_receipt(receipt, bindings)
        print(f"validated {path.relative_to(ROOT)}: {receipt_id} {receipt['lane']} {receipt['state']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
