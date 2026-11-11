# Third-party source notices

This repository is a source snapshot of fission's Android TV application. The
project's general third-party notices — the components the application is built
from, the services it talks to, and the full licence texts — are in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

This file records the third-party **source** attribution for the small number of
source files that are adapted from, or contain notice-bearing material from,
another project. Those notices are retained in the files themselves; they are the
only comments kept in this snapshot.

## `app-tv/src/main/kotlin/dev/infusion/tv/ui/screens/TvPlayerOverlay.kt`

The playback overlay is materially adapted, in fission's own design tokens, from
the JetStream sample:

- **android/tv-samples** — `JetStreamCompose`, <https://github.com/android/tv-samples/tree/main/JetStreamCompose>
- Copyright 2023 Google LLC
- Licensed under the Apache License, Version 2.0

The file's header notice states the adaptation. The Apache License 2.0 text is
reproduced in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) §3.1.

## `core/playback-media3/src/main/kotlin/dev/infusion/core/playback/media3/Media3FrameCadenceEstimator.kt`

A Kotlin port of the frame-cadence inference used by AndroidX Media3
(`FixedFrameRateEstimator`):

- Copyright 2022 The Android Open Source Project
- Licensed under the Apache License, Version 2.0

The upstream notice is retained verbatim in the file. AndroidX Media3 itself is
credited in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) §1, and the Apache
License 2.0 text in §3.1.

## `gradlew`, `gradlew.bat`

The Gradle wrapper scripts are the upstream Gradle scripts, distributed under the
Apache License, Version 2.0:

- Copyright 2015-2021 the original authors
- Licensed under the Apache License, Version 2.0
- SPDX-License-Identifier: Apache-2.0

Their upstream notice blocks are retained verbatim.

## Everything else

The remaining source in this snapshot carries no third-party source attribution.
Development commentary has been removed from this publication snapshot; executable
code, string literals, resource values and build configuration are unchanged.
