import { createHash } from "node:crypto";
import { mkdtemp, readFile, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { mirrorChartAssets, parseChartCatalog, verifyChartAssets } from "../src/chart-assets.js";

const response = (body: string | object, headers: Record<string, string> = {}) =>
	new Response(typeof body === "string" ? body : JSON.stringify(body), { status: 200, headers });

describe("chart asset catalog validation", () => {
	it("accepts only trusted numeric chart IDs and media hosts", () => {
		const entries = parseChartCatalog({
			entries: [
				{
					slug: "30",
					versionid: 21,
					title: "Song",
					files: {
						maidata: "https://astrodx-charts-alice.saop.cc/21/30/maidata.txt",
						audio: "https://astrodx-charts.saop.cc/21/30/track.mp3",
					},
				},
			],
		});
		expect(entries[0]?.id).toBe(30);
		expect(() =>
			parseChartCatalog({ entries: [{ slug: "30", versionid: 21, files: { audio: "https://example.com/a.mp3" } }] }),
		).toThrow("Unexpected audio URL");
		expect(() =>
			parseChartCatalog({
				entries: [
					{
						slug: "30",
						versionid: 21,
						files: {
							maidata: "https://astrodx-charts-alice.saop.cc/21/30/maidata.txt",
							audio: "https://astrodx-charts.saop.cc/21/30/track.mp3",
						},
					},
					{
						slug: "30",
						versionid: 21,
						files: {
							maidata: "https://astrodx-charts-alice.saop.cc/21/30/maidata.txt",
							audio: "https://astrodx-charts.saop.cc/21/30/track.mp3",
						},
					},
				],
			}),
		).toThrow("Duplicate chart ID 30");
	});
});

describe("chart asset mirroring", () => {
	it("writes content-addressed files and reuses validated cache metadata", async () => {
		const root = await mkdtemp(path.join(tmpdir(), "maimaid-chart-assets-"));
		const publicDirectory = path.join(root, "public", "chart-assets");
		const cacheDirectory = path.join(root, "cache");
		const catalogUrl = "https://catalog.test/index.json";
		const maidataUrl = "https://astrodx-charts-alice.saop.cc/21/30/maidata.txt";
		const audioUrl = "https://astrodx-charts.saop.cc/21/30/track.mp3";
		const maidata = "&title=Song;\n";
		const audio = "audio bytes";
		let gets = 0;
		const fetcher: typeof fetch = async (url, init) => {
			const method = init?.method ?? "GET";
			if (url === catalogUrl)
				return response({
					entries: [{ slug: "30", versionid: 21, title: "Song", files: { maidata: maidataUrl, audio: audioUrl } }],
				});
			const body = url === maidataUrl ? maidata : audio;
			const headers = { etag: `"${url === maidataUrl ? "m1" : "a1"}"`, "content-length": String(Buffer.byteLength(body)) };
			if (method === "HEAD") return response("", headers);
			gets += 1;
			return response(body, headers);
		};

		const first = await mirrorChartAssets({ catalogUrl, publicDirectory, cacheDirectory, fetcher, concurrency: 1 });
		expect(first.index.entries[0]?.files.maidata?.sha256).toBe(createHash("sha256").update(maidata).digest("hex"));
		const firstGets = gets;
		await mirrorChartAssets({ catalogUrl, publicDirectory, cacheDirectory, fetcher, concurrency: 1 });
		expect(gets).toBe(firstGets);
		const stalePath = path.join(root, "public", "chart-assets", "files", "stale.txt");
		await writeFile(stalePath, "stale");
		await mirrorChartAssets({ catalogUrl, publicDirectory, cacheDirectory, fetcher, concurrency: 1 });
		await expect(stat(stalePath)).rejects.toThrow();
		await verifyChartAssets({ publicDirectory });
		await expect(verifyChartAssets({ publicDirectory, maxFiles: 2 })).rejects.toThrow("exceeds limit 2");
		await expect(stat(path.join(publicDirectory, "index.json"))).resolves.toBeTruthy();
		await expect(readFile(path.join(cacheDirectory, "source-cache.json"), "utf8")).resolves.toContain('"schemaVersion": 1');
	});

	it("refreshes changed validators and does not reuse assets without remote validators", async () => {
		const root = await mkdtemp(path.join(tmpdir(), "maimaid-chart-asset-refresh-"));
		const publicDirectory = path.join(root, "public", "chart-assets");
		const cacheDirectory = path.join(root, "cache");
		const catalogUrl = "https://catalog.test/index.json";
		const maidataUrl = "https://astrodx-charts-alice.saop.cc/21/30/maidata.txt";
		const audioUrl = "https://astrodx-charts.saop.cc/21/30/track.mp3";
		const catalog = {
			entries: [{ slug: "30", versionid: 21, title: "Song", files: { maidata: maidataUrl, audio: audioUrl } }],
		};
		let body = "first version";
		let etag = '"v1"';
		let getCount = 0;
		const fetcher: typeof fetch = async (url, init) => {
			if (url === catalogUrl) return response(catalog);
			if ((init?.method ?? "GET") === "HEAD") {
				return response("", etag ? { etag, "content-length": String(Buffer.byteLength(body)) } : {});
			}
			getCount += 1;
			return response(body, etag ? { etag, "content-length": String(Buffer.byteLength(body)) } : {});
		};
		const options = { catalogUrl, publicDirectory, cacheDirectory, fetcher, concurrency: 1 };

		const first = await mirrorChartAssets(options);
		const originalPath = first.index.entries[0]?.files.maidata?.path;
		body = "changed version";
		etag = '"v2"';
		const second = await mirrorChartAssets(options);
		expect(second.index.entries[0]?.files.maidata?.path).not.toBe(originalPath);
		expect(getCount).toBe(4);

		etag = "";
		await mirrorChartAssets(options);
		await mirrorChartAssets(options);
		expect(getCount).toBe(8);
	});

	it("leaves the published index unchanged when a referenced asset fails", async () => {
		const root = await mkdtemp(path.join(tmpdir(), "maimaid-chart-asset-failure-"));
		const publicDirectory = path.join(root, "public", "chart-assets");
		const cacheDirectory = path.join(root, "cache");
		const catalogUrl = "https://catalog.test/index.json";
		const maidataUrl = "https://astrodx-charts-alice.saop.cc/21/30/maidata.txt";
		const audioUrl = "https://astrodx-charts.saop.cc/21/30/track.mp3";
		let fail = false;
		let etag = '"v1"';
		const fetcher: typeof fetch = async (url, init) => {
			if (url === catalogUrl)
				return response({
					entries: [{ slug: "30", versionid: 21, title: "Song", files: { maidata: maidataUrl, audio: audioUrl } }],
				});
			if ((init?.method ?? "GET") === "HEAD") return response("", { etag, "content-length": "5" });
			return fail ? new Response("upstream failure", { status: 503 }) : response("chart", { etag, "content-length": "5" });
		};
		const options = { catalogUrl, publicDirectory, cacheDirectory, fetcher, concurrency: 1 };
		await mirrorChartAssets(options);
		const oldIndex = await readFile(path.join(publicDirectory, "index.json"), "utf8");
		fail = true;
		etag = '"v2"';
		await expect(mirrorChartAssets(options)).rejects.toThrow("Failed to mirror 2 chart asset");
		expect(await readFile(path.join(publicDirectory, "index.json"), "utf8")).toBe(oldIndex);
	});
});
