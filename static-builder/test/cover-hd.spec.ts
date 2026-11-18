import { mkdir, mkdtemp, readFile, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { chartIdCandidates, imageSizeOf, promoteEmbeddedCovers } from "../src/cover-hd.js";

const png = (width: number, height: number): Buffer => {
	const bytes = Buffer.alloc(24);
	Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(bytes, 0);
	bytes.writeUInt32BE(13, 8);
	bytes.write("IHDR", 12, "latin1");
	bytes.writeUInt32BE(width, 16);
	bytes.writeUInt32BE(height, 20);
	return bytes;
};

const apicFrame = (image: Buffer): Buffer => {
	const body = Buffer.concat([Buffer.from([0]), Buffer.from("image/png\0", "latin1"), Buffer.from([3, 0]), image]);
	const header = Buffer.alloc(10);
	header.write("APIC", 0, "latin1");
	const size = body.length;
	header[4] = ((size >> 21) & 0x7f) | 0;
	header[5] = (size >> 14) & 0x7f;
	header[6] = (size >> 7) & 0x7f;
	header[7] = size & 0x7f;
	return Buffer.concat([header, body]);
};

const mp3WithCover = (image: Buffer): Buffer => {
	const frame = apicFrame(image);
	const tagHeader = Buffer.alloc(10);
	tagHeader.write("ID3", 0, "latin1");
	tagHeader[3] = 4;
	const size = frame.length;
	tagHeader[6] = (size >> 21) & 0x7f;
	tagHeader[7] = (size >> 14) & 0x7f;
	tagHeader[8] = (size >> 7) & 0x7f;
	tagHeader[9] = size & 0x7f;
	return Buffer.concat([tagHeader, frame, Buffer.alloc(512)]);
};

const setup = async (options: { cover: Buffer; embedded: Buffer | null; internalId?: number; type?: string }) => {
	const root = await mkdtemp(path.join(tmpdir(), "maimaid-cover-hd-"));
	const publicDirectory = path.join(root, "public");
	const chartAssetsDirectory = path.join(publicDirectory, "chart-assets");
	await mkdir(path.join(chartAssetsDirectory, "files"), { recursive: true });
	await mkdir(path.join(publicDirectory, "covers"), { recursive: true });
	await writeFile(path.join(publicDirectory, "covers", "a.png"), options.cover);
	const audioPath = "/chart-assets/files/audio.mp3";
	await writeFile(
		path.join(publicDirectory, audioPath.slice(1)),
		options.embedded ? mp3WithCover(options.embedded) : Buffer.alloc(1024),
	);
	await writeFile(
		path.join(chartAssetsDirectory, "index.json"),
		JSON.stringify({
			schemaVersion: 1,
			entries: [{ id: 10181, title: "Song", files: { audio: { path: audioPath, size: 1, sha256: "0".repeat(64) } } }],
		}),
	);
	const payload = {
		resources: {
			data_json: {
				songs: [
					{
						songId: "Song",
						imageName: "a.png",
						sheets: [{ internalId: options.internalId ?? 10181, type: options.type ?? "dx" }],
					},
				],
			},
		},
	};
	return { publicDirectory, chartAssetsDirectory, payload };
};

describe("embedded cover promotion", () => {
	it("maps sheet ids the same way the client does", () => {
		expect(chartIdCandidates(181, "dx")).toEqual([10181, 181]);
		expect(chartIdCandidates(10181, "std")).toEqual([10181, 181]);
		expect(chartIdCandidates(1181, "std")).toEqual([1181, 11181]);
		expect(chartIdCandidates(null, "dx")).toEqual([]);
	});

	it("reads png and jpeg dimensions from headers", () => {
		expect(imageSizeOf(png(640, 480))).toEqual({ width: 640, height: 480 });
		const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xc0, 0x00, 0x11, 0x08, 0x01, 0x2c, 0x01, 0x90, 0x00]);
		expect(imageSizeOf(jpeg)).toEqual({ width: 400, height: 300 });
	});

	it("replaces a smaller catalog cover with the embedded art", async () => {
		const { publicDirectory, chartAssetsDirectory, payload } = await setup({
			cover: png(100, 100),
			embedded: png(300, 300),
		});
		const result = await promoteEmbeddedCovers(payload, { outputDirectory: publicDirectory, chartAssetsDirectory });

		expect(result.replaced).toBe(1);
		expect(result.published).toHaveLength(1);
		const song = (payload.resources.data_json.songs as Array<{ imageName: string }>)[0];
		expect(song?.imageName).toMatch(/^hd-[0-9a-f]{64}\.png$/u);
		expect(result.published[0]?.name).toBe(song?.imageName);
		const written = await readFile(path.join(publicDirectory, "covers", song!.imageName));
		expect(imageSizeOf(written)).toEqual({ width: 300, height: 300 });
		await expect(stat(path.join(publicDirectory, "covers", "a.png"))).resolves.toBeTruthy();
	});

	it("keeps the catalog cover when it is already sharper", async () => {
		const { publicDirectory, chartAssetsDirectory, payload } = await setup({
			cover: png(600, 600),
			embedded: png(300, 300),
		});
		const result = await promoteEmbeddedCovers(payload, { outputDirectory: publicDirectory, chartAssetsDirectory });

		expect(result.replaced).toBe(0);
		const song = (payload.resources.data_json.songs as Array<{ imageName: string }>)[0];
		expect(song?.imageName).toBe("a.png");
	});

	it("leaves songs without embedded art alone", async () => {
		const { publicDirectory, chartAssetsDirectory, payload } = await setup({
			cover: png(100, 100),
			embedded: null,
		});
		const result = await promoteEmbeddedCovers(payload, { outputDirectory: publicDirectory, chartAssetsDirectory });

		expect(result.replaced).toBe(0);
		expect(result.checked).toBe(0);
		const song = (payload.resources.data_json.songs as Array<{ imageName: string }>)[0];
		expect(song?.imageName).toBe("a.png");
	});

	it("resolves dx ids when the sheet only carries the base id", async () => {
		const { publicDirectory, chartAssetsDirectory, payload } = await setup({
			cover: png(100, 100),
			embedded: png(300, 300),
			internalId: 181,
			type: "dx",
		});
		const result = await promoteEmbeddedCovers(payload, { outputDirectory: publicDirectory, chartAssetsDirectory });
		expect(result.replaced).toBe(1);
	});
});
