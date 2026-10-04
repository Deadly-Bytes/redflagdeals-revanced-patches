# RedFlagDeals Forums ReVanced patch

This is an unofficial ReVanced patch for the discontinued RedFlagDeals Forums Android app. It updates the app so it continues to work with the current forum and adds features available on the website.

It patches RedFlagDeals Forums **version 1.11.7**. The original APK is not included; obtain your own copy and patch it with ReVanced Manager.

## What it adds

- Thumbs-down voting on individual replies, including the correct net score and selected state.
- Working blue unread dots in topic lists.
- Correct unread state after opening a thread, visiting its last page, and returning to the list.
- Reliable login and reply permissions with the current forum API.
- Stable scrolling and pagination in long threads.

The patch keeps the forum's server-side read state and uses the app's normal login and voting rules. It does not add a separate account or send your data anywhere else.

## Install with ReVanced Manager

1. Open ReVanced Manager and add this patch source:

   `https://raw.githubusercontent.com/Deadly-Bytes/redflagdeals-revanced-patches/main/source.json`

2. Choose your unmodified RedFlagDeals Forums **1.11.7** APK.
3. Select **Fix RedFlagDeals Forums** and patch the APK.
4. Install the patched APK.

You can also download the `.rvp` file from the [Releases](https://github.com/Deadly-Bytes/redflagdeals-revanced-patches/releases) page and add it to ReVanced Manager as a local patch bundle.

### Installing beside the official app

Android normally treats the patched APK as a different signature, so it may not install as an update to the official app. If Android asks you to remove the official app, back up anything important first: uninstalling an app removes its local data, including saved alerts. Installing with the same signing key later allows updates without clearing that data.

## Patch with the command line

Download the latest `.rvp` from Releases and use ReVanced CLI with an unmodified 1.11.7 APK:

```shell
java -jar revanced-cli-6.0.0-all.jar patch \
  -p redflagdeals-revanced-patches-1.1.1.rvp \
  --exclusive -e "Fix RedFlagDeals Forums" \
  -o RedFlagDeals-Forums-patched.apk \
  RedFlagDeals-Forums-v1.11.7.apk
```

Do not distribute the generated APK. RedFlagDeals, Yellow Pages Group, and ReVanced are not affiliated with this project.

## Source and license

The source is available in this repository under GPL-3.0. The project distributes patch source and `.rvp` bundles only; it does not distribute the proprietary RedFlagDeals APK.
