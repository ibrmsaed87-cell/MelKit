# Mod groups and toggler tabs — 0.1.7

## Scope and phone evidence

On 4 November 2026 the user confirmed that disabling a mod and returning it to the
game succeeded in the latest installed update (0.1.6). They requested separate
active/disabled mod tabs because disabled items otherwise moved to the end of a
mixed list, and approved implementing groups first and batching both in one build.

Groups use one list, one selected active group, a badge and active-first ordering.
Create/edit/delete definitions, select core mods, activate a group and undo the last
switch are implemented in this batch. Scanner, phone transfer, general history,
bundles, categories and custom-thumbnail tools remain planned.

## Group behavior and persistence

- Definitions, core selections and the one-level switch journal are stored in shared
  `Download/MelKit/ModProfiles`; vault content stays in `DisabledMods`.
- Saving definitions is metadata-only. Activating a group enables its selected paths
  plus core mods and disables other active mods through the verified vault protocol.
  An empty group leaves only core mods enabled. The UI explains this before moving files.
- A switch journal records before/after membership and SHA-256 identities before
  the first operation. Completion is recorded only after verifying the final state.
- Failure attempts rollback to the verified previous state. If rollback cannot finish,
  the journal remains pending and exposes Restore previous state after reconnect/restart.
  New group switches, definition changes, individual toggles and mod-file restore are
  blocked while a group recovery is pending. Other content types remain accessible.
- Undo is one level and refuses to touch newly introduced or modified active files.
  Core-definition changes invalidate old completed undo availability. Group deletion
  removes metadata only. The active badge is recalculated from actual membership;
  manual mod changes can leave no selected group marked active.
- Missing or ambiguous inactive mod versions stop a switch before mutation. The user
  can enable the desired version individually and then activate its group. No mod IDs,
  identifiers inside content or file bytes are rewritten.
- Unreadable vault records are retained and warned about. A group uses only verified
  available copies; a needed missing copy prevents activation. This is not a corruption
  scanner and makes no automatic dependency assumptions about saves and mods.
- Editor choices are read once into a short-lived service snapshot and delivered in
  pages, avoiding rescanning the vault for every picker page. Final file validation
  happens at activation. Payloads are bounded; profile definitions are limited to 50
  groups and stored metadata files to 8 MiB.

## Mod tabs

The active tab opens by default. Both labels include a localized count. Filtering
happens on the complete service inventory before pagination. Disable/return reloads
the current tab, keeps it selected and shows the outcome. A moved item appears in
the other tab; thumbnails and original paths are preserved. Recovery copies remain
explicitly labeled instead of being presented as a completed move.

The dashboard now reads actual group state and reports when Shizuku is needed or
state could not be checked. Its previous preparation notice no longer describes
implemented restore/toggle functionality as unimplemented.

## Host verification

OpenJDK 17 compiler-module execution of `ci/check-content-paths.sh` passed:

```text
Content path checks passed: 24
Export path checks passed: 26
Preview image checks passed: 29
New-file restore checks passed: 22
Mod vault checks: 33 passed
Mod profile checks: 52 passed
```

Total: **186 checks**. The new cases establish exact-byte round trips, pinned core
membership, two group switches and prior-group undo, empty groups, restart, one-level
undo, metadata-only edits/deletion, missing selections, traversal/symlink rejection,
external file changes, rollback, low space, ambiguous versions and explicitly chosen
versions. Process interruption is simulated with an abrupt error after a completed
move, bypassing automatic rollback; a new manager then recovers from the disk journal.
Late collision tests preserve the conflicting external bytes and recover after the
test removes that collision. These are host filesystem tests, not Android process-kill tests.

XML parsing, duplicate-key checks, placeholder parity, **195 matching translatable
keys** and **197 valid Kotlin string references** pass. Signing, namespace, min/target
SDKs and CI ownership are unchanged. Only the version name changes to 0.1.7; release
version codes still come from the existing GitLab pipeline configuration.

## Pending before claiming device acceptance

No Android SDK, emulator or Kotlin compiler is installed on this host. GitLab must
compile Kotlin, Compose, resources and AIDL. No APK build success is claimed here.
No emulator run or on-device group/tab test has been performed in this batch.

After installing the single signed update over 0.1.6:

1. Verify active/disabled tabs, counts and stay-on-tab behavior with one known mod.
2. Create two small groups and choose a separate core mod. Saving should not move files.
3. Close the game, activate each group and reopen the game to verify membership and
   the shared core mod. The current group should appear first and on the dashboard.
4. Close the game and undo; verify the exact previous group and mod behavior.
5. Reopen/reconnect MelKit and check persistence, group editing and metadata-only deletion.
6. Review Arabic/English, RTL/LTR, long names, picker scrolling and empty states.
7. Use disposable test mods for interrupted Shizuku and collision cases.

The protocol assumes the game is closed. It does not lock out unrelated apps writing
the same game files, and physical power-loss durability of Android shared-storage
directory entries is not established. Safety copies and incomplete records are retained.
