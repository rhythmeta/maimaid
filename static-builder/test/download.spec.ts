import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { downloadFirstAvailableImage, downloadImageFromSource } from "../src/download.js";

const imageResponse = (bytes: number, contentType = "image/png") =>
	new Response(Buffer.alloc(bytes), { status: 200, headers: { "content-type": contentType } });

const stubFetch = (handler: (url: string) => Response | Promise<Response>) => {
	const spy = vi.spyOn(globalThis, "fetch");
	spy.mockImplementation(async (input) => handler(String(input)));
	return spy;
};

describe("image download fallback", () => {
	let directory: string;
	let destination: string;

	beforeEach(async () => {
		directory = await mkdtemp(path.join(tmpdir(), "maimaid-download-"));
		destination = path.join(directory, "cover.png");
	});

	afterEach(async () => {
		vi.restoreAllMocks();
		await rm(directory, { recursive: true, force: true });
	});

	it("writes the primary source without consulting the fallback", async () => {
		const fetchSpy = stubFetch(() => imageResponse(12));
		const onFallback = vi.fn();

		await downloadFirstAvailableImage({
			label: "cover cover.png",
			sourceUrls: ["https://primary.test/cover.png", "https://fallback.test/cover.jpg"],
			attempt: (url) => downloadImageFromSource(url, destination, 5_000),
			onFallback,
		});

		expect(fetchSpy).toHaveBeenCalledTimes(1);
		expect(onFallback).not.toHaveBeenCalled();
		expect((await readFile(destination)).byteLength).toBe(12);
	});

	it("falls back when the primary source is forbidden", async () => {
		const fetchSpy = stubFetch((url) =>
			url.startsWith("https://primary.test") ? new Response("denied", { status: 403 }) : imageResponse(34, "image/jpeg"),
		);
		const onFallback = vi.fn();

		await downloadFirstAvailableImage({
			label: "cover cover.png",
			sourceUrls: ["https://primary.test/cover.png", "https://fallback.test/cover.jpg"],
			attempt: (url) => downloadImageFromSource(url, destination, 5_000),
			onFallback,
		});

		expect(fetchSpy).toHaveBeenCalledTimes(2);
		expect(onFallback).toHaveBeenCalledWith("https://fallback.test/cover.jpg");
		expect((await readFile(destination)).byteLength).toBe(34);
	});

	it("reports every failure when no source yields an image", async () => {
		stubFetch(() => new Response("denied", { status: 403 }));

		await expect(
			downloadFirstAvailableImage({
				label: "cover cover.png",
				sourceUrls: ["https://primary.test/cover.png", "https://fallback.test/cover.jpg"],
				attempt: (url) => downloadImageFromSource(url, destination, 5_000),
			}),
		).rejects.toThrow(/Failed to download cover cover\.png: .*403.*403/u);
	});

	it("rejects a non-image response and an empty body", async () => {
		stubFetch(() => new Response("<html></html>", { status: 200, headers: { "content-type": "text/html" } }));
		await expect(downloadImageFromSource("https://primary.test/cover.png", destination, 5_000)).rejects.toThrow(
			/Unexpected content type text\/html/u,
		);

		stubFetch(() => imageResponse(0));
		await expect(downloadImageFromSource("https://primary.test/cover.png", destination, 5_000)).rejects.toThrow(
			/Empty image/u,
		);
	});
});
