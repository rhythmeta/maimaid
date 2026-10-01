/**
 * Builds the public static-data tree consumed directly by mobile and web clients.
 * Reads public upstream sources directly; publication is owned by this repository.
 */
import { mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { collectStaticAssetCandidates, type StaticAssetCandidate } from "./assets.js";
import { composeBundlePayload } from "./bundle.js";
import { sources } from "./sources.js";
import { communityIndex } from "./community-index.js";

type StaticManifest = {
	schemaVersion: 1;
	version: string;
	md5: string;
	createdAt: string;
	bundle: string;
	assets: {
		coverBaseUrl: string;
		presetAvatarBaseUrl: string;
		coverFallbackBaseUrl: string;
		presetAvatarFallbackBaseUrl: string;
	};
};

const staticAssetsBaseUrl = (process.env.MAIMAID_STATIC_ASSETS_URL ?? "https://maimaid-assets.rhythmeta.org")
	.trim()
	.replace(/\/+$/u, "");
const currentDirectory = path.dirname(fileURLToPath(import.meta.url));
const outputDirectory = path.resolve(currentDirectory, "../../static-worker/public");
const requestTimeoutMs = 10 * 60_000;
const assetRequestTimeoutMs = 60_000;
const assetConcurrency = 12;

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

const downloadAsset = async (candidate: StaticAssetCandidate) => {
	const directory = candidate.kind === "cover" ? "covers" : "lxns-icons";
	const destination = path.join(outputDirectory, directory, candidate.name);
	await retry(async () => {
		const response = await fetch(candidate.sourceUrl, {
			headers: { accept: "image/*", "user-agent": "maimaid-static-bundle-builder" },
			signal: AbortSignal.timeout(assetRequestTimeoutMs),
		});
		if (!response.ok) throw new Error(`HTTP ${response.status} ${candidate.sourceUrl}`);
		const contentType = response.headers.get("content-type") ?? "";
		if (!contentType.toLocaleLowerCase().startsWith("image/")) {
			throw new Error(`Unexpected content type ${contentType || "unknown"}: ${candidate.sourceUrl}`);
		}
		const body = Buffer.from(await response.arrayBuffer());
		if (body.byteLength === 0) throw new Error(`Empty image: ${candidate.sourceUrl}`);
		await writeFile(destination, body);
	});
};

const writeStaticTree = async () => {
	console.log(`[build] ${sources.length} public sources`);
	const composed = await composeBundlePayload(sources, async () => ({ payload: { charts: {}, diff_data: {} }, meta: {} }));

	const createdAt = new Date().toISOString();
	const version = `bundle-${Date.parse(createdAt)}`;
	const bundlePath = `/bundles/${composed.md5}.json`;
	const manifest: StaticManifest = {
		schemaVersion: 1,
		version,
		md5: composed.md5,
		createdAt,
		bundle: bundlePath,
		assets: {
			coverBaseUrl: `${staticAssetsBaseUrl}/cdn-cgi/image/format=png/covers/`,
			presetAvatarBaseUrl: `${staticAssetsBaseUrl}/cdn-cgi/image/format=png/lxns-icons/`,
			coverFallbackBaseUrl: `${staticAssetsBaseUrl}/covers/`,
			presetAvatarFallbackBaseUrl: `${staticAssetsBaseUrl}/lxns-icons/`,
		},
	};
	const bundle = { version, md5: composed.md5, createdAt, payload: composed.payload, sourceMeta: composed.sourceMeta };

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
		writeFile(path.join(outputDirectory, "manifest.json"), JSON.stringify(manifest)),
		writeFile(path.join(outputDirectory, bundlePath.slice(1)), JSON.stringify(bundle)),
		writeFile(
			path.join(outputDirectory, "_headers"),
			"/*\n  Access-Control-Allow-Origin: *\n/community-index.json\n  Cache-Control: public, max-age=300\n/manifest.json\n  Cache-Control: public, max-age=60, must-revalidate\n/bundles/*\n  Cache-Control: public, max-age=31536000, immutable\n/covers/*\n  Cache-Control: public, max-age=31536000, immutable\n/lxns-icons/*\n  Cache-Control: public, max-age=31536000, immutable\n",
		),
	]);

	await writeFile(
		path.join(outputDirectory, "community-index.json"),
		JSON.stringify(communityIndex(composed.payload, staticAssetsBaseUrl)),
	);
	const candidates = process.env.SKIP_IMAGES === "1" ? [] : collectStaticAssetCandidates(composed.payload);
	const existingAssets = new Set<string>();
	for (const candidate of candidates) {
		const destination = path.join(outputDirectory, candidate.kind === "cover" ? "covers" : "lxns-icons", candidate.name);
		try {
			const metadata = await stat(destination);
			if (metadata.isFile() && metadata.size > 0) {
				existingAssets.add(`${candidate.kind}|${candidate.name}`);
			}
		} catch {
			// The cache may be empty or may not contain this newly referenced asset.
		}
	}
	const missingCandidates = candidates.filter((candidate) => !existingAssets.has(`${candidate.kind}|${candidate.name}`));
	let completed = 0;
	console.log(
		`[assets] reusing ${existingAssets.size}, downloading ${missingCandidates.length} of ${candidates.length} object(s)`,
	);
	await mapWithConcurrency(missingCandidates, assetConcurrency, async (candidate) => {
		await downloadAsset(candidate);
		completed += 1;
		if (completed % 100 === 0) console.log(`[assets] downloaded ${completed}/${candidates.length}`);
	});
	console.log(`[build] wrote ${outputDirectory}, md5=${composed.md5}`);
};

writeStaticTree().catch((error: unknown) => {
	console.error("[static-bundle] failed:", error instanceof Error ? error.message : error);
	process.exitCode = 1;
});
