# Android CI updates

The app checks `https://assets.rhythmeta.org/android/ci/main/latest.json` on startup
and from Settings. Only a higher `versionCode` with a different Git commit is offered.
`MAIMAID_UPDATE_URL` is a Gradle property for overriding the endpoint.

The APK workflow signs all four ABI packages with the existing release key and
publishes **raw APKs** to the `maimaid-assets` R2 bucket using the existing
`CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` Actions secrets. The token needs
R2 object read/write/delete access to that bucket. No Cloudflare or GitHub credential
is embedded in the app.

Only the latest **main** build is retained in R2. Other branches use GitHub Artifacts.
Each publication stages six files in `android/ci/builds/<runId>-<attempt>/`: four APKs,
a versioned manifest, and the complete reachable Git commit history. The app uses
the history to show up to 20 commits since the installed build, including merges.
All public files are downloaded and hashed before switching `main/latest.json`.
After that switch is verified, the previous build is deleted. A temporary
`main/publication.json` journal lets the next run recover interrupted uploads or
cleanup. Deletion is restricted to generated CI build keys; other bucket content
is untouched. A failed publication preserves the last working build.

CI publication is serialized for main. Equal or older version codes do not replace
the pointer. Re-running the same commit therefore does not create another retained
build. The existing commit-count version numbering is preserved; main should not
be force-rewritten to a history with a smaller count.

The system DownloadManager continues downloads in the background. The app stores
its task in a separate DataStore excluded from backup and verifies file size,
SHA-256, package name, version code, and signing certificate compatibility before
opening Android's installer. Installation requires the user's system confirmation.
An already installed release APK cannot be updated with a locally debug-signed APK.

When a new publication replaces the only retained build, a previously offered
but not downloaded APK may become unavailable. The error dialog provides
**Check again** to fetch the current build. A completed, verified local download
remains installable until the app is updated or its download is canceled.

Validation:

```sh
node --test scripts/android-ci-update.test.mjs
cd android
./gradlew :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug
# On a compatible test device, with a matching app signature:
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.rhythmeta.maimaid.update.AppUpdateDialogTest
```

The device dialog test checks four locales, light/dark themes, long changelogs,
scrolling, and fixed action visibility, and writes PNGs to the app's external files
directory. It uses fixtures and does not publish an update.
