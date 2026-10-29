import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { collectStaticAssetCandidates } from "../src/assets.js";
import {
	assetDestination,
	createStaticManifest,
	formatByteSize,
	sumStaticAssetBytes,
	type StaticManifestAssets,
} from "../src/manifest.js";

const assets: StaticManifestAssets = {
	coverBaseUrl: "https://example.test/cdn-cgi/image/format=png/covers/",
	presetAvatarBaseUrl: "https://example.test/cdn-cgi/image/format=png/lxns-icons/",
	coverFallbackBaseUrl: "https://example.test/covers/",
	presetAvatarFallbackBaseUrl: "https://example.test/lxns-icons/",
};

const createManifest = (imageBytes: number | null, bundleBytes = 1_000) =>
	createStaticManifest({
		version: "bundle-1",
		md5: "0".repeat(32),
		createdAt: "2026-01-01T00:00:00.000Z",
		bundlePath: "/bundles/00000000000000000000000000000000.json",
		assets,
		bundleBytes,
		imageBytes,
	});

describe("static manifest sizes", () => {
	it("carries the combined bundle and image bytes", () => {
		const manifest = createManifest(250_000, 5_792_767);

		expect(manifest.sizes).toEqual({
			bundleBytes: 5_792_767,
			imageBytes: 250_000,
			totalBytes: 6_042_767,
		});
		expect(manifest.schemaVersion).toBe(1);
		expect(manifest.assets).toEqual(assets);
	});

	it("omits the sizes key entirely when the image tree could not be measured", () => {
		const manifest = createManifest(null);

		expect("sizes" in manifest).toBe(false);
		expect(JSON.parse(JSON.stringify(manifest))).not.toHaveProperty("sizes");
	});

	it("formats byte sizes for build logs", () => {
		expect(formatByteSize(512)).toBe("512B");
		expect(formatByteSize(1_536)).toBe("1.5KB");
		expect(formatByteSize(5_792_767)).toBe("5.5MB");
	});
});

describe("static asset size accounting", () => {
	let directory: string;
	let candidates: ReturnType<typeof collectStaticAssetCandidates>;

	beforeEach(async () => {
		directory = await mkdtemp(path.join(tmpdir(), "maimaid-manifest-"));
		candidates = collectStaticAssetCandidates({
			resources: {
				data_json: { songs: [{ imageName: "cover.png" }, { imageName: "cover.png" }] },
				lxns_icon_list: { icons: [{ id: 7 }] },
			},
		});
		for (const candidate of candidates) {
			const destination = assetDestination(directory, candidate);
			await mkdir(path.dirname(destination), { recursive: true });
			await writeFile(destination, Buffer.alloc(candidate.kind === "cover" ? 300 : 50));
		}
	});

	afterEach(async () => {
		await rm(directory, { recursive: true, force: true });
	});

	it("sums every deduplicated candidate", () => {
		expect(candidates).toHaveLength(2);
		return expect(sumStaticAssetBytes(directory, candidates)).resolves.toBe(350);
	});

	it("returns null when one candidate is missing", async () => {
		const [first] = candidates;
		if (first) await rm(assetDestination(directory, first), { force: true });

		await expect(sumStaticAssetBytes(directory, candidates)).resolves.toBeNull();
	});

	it("returns null for an empty candidate list", async () => {
		await expect(sumStaticAssetBytes(directory, [])).resolves.toBeNull();
	});
});
