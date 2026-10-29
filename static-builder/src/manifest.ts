/**
 * Shape and size accounting for `manifest.json`, split out of `build.ts` so it can be unit tested.
 * `build.ts` runs as a top-level side effect and cannot be imported from a test.
 */
import { stat } from "node:fs/promises";
import path from "node:path";
import type { StaticAssetCandidate } from "./assets.js";

export type StaticManifestAssets = {
	coverBaseUrl: string;
	presetAvatarBaseUrl: string;
	coverFallbackBaseUrl: string;
	presetAvatarFallbackBaseUrl: string;
};

/**
 * Everything a first-run client has to download, measured from the bytes that are actually stored
 * in the published tree. Clients fetch covers through the Cloudflare image transform, so the bytes
 * they receive differ slightly from these stored sizes; the totals are a stable upper estimate
 * rather than an exact transfer count.
 */
export type StaticManifestSizes = {
	bundleBytes: number;
	imageBytes: number;
	totalBytes: number;
};

export type StaticManifest = {
	schemaVersion: 1;
	version: string;
	md5: string;
	createdAt: string;
	bundle: string;
	assets: StaticManifestAssets;
	sizes?: StaticManifestSizes;
};

export type StaticManifestInput = {
	version: string;
	md5: string;
	createdAt: string;
	bundlePath: string;
	assets: StaticManifestAssets;
	bundleBytes: number;
	/** `null` when the image tree could not be measured, in which case `sizes` is omitted. */
	imageBytes: number | null;
};

export const createStaticManifest = (input: StaticManifestInput): StaticManifest => {
	const manifest: StaticManifest = {
		schemaVersion: 1,
		version: input.version,
		md5: input.md5,
		createdAt: input.createdAt,
		bundle: input.bundlePath,
		assets: input.assets,
	};
	if (input.imageBytes !== null) {
		manifest.sizes = {
			bundleBytes: input.bundleBytes,
			imageBytes: input.imageBytes,
			totalBytes: input.bundleBytes + input.imageBytes,
		};
	}
	return manifest;
};

export const assetDirectoryName = (candidate: StaticAssetCandidate): string =>
	candidate.kind === "cover" ? "covers" : "lxns-icons";

export const assetDestination = (outputDirectory: string, candidate: StaticAssetCandidate): string =>
	path.join(outputDirectory, assetDirectoryName(candidate), candidate.name);

/**
 * Sums the on-disk size of every candidate. Returns `null` as soon as one file is missing or
 * unreadable, so callers can omit `sizes` instead of publishing an understated total.
 * Candidates are already deduplicated by `collectStaticAssetCandidates`.
 */
export const sumStaticAssetBytes = async (
	outputDirectory: string,
	candidates: StaticAssetCandidate[],
): Promise<number | null> => {
	if (candidates.length === 0) return null;
	let total = 0;
	for (const candidate of candidates) {
		try {
			const metadata = await stat(assetDestination(outputDirectory, candidate));
			if (!metadata.isFile() || metadata.size <= 0) return null;
			total += metadata.size;
		} catch {
			return null;
		}
	}
	return total;
};

/** Human-readable size for build logs only; not part of the wire format. */
export const formatByteSize = (bytes: number): string => {
	const units = ["B", "KB", "MB", "GB"];
	let value = bytes;
	let unitIndex = 0;
	while (value >= 1024 && unitIndex < units.length - 1) {
		value /= 1024;
		unitIndex += 1;
	}
	return `${unitIndex === 0 ? value : value.toFixed(1)}${units[unitIndex]}`;
};
