# Federated Capability Workspace evidence

Issue: #126 (W6)

This directory is the public evidence index for the deterministic Federated Capability Workspace foundation. It records exact public KAW subjects and keeps every unexercised external lane in the denominator.

## Exact implementation subjects

| Atom | Issue | Draft PR | Exact head | CI run | Literal evidence |
|---|---:|---:|---|---:|---|
| W0 contracts | #120 | #136 | `fba4a7f1c7cb8ca014d5a3cfe083fd9beaea4c5c` | `32248605485` | provider-neutral contract CI |
| W1 local registry | #121 | #139 | `286f588226be7b0f9ecb63042e3eaefd5bc77dd7` | `32332217916` | deterministic local persistence/outbox CI |
| W2 GitHub WorkGraph | #122 | #158 | `3294fb2b4d86fef91f3f2c63e28718c490147808` | `32345280914` | GitHub adapter fixture/mapping CI |
| W3 Google projection | #123 | #160 | `95754e2a7ea6a09da030da3803313fe49641b677` | `32377060202` | projection saga/read-back fixture CI |
| W4 federation router | #124 | #161 | `56eb824866e7e74d63a4297748c647cff738db51` | `32379913900` | deterministic routing/proposal CI |
| W5 read-only UI | #125 | #162 | `f0e37a4f2b39dd825bfd379d42f96c29ce887f37` | `32389825474` | read-only UI/controller/privacy CI |

W3 and W4 are sibling implementation subjects, not Git ancestors of W5. W6 references their exact PR/head/run receipts instead of manufacturing multi-parent Git ancestry.

## Evidence denominator

```text
L0 CONTRACT                  PASS
L1 LOCAL_DETERMINISTIC       PASS
L2 GITHUB_CONNECTOR          NOT_EXERCISED
L3 GOOGLE_PROJECTION_ACCOUNT NOT_EXERCISED
L4 BETTOR_ROUTE_HANDOFF      NOT_EXERCISED
L5 DOMAIN_AUTHORITY_RECEIPT  NOT_EXERCISED
L6 PHYSICAL_DEVICE           NOT_EXERCISED
L7 USER_OUTCOME              ABSENT
```

Only L0/L1 are currently admitted. W2/W3/W4 fixture tests remain `DETERMINISTIC_FIXTURE`; they cannot satisfy live-provider lanes.

## Deterministic oracle

Run:

```bash
python3 scripts/evidence/workspace/verify_federation_evidence.py \
  receipts/workspace/federation-evidence.json
python3 -m unittest discover -s tests/evidence/workspace -p 'test_*.py' -v
```

The verifier pins the exact W0–W5 Issue, PR, head SHA and workflow run IDs. It also rejects:

- URL/title substituted for stable identity;
- check SHA different from the exact implementation head;
- W3 projection evidence without read-back verification;
- Google/manual edit promoted to canonical mutation;
- private repository, Drive/customer identifiers in a public receipt;
- route request promoted to execution authority;
- destination receipt reused under another authority;
- deterministic fixture reused as live GitHub/Google/Bettor/provider evidence;
- simulator/CI promoted to physical-device evidence;
- CI/Issue/PR promoted to user outcome;
- any attempt to remove L0–L7 from the denominator.

## Privacy law

This is a public repository. The versioned W6 receipt contains only public KAW Issues/PRs/SHAs/runs and sanitized public receipt summaries.

It must never contain:

```text
private repo URL/name
private Google file ID/URL
customer identifier
credentials/tokens/cookies
restricted source content
```

Opaque/private cross-repository evidence belongs in its owning private evidence authority and may later be referenced only through admitted opaque IDs.

## Non-claims

W6 deterministic PASS does **not** prove:

- live GitHub connector access to private repositories;
- Google OAuth or real Docs/Sheets account synchronization;
- Bettor Arena live routing/execution;
- any domain repository accepting or executing a routed request;
- physical-device behavior;
- App Store / Play / legal / licensing approval;
- main-branch convergence or release;
- user adoption, user outcome, payment, revenue, or market validation.

A lane may move to `PASS` only when its receipt matches the exact subject, environment and lane required by `federation-evidence.json`.
