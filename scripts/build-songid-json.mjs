#!/usr/bin/env node

import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { ADX_ATTRIBUTION, fetchJSON, isRecord, writeJSONAtomically } from "./json-build-utils.mjs";

// The website's search index is generated from this public catalog. Read it
// directly so website access restrictions cannot break the scheduled build.
const SOURCE_URL = "https://raw.githubusercontent.com/AdingApkgg/adx-dl/main/data/catalog/index.json";
const repositoryRoot = fileURLToPath(new URL("..", import.meta.url));
const outputPath = resolve(process.argv[2] ?? repositoryRoot, process.argv[2] ? "" : "songid.json");

const source = await fetchJSON(SOURCE_URL);
if (!isRecord(source) || !Array.isArray(source.entries)) {
	throw new TypeError("ADX catalog must contain an entries array");
}

const seenIds = new Set();
const songs = source.entries.map((item, index) => {
	if (!isRecord(item)) {
		throw new TypeError(`Catalog entry ${index} must be an object`);
	}

	const id = typeof item.slug === "string" && /^\d+$/u.test(item.slug) ? Number(item.slug) : NaN;
	const name = typeof item.title === "string" ? item.title.trim() : "";
	if (!Number.isSafeInteger(id) || id <= 0) {
		throw new TypeError(`Catalog entry ${index} has an invalid slug`);
	}
	if (!name) {
		throw new TypeError(`Catalog entry ${index} has an empty title`);
	}
	if (seenIds.has(id)) {
		throw new Error(`Duplicate song ID ${id}`);
	}

	seenIds.add(id);
	return { id, name };
});

songs.sort((left, right) => left.id - right.id);
await writeJSONAtomically(outputPath, songs);

console.log(`Wrote ${songs.length} songs to ${outputPath}`);
console.log(`Source: ${ADX_ATTRIBUTION}`);
