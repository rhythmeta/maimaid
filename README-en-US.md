# maimaid

A local-first maimai DX toolkit for iOS and Android: song catalogs, profiles, scores, B50, progress, OCR, Diving Fish/LXNS imports and manual cloud backups.

## Repositories

This repository contains `ios/`, `android/`, `shared/` (portable protocols) and `static-builder/` + `static-worker/` (independent catalog publication).

Shared accounts and cloud services now live in [rhythmeta-backend](https://github.com/rhythmeta/rhythmeta-backend) (Cloudflare Workers + D1 + R2). The account website lives in [rhythmeta-dashboard](https://github.com/rhythmeta/rhythmeta-dashboard) and is available at [dash.rhythmeta.org](https://dash.rhythmeta.org).

## Development

Use pnpm 10, Xcode for iOS 26+, and JDK 17/Android SDK 37 for Android.

```sh
pnpm install --frozen-lockfile
pnpm test:static
pnpm typecheck:static
pnpm build:static
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The static workflow builds directly from public upstream data and deploys the static Worker. Backend availability is no longer required. Set the workflow's `MAIMAID_STATIC_ASSETS_URL`, `CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_API_TOKEN` secrets.

Clients use `https://api.rhythmeta.org` and `https://dash.rhythmeta.org`. Android supports `-PMAIMAID_BACKEND_URL=...` and `-PMAIMAID_BACKEND_AUTH_URL=...`; iOS supports `BACKEND_URL` and `BACKEND_AUTH_URL` in the ignored `ios/Config/Secrets.xcconfig`.

## Accounts and backups

Existing accounts remain valid; log in again after migration. Manual backups contain personal profiles, scores/history, collections, favorites and settings as protobuf + gzip in R2. Each game retains three snapshots. Restore replaces all local personal data and saves a recovery copy first. Catalog assets and credentials are excluded. Old per-row cloud sync, cloud imports, public collection storage and multiplayer have been retired. Local imports, score uploads and collection snapshot links remain available.

## Data and copyright

Thanks to Diving Fish, LXNS Coffee House and arcade-songs for community data. maimai and its assets/trademarks belong to SEGA. This project is independent and is not affiliated with SEGA.
