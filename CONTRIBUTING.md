# Contributing

Bug reports and narrowly scoped compatibility fixes are welcome.

Do not attach or link proprietary APKs, authentication cookies, credentials, account names, private logs, or patched APKs. Logs should be reduced to the safe `RFDSession` diagnostics and relevant Android exception text.

Before submitting a pull request:

1. Keep compatibility restricted to the exact package/version unless a new APK has been independently analyzed.
2. Preserve fail-closed fingerprints and transformation assertions.
3. Run `:patches:buildAndroid` and the relevant static/runtime checks.
4. Explain any change to authentication, logout, reply-state, or pagination behavior.
5. Confirm no proprietary APK, signing key, downloaded tool, or generated local artifact is included.

By contributing, you agree that your contribution is licensed under GPL-3.0.
