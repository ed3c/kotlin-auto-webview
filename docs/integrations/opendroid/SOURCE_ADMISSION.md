# D0 source admission

Issue: #65

## Exact baseline

- Product base: `8d0ac180971d8aa5a93643165d1a59cf26ed6e71`
- Upstream: `yashab-cyber/opendroid@0e9e5898f0e0dcc679d99e5f4518e19310e96775`
- Upstream tree: `4c9d1d5f644fc69d9a0a5e658b51d1753fd2ac32`
- License subject: `LICENSE@789a5cebf5fefbc9a1b73af4d7725aaa3c9c2446`

## Decisions frozen before implementation

- OpenDroid is a behavioral reference, not an authority source.
- No wholesale vendoring or binary dependency is admitted.
- Device-wide Accessibility execution is outside `PLAY_SAFE`.
- Shizuku, when later admitted for enterprise, must be a typed allowlisted UserService surface; raw shell/root/terminal remains denied.
- Model/MCP/network output is proposal data only.
- First-match and coordinate convenience mechanisms cannot become action authority.
- Platform callback success cannot become `APPLIED` without independently observed postconditions.

## Preparation gate

The branch is ready for D0 validation when all machine files are readable and the independent Shadow review confirms no L3 authority, privacy, license, or profile-widening issue.

D0 completion still requires deterministic completeness, stale-pin, provenance, policy-profile, and denial mutation tests. Until those tests exist and pass on the exact head, the issue state remains open and downstream #66 must not consume D0 as a completed receipt.

## Handoff to #66

#66 may use the machine-readable capability and profile schemas only after Tech Lead selects an exact D0 head and records the validation receipt. #66 must not import Android classes, `Map<String,String>` action payloads, OpenDroid aliases, or any direct execution authority.
