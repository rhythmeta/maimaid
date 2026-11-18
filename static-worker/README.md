# maimaid static assets

`static-builder/src/build.ts` generates this Worker's `public/` tree.
The deployment is atomic: clients read `manifest.json`, then download its
content-addressed bundle and image assets from the same Worker origin.
Image URLs use Cloudflare's `/cdn-cgi/image/format=png/` transformation and
retain the original Worker paths as fallbacks. Native clients request PNG
directly.

The scheduled static bundle workflow also mirrors the chart preview assets
described by the ADX catalog. `chart-assets/index.json` is a separate lookup
table; it maps each stable chart ID to content-addressed `maidata.txt` and
audio paths and records the source URL, byte count, and SHA-256 digest. Clients
can fetch those files on demand. The current app bundle does not download them
during catalog synchronization.

Source audio arrives as MP3. Android's MP3 extractor seeks with the Xing/Info
table of contents whenever one is present, and that table stores a byte
position per percent of the track in 1/256 steps, so the byte it lands on can
be a few hundred milliseconds away from the requested time while the extractor
still stamps the buffers with the requested time. The chart preview clock
follows that timestamp, so the music drifts away from the chart after every
scrub. Without the metadata frame the extractor uses its CBR path instead: it
estimates the byte offset from the bitrate and then rewrites the timestamp
from the frame it actually landed on, which keeps audio and position within one
frame.

The mirror therefore removes the leading Xing/Info/VBRI frame from every
constant-bitrate track before publishing it; everything else stays
byte-identical. Variable-bitrate files keep their metadata frame because the
CBR estimate would be wrong for them. Removing the frame also removes about
24 ms of silent encoder padding, so durations shrink by one frame. The recipe
version is stored in the CI cache, so changing it re-publishes every track
exactly once.

Chart assets are fetched from the ADX Alice catalog and media hosts. The build
keeps source metadata in a separate CI cache and reuses a local file only when
the upstream ETag, Last-Modified, and Content-Length metadata still match.
Changed or unverifiable files are downloaded to a temporary path, verified,
hashed, and atomically moved into their content-addressed path. The published
index is written only after every referenced asset passes validation; files no
longer present upstream are omitted from the next deployment.

Community chart audio often carries the jacket art in its ID3v2 tag. The
build matches every catalog song to its chart audio, decodes the embedded
picture (front cover first) and, when it is larger than the catalog cover,
publishes it as `covers/hd-<sha256>.png|jpg` and rewrites the song's
`imageName` to that file. The `hd-` prefix plus the content hash is the marker:
clients download the sharper cover once and no later build has to inspect that
track again. Songs without chart audio, without an embedded picture, or with a
cover that is already sharper keep their existing `imageName`. The rewrite
happens before the bundle is serialized, so its md5, the community index and
the manifest sizes all describe the published tree.

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
