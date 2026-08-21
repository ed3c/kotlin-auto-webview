# Live GitHub receipts

The literal public canary receipt is generated at workflow runtime and uploaded as the `workspace-live-github-public-receipt` artifact. It is intentionally not committed as a mutable or stale tracked PASS file.

`receipt.schema.json` defines the bounded public-safe payload. The receipt verifier also enforces exact subject identity, W1 read-back, disclosure, cleanup, and evidence ceilings.
