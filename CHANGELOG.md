# Changelog

## 1.1.1 — 2026-10-04

- Restore blue unread dots by adding a unique query parameter to the exact forum topic-list GET request, avoiding shared cached responses with incorrect `has_new_post` values.
- Preserve the server's read state and the existing last-page/Back model synchronization.
- Add 31 helper contract checks and decoded request-hook verification.

Full stock-APK patch/decode/signature/alignment checks and altered-input rejection passed. Android 14 emulator tests restored unread flags from 0/15 to 14/15, confirmed page-1 retention and last-page clearing, retained read state after refresh/cold relaunch, and observed a dot return when a new reply arrived. Physical-device testing remains pending.

## 1.1.0 — 2026-10-03

- Add thumbs-down controls to individual replies using the app's native voting and net-score handling.
- Match stock icon sizing and preserve selected state, native animation, and recycled-row behavior.
- Return the refreshed topic model on Back so the list receives current server unread state.
- Add 24 isolated reply-control checks, decoded hook validation, and an Android 14 emulator record.
- Automate versioned bundle publication and update the Manager source only after verifying the release asset.

Live undo/error rollback, a naturally unread blue-dot transition, and physical-device testing remain unverified.

## 1.0.0 — 2026-08-21

- Add strict ReVanced patch support for RedFlagDeals Forums `1.11.7`.
- Repair current YID/phpBB session handling and SID parsing.
- Decouple topic reply permission from automatic logout.
- Refresh authoritative topic state before exposing reply controls.
- Replace cached pagination progress-holder reuse.
- Add privacy-safe runtime diagnostics and fail-closed build verification.
