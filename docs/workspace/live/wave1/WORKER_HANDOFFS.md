# Wave-1 Worker handoffs

These packets are zero-context supplements to the full issue bodies. They do
not replace `contract-lock.json`.

## L2-GH / #165

```yaml
role: GitHub live-evidence Worker
git_parent:
  pr: 158
  head: 3294fb2b4d86fef91f3f2c63e28718c490147808
consume:
  - GH-REST
  - GH-CONTRACTS
  - GH-ADAPTER
  - W6-VERIFIER
first_decision: reuse GitHubRestMetadataSource; do not add a second REST client
first_safe_output:
  - public exact-subject canary
  - sanitized receipt schema/validator
  - rate-limit/cancellation/cleanup receipts
public_canary: READY
private_canary: BLOCKED_EXTERNAL_AUTHORITY
maximum_claim: EXACT_READ_ONLY_LIVE_GITHUB_CONNECTOR
```

Required first red: old check or changed PR head must fail the exact-subject
receipt.

## L3-GOOGLE / #166

```yaml
role: Google projection transport Worker
git_parent:
  pr: 160
  head: 95754e2a7ea6a09da030da3803313fe49641b677
consume:
  - GOOGLE-CONTRACTS
  - GOOGLE-SAGA
  - W6-VERIFIER
first_decision: implement GoogleProjectionTransport; do not alter saga authority
first_safe_output:
  - transport interface adapter and deterministic HTTP fixtures
  - token/account/file redaction controls
  - revision/read-back canary command contract
live_account: BLOCKED_EXTERNAL_AUTHORITY
maximum_claim: EXACT_ADMITTED_GOOGLE_PROJECTION_ACCOUNT
```

Required first red: write ACK without authenticated read-back cannot produce
`SYNCED`.

## L4-BETTOR / #167

```yaml
role: KAW/Bettor handoff Worker
git_parent:
  pr: 161
  head: 56eb824866e7e74d63a4297748c647cff738db51
consume:
  - FEDERATION-ROUTER
  - BETTOR-GATEWAY-MANIFEST
  - BETTOR-WORKER-RECEIPT
  - W6-VERIFIER
external_owner: ed3c/bettor-arena#197
first_decision: bind existing RouteProposalPacket to a new Bettor consumer; do not duplicate LoopX
first_safe_output:
  - cross-repository schema/digest contract
  - KAW receipt-reference validator
  - deterministic deny/idempotency/version-drift tests
live_handoff: BLOCKED_ON_CONSUMER_AND_RUNTIME
maximum_claim: EXACT_LIVE_BETTOR_HANDOFF
```

Required first red: route ACK cannot be accepted as Worker execution or Gate
success.

## L5-DOMAIN / #168

```yaml
role: Domain authority receipt Worker
git_parent:
  pr: 161
  head: 56eb824866e7e74d63a4297748c647cff738db51
consume:
  - FEDERATION-ROUTER
  - TVL-SEMANTIC-RECEIPT
  - W6-VERIFIER
external_owner: ed3c/truth-verify-loop#47
first_decision: validate receipt identity and owner verdict; KAW does not verify the claim
first_safe_output:
  - KAW receipt-reference contract/adapter
  - truth-verify-loop producer contract
  - wrong-authority/subject/digest/environment tests
live_domain_receipt: BLOCKED_ON_CROSS_REPO_IMPLEMENTATION
maximum_claim: EXACT_LIVE_DOMAIN_AUTHORITY_RECEIPT
```

Required first red: the same receipt reused for another claim or authority must
fail.

## Convergence rule

The four branches are siblings. #171 is the only live-receipt convergence
owner. It selects exact heads and reruns W6 after integration; it does not
invent multi-parent Git ancestry.
