# RedFlagDeals Forums ReVanced patches

An unofficial compatibility patch for the discontinued RedFlagDeals Forums Android app.

This project is not affiliated with, endorsed by, or supported by RedFlagDeals, Yellow Pages Group, or the ReVanced project. It distributes patch source and ReVanced patch bundles only—never the proprietary RedFlagDeals APK.

## Supported app

- Package: `com.ypg.rfdforums`
- Version: `1.11.7`
- Required stock APK SHA-256: `e826029890e2c4e5193b75381061a353953a9e4c92e7601498cc01e2c997ad1d`
- Required stock APK size: `6511556` bytes

Fingerprints are deliberately strict. Any package, version, or bytecode mismatch aborts patching.

## What the patch changes

`Fix RedFlagDeals Forums` makes three focused repairs:

- Preserves the current YID/phpBB authentication tuple and accepts the current server's SID format.
- Stops non-replyable topics from being treated as an automatic logout, refreshes the exact topic before showing reply controls, and keeps locked topics readable.
- Creates a fresh pagination progress holder instead of reusing the cached holder that caused scrolling crashes.

Safe runtime diagnostics use the `RFDSession` log tag. They report endpoint names, authentication-component presence, topic IDs, and permission flags. They never log cookie values, credentials, IP addresses, account names, or reply text.

## Install with ReVanced Manager

### Add this repository by URL

In ReVanced Manager, open the **Patches** tab, choose the add-source option, select **Enter URL**, and paste:

`https://raw.githubusercontent.com/Deadly-Bytes/redflagdeals-revanced-patches/main/source.json`

This root-level source descriptor tells ReVanced Manager where to download the released `.rvp` bundle. The repository also publishes `patches.json`, a catalogue describing the patch name, supported package, and supported app version; that catalogue is not the URL to enter in Manager's add-source dialog.

Then select a legally obtained, unmodified RedFlagDeals Forums `1.11.7` APK from storage, enable `Fix RedFlagDeals Forums`, patch, and install the result.

### Add a local bundle instead

1. Download the `.rvp` patch bundle from this repository's Releases page.
2. Add the downloaded bundle as a local patch bundle in ReVanced Manager.
3. Select a legally obtained, unmodified RedFlagDeals Forums `1.11.7` APK from storage.
4. Enable `Fix RedFlagDeals Forums`, patch, and install the result.

An existing installation signed with a different certificate must be removed before Android will accept the newly patched APK. Removing an app also removes its local app data.

## Patch with ReVanced CLI

With ReVanced CLI `6.0.0` and the released bundle in the current directory:

```shell
java -jar revanced-cli-6.0.0-all.jar patch \
  -p redflagdeals-revanced-patches-1.0.0.rvp \
  --exclusive -e "Fix RedFlagDeals Forums" \
  -o RedFlagDeals-Forums-patched.apk \
  RedFlagDeals-Forums-v1.11.7.apk
```

Do not distribute the generated APK.

## Build and verify from source

Prerequisites:

- Windows PowerShell
- Java `21.0.6`
- Android SDK Build-Tools `36.0.0`
- The exact stock APK described above

```powershell
.\build-and-verify.ps1 -StockApk 'C:\path\to\RedFlagDeals-Forums-v1.11.7.apk'
```

The script checksum-downloads pinned tools, verifies the stock APK before and after patching, builds the `.rvp`, requires an explicit successful patch result, decodes and inspects transformed bytecode, verifies the APK signature, and checks 16 KiB page alignment. Local APKs, signing keys, downloaded tools, and generated artifacts are ignored by Git.

All dependency versions, source commits, and download hashes are recorded in `toolchain-lock.json`. The negative regression test in `tests/test-fail-closed.ps1` proves altered bytecode is rejected and partial CLI output is removed.

## Validation status

The patch passed Android 14 emulator testing across authenticated login, at least 22 distinct topics, locked and replyable topic states, refresh, pagination, deep scrolling, non-submitting reply composition, and force-stop/relaunch. No missing-auth request, verifier failure, pagination-holder crash, or automatic logout occurred.

No real forum reply was submitted. Physical-device validation of the public metadata-only rebuild should be recorded separately.

## Upstream basis and license

The project is based on the official ReVanced patches template and Patcher 22 toolchain. The vendored Gradle build plugin is derived from the official ReVanced plugin at commit `7bdf4324` and is included so builds do not require private GitHub Packages credentials.

Source code is licensed under GPL-3.0. See `LICENSE`. ReVanced trademarks and project assets belong to their respective owners.

Official references:

- https://github.com/ReVanced/revanced-documentation
- https://github.com/ReVanced/revanced-patches-template
- https://github.com/ReVanced/revanced-patcher/tree/v22.0.0/docs
- https://github.com/ReVanced/revanced-cli/releases/tag/v6.0.0
