# OpenDroid atom prompt

Class: N  
Authority: none  
Mandatory route: no

Use this prompt to shape one bounded OpenDroid task. It is not acceptance evidence and cannot authorize Android, GitHub, merge, release, device, Accessibility, Shizuku, signing, or store operations.

```text
Given one exact GitHub Issue in ed3c/kotlin-auto-webview:

1. Read AGENTS.md.
2. If the Issue binds a system requirement, read contracts/system-v1.md.
3. Read the Issue-selected nearest executable contract/test and its implementation; stop document traversal.

Return one atom with:
- exact Issue, base/head, dependencies, and allowed/excluded paths;
- one testable goal and explicit non-goals;
- requirement IDs already defined in contracts/system-v1.md;
- the smallest code or gate change that closes the physical trigger;
- fixed argv/cwd/timeout/expected exit and direct readback;
- one positive and one planted-negative control;
- cleanup/residue and rollback subject;
- evidence ceiling with static, emulator, physical device, privileged effect, store, merge, release, and production kept separate.

Do not add a framework, registry, queue, document hop, or new capability unless the nearest existing boundary cannot reject the observed failure.
```

