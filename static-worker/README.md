# maimaid static assets

`static-builder/src/build.ts` generates this Worker's `public/` tree.
The deployment is atomic: clients read `manifest.json`, then download its
content-addressed bundle and image assets from the same Worker origin.
Image URLs use Cloudflare's `/cdn-cgi/image/format=png/` transformation and
retain the original Worker paths as fallbacks. Native clients request PNG
directly.

The scheduled static bundle workflow also mirrors the chart preview assets
described by the ADX catalog. `chart-assets/index.json` is a separate lookup
table; it maps each stable chart ID to content-addressed `maidata.txt` and MP3
paths and records the source URL, byte count, and SHA-256 digest. Clients can
fetch those files on demand. The current app bundle does not download them
during catalog synchronization.

Chart assets are fetched from the ADX Alice catalog and media hosts. The build
keeps source metadata in a separate CI cache and reuses a local file only when
the upstream ETag, Last-Modified, and Content-Length metadata still match.
Changed or unverifiable files are downloaded to a temporary path, verified,
hashed, and atomically moved into their content-addressed path. The published
index is written only after every referenced asset passes validation; files no
longer present upstream are omitted from the next deployment.

The chart mirror preserves the ADX catalog attribution in the index. The ADX
catalog's CC BY 4.0 license applies to its metadata; chart and audio files
remain subject to their original rights and the repository's media
redistribution authorization.

The first complete mirror is large (the current catalog is roughly 8.3 GB of
MP3 plus maidata). Later runs use the Actions cache and remote validators, so
only new or changed files are downloaded. The build rejects files over the
Workers Static Assets 25 MiB per-file limit and verifies the 20,000-file
per-version limit before deployment.

Required build environment:

- `MAIMAID_STATIC_ASSETS_URL`

Required GitHub Actions secrets:

- `MAIMAID_STATIC_ASSETS_URL`
- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`

Configure the `MAIMAID_STATIC_ASSETS_URL` hostname as this Worker's custom
domain. Run the repository workflow to generate and deploy independently of the API.
