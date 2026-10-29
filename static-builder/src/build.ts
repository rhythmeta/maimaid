/**
 * Builds the public static-data tree consumed directly by mobile and web clients.
 * Reads public upstream sources directly; publication is owned by this repository.
 */
import { mkdir, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { collectStaticAssetCandidates, type StaticAssetCandidate } from "./assets.js";
import { composeBundlePayload } from "./bundle.js";
import { communityIndex } from "./community-index.js";
import { downloadFirstAvailableImage, downloadImageFromSource } from "./download.js";
import {
	assetDestination,
	createStaticManifest,
	formatByteSize,
	sumStaticAssetBytes,
	type StaticManifestAssets,
} from "./manifest.js";
import { sources } from "./sources.js";

const staticAssetsBaseUrl = (process.env.MAIMAID_STATIC_ASSETS_URL ?? "https://maimaid-assets.rhythmeta.org")
	.trim()
	.replace(/\/+$/u, "");
const currentDirectory = path.dirname(fileURLToPath(import.meta.url));
const outputDirectory = path.resolve(currentDirectory, "../../static-worker/public");
const assetRequestTimeoutMs = 60_000;
const assetConcurrency = 12;
const headersContent =
	"/*\n  Access-Control-Allow-Origin: *\n/community-index.json\n  Cache-Control: public, max-age=300\n/manifest.json\n  Cache-Control: public, max-age=60, must-revalidate\n/bundles/*\n  Cache-Control: public, max-age=31536000, immutable\n/covers/*\n  Cache-Control: public, max-age=31536000, immutable\n/lxns-icons/*\n  Cache-Control: public, max-age=31536000, immutable\n";

const mapWithConcurrency = async <T>(items: T[], limit: number, operation: (item: T) => Promise<void>) => {
	let cursor = 0;
	const worker = async () => {
		while (cursor < items.length) {
			const index = cursor;
			cursor += 1;
			await operation(items[index] as T);
		}
	};
	await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
};

const retry = async (operation: () => Promise<void>, attempts = 3) => {
	let lastError: unknown;
	for (let attempt = 1; attempt <= attempts; attempt += 1) {
		try {
			await operation();
			return;
		} catch (error) {
			lastError = error;
			if (attempt < attempts) await new Promise((resolve) => setTimeout(resolve, attempt * 500));
		}
	}
	throw lastError;
};

/**
 * Tries every source of the candidate in order. The dxrating fallback returns JPEG bytes that are
 * stored under the original `.png` name, because the published path is derived from the catalog's
 * `imageName`; every client sniffs the format from the content, so the mismatch is harmless.
 */
const downloadAsset = async (candidate: StaticAssetCandidate) => {
	const destination = assetDestination(outputDirectory, candidate);
	await downloadFirstAvailableImage({
		label: `${candidate.kind} ${candidate.name}`,
		sourceUrls: candidate.sourceUrls,
		attempt: (sourceUrl) => retry(() => downloadImageFromSource(sourceUrl, destination, assetRequestTimeoutMs)),
		onFallback: (sourceUrl) => console.log(`[assets] recovered ${candidate.name} from ${sourceUrl}`),
	});
};

const writeStaticTree = async () => {
	console.log(`[build] ${sources.length} public sources`);
	const composed = await composeBundlePayload(sources, async () => ({ payload: { charts: {}, diff_data: {} }, meta: {} }));

	const createdAt = new Date().toISOString();
	const version = `bundle-${Date.parse(createdAt)}`;
	const bundlePath = `/bundles/${composed.md5}.json`;
	const bundle = { version, md5: composed.md5, createdAt, payload: composed.payload, sourceMeta: composed.sourceMeta };
	// Stringify once so the recorded size describes exactly the bytes that get written.
	const bundleJson = JSON.stringify(bundle);
	const bundleBytes = Buffer.byteLength(bundleJson, "utf8");
	const assets: StaticManifestAssets = {
		coverBaseUrl: `${staticAssetsBaseUrl}/cdn-cgi/image/format=png/covers/`,
		presetAvatarBaseUrl: `${staticAssetsBaseUrl}/cdn-cgi/image/format=png/lxns-icons/`,
		coverFallbackBaseUrl: `${staticAssetsBaseUrl}/covers/`,
		presetAvatarFallbackBaseUrl: `${staticAssetsBaseUrl}/lxns-icons/`,
	};

	await Promise.all([
		rm(path.join(outputDirectory, "manifest.json"), { force: true }),
		rm(path.join(outputDirectory, "bundles"), { recursive: true, force: true }),
		rm(path.join(outputDirectory, "_headers"), { force: true }),
	]);
	await Promise.all([
		mkdir(path.join(outputDirectory, "bundles"), { recursive: true }),
		mkdir(path.join(outputDirectory, "covers"), { recursive: true }),
		mkdir(path.join(outputDirectory, "lxns-icons"), { recursive: true }),
	]);
	await Promise.all([
		writeFile(path.join(outputDirectory, bundlePath.slice(1)), bundleJson),
		writeFile(path.join(outputDirectory, "_headers"), headersContent),
	]);
	await writeFile(
		path.join(outputDirectory, "community-index.json"),
		JSON.stringify(communityIndex(composed.payload, staticAssetsBaseUrl)),
	);

	const candidates = collectStaticAssetCandidates(composed.payload);
	const skipImages = process.env.SKIP_IMAGES === "1";
	const existingAssets = new Set<string>();
	for (const candidate of candidates) {
		try {
			const metadata = await stat(assetDestination(outputDirectory, candidate));
			if (metadata.isFile() && metadata.size > 0) {
				existingAssets.add(`${candidate.kind}|${candidate.name}`);
			}
		} catch {
			// The cache may be empty or may not contain this newly referenced asset.
		}
	}
	const missingCandidates = candidates.filter((candidate) => !existingAssets.has(`${candidate.kind}|${candidate.name}`));
	if (skipImages) {
		console.log(`[assets] SKIP_IMAGES=1, reusing ${existingAssets.size} of ${candidates.length} object(s)`);
	} else {
		let completed = 0;
		console.log(
			`[assets] reusing ${existingAssets.size}, downloading ${missingCandidates.length} of ${candidates.length} object(s)`,
		);
		await mapWithConcurrency(missingCandidates, assetConcurrency, async (candidate) => {
			await downloadAsset(candidate);
			completed += 1;
			if (completed % 100 === 0) console.log(`[assets] downloaded ${completed}/${candidates.length}`);
		});
	}

	// The manifest is written last so it can carry the measured sizes. `wrangler deploy` only runs
	// after this script succeeds, and the Worker publishes the directory as one version, so clients
	// never observe a manifest that points at assets which have not landed yet.
	const imageBytes = await sumStaticAssetBytes(outputDirectory, candidates);
	if (imageBytes === null && !skipImages) {
		throw new Error("Failed to measure static asset sizes after downloading every candidate.");
	}
	const manifest = createStaticManifest({
		version,
		md5: composed.md5,
		createdAt,
		bundlePath,
		assets,
		bundleBytes,
		imageBytes,
	});
	await writeFile(path.join(outputDirectory, "manifest.json"), JSON.stringify(manifest));
	if (manifest.sizes) {
		console.log(
			`[build] sizes bundle=${formatByteSize(manifest.sizes.bundleBytes)} images=${formatByteSize(manifest.sizes.imageBytes)} total=${formatByteSize(manifest.sizes.totalBytes)}`,
		);
	} else {
		console.log("[build] sizes unavailable (image tree incomplete), omitted from manifest");
	}
	console.log(`[build] wrote ${outputDirectory}, md5=${composed.md5}`);
};

writeStaticTree().catch((error: unknown) => {
	console.error("[static-bundle] failed:", error instanceof Error ? error.message : error);
	process.exitCode = 1;
});
