# Vendored ReVanced patches Gradle plugin

This directory contains source derived from the official ReVanced patches Gradle plugin `1.0.0-dev.10`, commit `7bdf4324`.

It is vendored because the official binary is distributed through GitHub Packages, which requires authenticated package access. Local adaptations compile the plugin on Java 21, use the checksum-pinned ReVanced CLI jar as the Patcher 22 compile dependency, disable publishing, and make `.rvp` archives reproducible.

The upstream GPL-3.0 license is preserved in this directory. This vendored component is build infrastructure; it does not alter the runtime behavior of the RedFlagDeals patch.
