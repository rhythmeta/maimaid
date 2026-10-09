import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

const run = promisify(execFile);
const scriptUrl = new URL("../../scripts/build-songid-json.mjs", import.meta.url).href;
const catalogUrl = "https://raw.githubusercontent.com/AdingApkgg/adx-dl/main/data/catalog/index.json";

describe("song ID generation", () => {
	let directory: string;
	let outputPath: string;

	beforeEach(async () => {
		directory = await mkdtemp(path.join(tmpdir(), "maimaid-songid-"));
		outputPath = path.join(directory, "songid.json");
	});

	afterEach(async () => {
		await rm(directory, { recursive: true, force: true });
	});

	const generate = (catalog: unknown, status = 200) =>
		run(process.execPath, [
			"--input-type=module",
			"--eval",
			`
			globalThis.fetch = async (url) => {
				if (url !== ${JSON.stringify(catalogUrl)}) throw new Error("Unexpected source: " + url);
				return new Response(${JSON.stringify(JSON.stringify(catalog))}, { status: ${status} });
			};
			process.argv[2] = ${JSON.stringify(outputPath)};
			await import(${JSON.stringify(scriptUrl)});
			`,
		]);

	it("reads the public catalog and preserves sorted standard, DX, and Utage IDs", async () => {
		await generate({
			entries: [
				{ id: "100001-utage", slug: "100001", title: "宴曲" },
				{ id: "10030-dx", slug: "10030", title: " DX song " },
				{ id: "30-standard", slug: "30", title: "Standard song" },
			],
		});
		expect(JSON.parse(await readFile(outputPath, "utf8"))).toEqual([
			{ id: 30, name: "Standard song" },
			{ id: 10030, name: "DX song" },
			{ id: 100001, name: "宴曲" },
		]);
	});

	it.each([
		[[], "ADX catalog must contain an entries array"],
		[{ entries: [null] }, "Catalog entry 0 must be an object"],
		[{ entries: [{ slug: "bad", title: "Song" }] }, "invalid slug"],
		[{ entries: [{ slug: "0", title: "Song" }] }, "invalid slug"],
		[{ entries: [{ slug: "9007199254740992", title: "Song" }] }, "invalid slug"],
		[{ entries: [{ slug: "30", title: " " }] }, "empty title"],
		[
			{
				entries: [
					{ slug: "30", title: "Song" },
					{ slug: "30", title: "Duplicate" },
				],
			},
			"Duplicate song ID 30",
		],
	])("rejects malformed catalog %j without replacing existing output", async (catalog, message) => {
		await writeFile(outputPath, "previous output");
		await expect(generate(catalog)).rejects.toThrow(message as string);
		expect(await readFile(outputPath, "utf8")).toBe("previous output");
	});

	it("fails on upstream HTTP errors without replacing existing output", async () => {
		await writeFile(outputPath, "previous output");
		await expect(generate({}, 403)).rejects.toThrow("HTTP 403");
		expect(await readFile(outputPath, "utf8")).toBe("previous output");
	});
});
