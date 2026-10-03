# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

maimaid is a maimai DX player ecosystem app. This repository contains native iOS/Android clients and independent static catalog publication. The iOS app is the primary product — it handles score tracking, song catalog browsing, B50 calculation, image recognition-based score entry, and cloud sync. The Android app is the port of that same feature set.

## Monorepo Structure

- **`ios/maimaid/`** — iOS app (SwiftUI + SwiftData), the core product
- **`android/`** — Android app (Kotlin + Jetpack Compose + Room), standalone Gradle build
- **`static-builder/`** — Public upstream catalog builder
- **`static-worker/`** — Cloudflare static assets deployment
- **`shared/`** — Portable protobuf schemas and fixtures

Orchestrated with **Nx** and **pnpm workspaces** (`pnpm@10.33.0`). The pnpm workspace covers `static-builder`. The `android/` tree is a self-contained Gradle build and is not part of the pnpm workspace or Nx graph.

## Common Commands

### Root-level

```bash
pnpm install --frozen-lockfile
pnpm test:static
pnpm typecheck:static
pnpm build:static
pnpm run build:ios
```

### iOS

Use XcodeBuildMCP (via the xcodebuildmcp-cli skill) for building, testing, and running the iOS app. The Xcode project is at `ios/maimaid.xcodeproj/`. iOS secrets (BACKEND_URL, BACKEND_AUTH_URL) go in `ios/Config/Secrets.xcconfig` (gitignored).

### Android (from `android/`)

The Gradle root is `android/`, so run the wrapper from there — not from the repo root.

```bash
./gradlew assembleDebug           # Build debug APK
./gradlew installDebug            # Build and install on connected device/emulator
./gradlew test                    # JVM unit tests (app/src/test)
./gradlew connectedAndroidTest    # Instrumented tests (requires device/emulator)
./gradlew lint                    # Android lint
```

## Architecture

### iOS App (`ios/maimaid/`)

- **Target**: iOS 26.0+, Swift 6.2+, strict concurrency
- **Data layer**: SwiftData with models: `Song`, `Sheet`, `Score`, `PlayRecord`, `SyncConfig`, `MaimaiIcon`, `UserProfile`, `CommunityAliasCache`
- **Entry point**: `maimaidApp.swift` — sets up `ModelContainer`, recovers interrupted restores before opening the UI, and manages account/alias lifecycle checks
- **Views/**: Page-oriented SwiftUI features, aligned with Android's `ui` packages:
  `Home/`, `Catalog/`, `Best/`, `Song/`, `Collections/`, `Score/`, `ScoreQuery/`,
  `Recommendation/`, `ConstantTable/`, `Dan/`, `Plate/`, `Random/`, `Scanner/`,
  `Community/`, `Settings/`, and `Onboarding/`. Shared UI stays in
  `Components/`; app-level tab routing stays in `Navigation/`.
- **Services/**: Backend API client (`BackendAPIClient`), session management (`BackendSessionManager`), manual snapshots (`CloudBackupService`), local score uploads, data import from Diving Fish / LXNS, image recognition (`MLScoreProcessor`, `MLChooseProcessor`, `MLDistinguishProcessor`), community aliases
- **Localization**: `Localizable.strings` in `en`, `ja`, `zh-Hans`, `zh-Hant`. When adding user-facing strings, translate into all four languages.

### Android App (`android/`)

- **Target**: minSdk 28, targetSdk 37, compileSdk 37; JVM target 17; Kotlin 2.4.1, AGP 9.4.0
- **Toolchain**: `app/build.gradle.kts` pins `kotlin { jvmToolchain(17) }`, so the compile JDK does not depend on your `JAVA_HOME`. This is deliberate: AGP's `androidJdkImage` transform shells out to `jlink`, and GraalVM's `jlink` cannot process the Android platform's `core-for-system-modules.jar`. Without the pin, a GraalVM `JAVA_HOME` fails `:app:compileDebugJavaWithJavac`. Gradle auto-provisions a JDK 17 via the foojay resolver configured in `settings.gradle.kts` if you don't have one.
- **Package**: `org.rhythmeta.maimaid` (both `namespace` and `applicationId`)
- **UI**: Jetpack Compose (Compose BOM) + Miuix, Navigation Compose, Coil for images
- **Data layer**: Room (schemas exported to `app/schemas/` via KSP) + DataStore Preferences
- **Networking**: Retrofit + OkHttp + kotlinx.serialization; background work via WorkManager
- **Scanner**: CameraX for capture, ML Kit text recognition (Latin + Chinese + Japanese) for OCR, TFLite for classification/detection. Runtime models are packaged assets in `app/src/main/assets/scanner/`.
- **Build types**: `debug` (`.debug` suffix), `release`, and `snapshot` (`.snapshot` suffix, derived from release). All restricted to the `arm64-v8a` ABI.
- **Config**: `BACKEND_URL` and `BACKEND_AUTH_URL` are read as Gradle properties from `android/gradle.properties` and exposed through `BuildConfig`. Release signing reads the PKCS#12 keystore path, store password, alias, and key password from `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`; local release builds remain unsigned when these variables are absent.
- **Tests**: JVM unit tests in `app/src/test/` (JUnit4 + Truth + coroutines-test). Instrumented tests in `app/src/androidTest/`, including `ScannerSampleRegressionTest`, which reads the HEIC samples and `detail.json` in `android/mairesult/` — that directory is wired in as an androidTest asset source, so it is test fixture data, not scratch files.

### Shared Rhythmeta services

The backend and dashboard were extracted with history into `rhythmeta/gekichumai-backend` and `rhythmeta/gekichumai-dashboard`. Hono runs on Workers + D1 with public R2 snapshots; the Next.js dashboard runs on Workers Static Assets at `dash.rhythmeta.org`.

Authentication uses `/auth/v1` with PKCE S256. Game resources use `/maimaid/v1` or `/chunithmd/v1`. Legacy `/v1/*` is retired. Existing credentials and community aliases were migrated; cloud profiles, scores, imports, public collections and multiplayer were discarded.

Native apps remain local-first. Manual protobuf+gzip snapshots contain personal data and settings, exclude credentials/catalog assets, and preserve the latest three per game/account. Replacement restore uses a durable rollback journal. Static catalogs are built and published by each game's own repository directly from public upstreams.

## iOS Coding Guidelines

Detailed Swift/SwiftUI rules are in `AGENTS.md`. Key points:

- Use `@Observable` (not `ObservableObject`), mark with `@MainActor`
- Use modern Swift concurrency (async/await, never GCD)
- Use `FormatStyle` API (never legacy `Formatter` subclasses like `DateFormatter`)
- Use `foregroundStyle()` not `foregroundColor()`, `clipShape(.rect(cornerRadius:))` not `cornerRadius()`
- Use `NavigationStack` not `NavigationView`, `Tab` API not `tabItem()`
- Filter user input with `localizedStandardContains()` not `contains()`
- Break views into separate `View` structs, not computed properties
- No UIKit unless specifically needed
- No third-party frameworks without asking first
