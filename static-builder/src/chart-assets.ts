import { createHash, randomUUID } from "node:crypto";
import { mkdir, readFile, readdir, rename, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { stripMp3MetadataFrame } from "./mp3-seekable.js";

export const CHART_CATALOG_URL = "https://raw.githubusercontent.com/AdingApkgg/adx-dl/main/data/catalog/index.json";
export const CHART_ATTRIBUTION = "ADX 谱面资源 — https://adxdls.saop.cc (catalog metadata: CC BY 4.0)";
export const MAX_STATIC_ASSET_BYTES = 25 * 1024 * 1024;
export const MAX_STATIC_ASSET_FILES = 20_000;

/**
 * Bump when the published audio recipe changes. Cached assets are reused only when the stored
 * pipeline version matches, so a new recipe re-publishes every track exactly once.
 *
 * Version 2 removes the leading Xing/Info metadata frame from constant-bitrate MP3s so the
 * Android extractor seeks with its byte-accurate CBR path instead of the coarse table of
 * contents. See [stripMp3MetadataFrame].
 */
export const CHART_ASSET_PIPELINE_VERSION = 2;

const MAIDATA_HOST = "astrodx-charts-alice.saop.cc";
const AUDIO_HOST = "astrodx-charts.saop.cc";
const CONCURRENCY = 16;
const OUTPUT_DIRECTORY = path.resolve(process.cwd(), "../static-worker/public/chart-assets");
const CACHE_DIRECTORY = path.resolve(process.cwd(), "../static-worker/.chart-assets-cache");

type SourceAsset = {
	type: "maidata" | "audio";
	url: string;
	pathExtension: "txt" | "mp3";
	expectedBytes: number | null;
};

type SourceEntry = {
	id: number;
	title: string;
	assets: SourceAsset[];
};

type RemoteMetadata = {
	etag: string | null;
	lastModified: string | null;
	contentLength: number | null;
};

type CachedAsset = RemoteMetadata & {
	sourceUrl: string;
	path: string;
	sha256: string;
	size: number;
	/** Published-file recipe that produced this entry. */
	pipeline?: number;
	/** True when the published audio is smaller than the source, i.e. its metadata frame was removed. */
	strippedAudio?: boolean;
};

/** Converts downloaded chart audio bytes into the published bytes. */
export type ChartAudioPreparer = (source: Buffer, sourceUrl: string) => Promise<Buffer>;

type CacheFile = { schemaVersion: 1; assets: Record<string, CachedAsset> };

export type PublishedAsset = {
	path: string;
	sourceUrl: string;
	size: number;
	sha256: string;
};

export type ChartAssetIndex = {
	schemaVersion: 1;
	generatedAt: string;
	source: {
		name: "ADX 谱面资源";
		catalogUrl: string;
		attribution: string;
		metadataLicense: "CC BY 4.0";
		mediaRights: "Original rights holders; redistributed under maimaid authorization.";
	};
	entries: Array<{
		id: number;
		title: string;
		files: Partial<Record<"maidata" | "audio", PublishedAsset>>;
	}>;
};

export type MirrorChartAssetsResult = {
	index: ChartAssetIndex;
	reused: number;
	downloaded: number;
	changed: boolean;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null && !Array.isArray(value);

const positiveBytes = (value: unknown): number | null =>
	typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? value : null;

const remoteMetadata = (headers: Headers): RemoteMetadata => {
	const contentLengthHeader = headers.get("content-length");
	const parsedLength = contentLengthHeader === null ? undefined : Number(contentLengthHeader);
	return {
		etag: headers.get("etag"),
		lastModified: headers.get("last-modified"),
		contentLength: parsedLength !== undefined && Number.isSafeInteger(parsedLength) && parsedLength > 0 ? parsedLength : null,
	};
};

const hasUsefulValidator = (metadata: RemoteMetadata): boolean =>
	metadata.contentLength !== null && (metadata.etag !== null || metadata.lastModified !== null);

const sameMetadata = (left: RemoteMetadata, right: RemoteMetadata): boolean =>
	left.etag === right.etag && left.lastModified === right.lastModified && left.contentLength === right.contentLength;

const compatibleMetadata = (expected: RemoteMetadata, observed: RemoteMetadata): boolean =>
	(expected.etag === null || observed.etag === null || expected.etag === observed.etag) &&
	(expected.lastModified === null || observed.lastModified === null || expected.lastModified === observed.lastModified) &&
	(expected.contentLength === null || observed.contentLength === null || expected.contentLength === observed.contentLength);

const assetKey = (id: number, type: SourceAsset["type"]): string => `${id}:${type}`;

const trustedAssetURL = (rawUrl: unknown, type: SourceAsset["type"]): URL => {
	if (typeof rawUrl !== "string" || !rawUrl.trim()) throw new TypeError(`Chart ${type} URL is empty`);
	const url = new URL(rawUrl);
	const expectedHost = type === "maidata" ? MAIDATA_HOST : AUDIO_HOST;
	const expectedExtension = type === "maidata" ? ".txt" : ".mp3";
	if (
		url.protocol !== "https:" ||
		url.hostname !== expectedHost ||
		url.username ||
		url.password ||
		url.search ||
		url.hash ||
		!url.pathname.toLowerCase().endsWith(expectedExtension) ||
		url.pathname.split("/").some((part) => part === "." || part === "..")
	) {
		throw new TypeError(`Unexpected ${type} URL: ${rawUrl}`);
	}
	return url;
};

export const parseChartCatalog = (raw: unknown): SourceEntry[] => {
	if (!isRecord(raw) || !Array.isArray(raw.entries)) throw new TypeError("ADX catalog must contain an entries array");
	if (raw.entries.length === 0) throw new TypeError("ADX catalog must not be empty");
	const seenIds = new Set<number>();
	const entries: SourceEntry[] = [];
	for (const [index, value] of raw.entries.entries()) {
		if (!isRecord(value)) throw new TypeError(`ADX catalog entry ${index} must be an object`);
		const rawId = typeof value.slug === "string" ? value.slug : "";
		if (!/^\d+$/u.test(rawId)) throw new TypeError(`ADX catalog entry ${index} has an invalid numeric slug`);
		const id = Number(rawId);
		if (!Number.isSafeInteger(id) || id <= 0) throw new TypeError(`ADX catalog entry ${index} has an invalid numeric slug`);
		const versionId = value.versionid;
		if (!Number.isSafeInteger(versionId) || Number(versionId) < 0) {
			throw new TypeError(`ADX catalog entry ${index} has an invalid versionid`);
		}
		if (seenIds.has(id)) throw new Error(`Duplicate chart ID ${id}`);
		seenIds.add(id);
		const title = typeof value.title === "string" && value.title.trim() ? value.title.trim() : String(id);
		const files = isRecord(value.files) ? value.files : {};
		const declaredSizes = isRecord(value.file_bytes) ? value.file_bytes : {};
		const assets: SourceAsset[] = [];
		for (const type of ["maidata", "audio"] as const) {
			const rawUrl = files[type];
			if (typeof rawUrl !== "string" || !rawUrl.trim()) continue;
			const url = trustedAssetURL(rawUrl, type);
			const segments = url.pathname.split("/").filter(Boolean);
			const expectedFile = type === "maidata" ? "maidata.txt" : "track.mp3";
			if (segments.length !== 3 || segments[0] !== String(versionId) || segments[1] !== rawId || segments[2] !== expectedFile) {
				throw new TypeError(`Unexpected ${type} path for chart ${id}: ${rawUrl}`);
			}
			assets.push({
				type,
				url: url.href,
				pathExtension: type === "maidata" ? "txt" : "mp3",
				expectedBytes: positiveBytes(declaredSizes[type]),
			});
		}
		if (assets.length !== 2) throw new TypeError(`ADX catalog entry ${id} must provide both maidata and audio assets`);
		entries.push({ id, title, assets });
	}
	return entries.sort((left, right) => left.id - right.id);
};

const readCache = async (cachePath: string): Promise<CacheFile> => {
	try {
		const value: unknown = JSON.parse(await readFile(cachePath, "utf8"));
		if (!isRecord(value) || value.schemaVersion !== 1 || !isRecord(value.assets)) return { schemaVersion: 1, assets: {} };
		return { schemaVersion: 1, assets: value.assets as Record<string, CachedAsset> };
	} catch {
		return { schemaVersion: 1, assets: {} };
	}
};

const writeJSONAtomically = async (filePath: string, value: unknown): Promise<void> => {
	await mkdir(path.dirname(filePath), { recursive: true });
	const temporaryPath = `${filePath}.${process.pid}.${randomUUID()}.tmp`;
	try {
		await writeFile(temporaryPath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
		await rename(temporaryPath, filePath);
	} catch (error) {
		await rm(temporaryPath, { force: true });
		throw error;
	}
};

const retry = async <T>(operation: () => Promise<T>, attempts = 3): Promise<T> => {
	let lastError: unknown;
	for (let attempt = 1; attempt <= attempts; attempt++) {
		try {
			return await operation();
		} catch (error) {
			lastError = error;
			if (attempt < attempts) await new Promise((resolve) => setTimeout(resolve, attempt * 500));
		}
	}
	throw lastError;
};

const hashFile = async (filePath: string): Promise<{ sha256: string; size: number }> => {
	const bytes = await readFile(filePath);
	return { sha256: createHash("sha256").update(bytes).digest("hex"), size: bytes.byteLength };
};

/** Drops the Xing/Info frame when the track is CBR; leaves anything else byte-identical. */
export const makeSeekableMp3: ChartAudioPreparer = async (source) => {
	const result = stripMp3MetadataFrame(source);
	if (result.status === "stripped") return result.bytes;
	if (result.reason === "not-cbr") {
		console.warn("[chart-assets] audio is not constant bitrate; keeping its Xing/Info frame");
	}
	return source;
};

const isValidCacheEntry = (value: unknown): value is CachedAsset => {
	if (!isRecord(value)) return false;
	return (
		typeof value.sourceUrl === "string" &&
		typeof value.path === "string" &&
		value.pipeline === CHART_ASSET_PIPELINE_VERSION &&
		/^chart-assets\/files\/[0-9a-f]{64}\.(txt|mp3)$/u.test(value.path) &&
		typeof value.sha256 === "string" &&
		/^[0-9a-f]{64}$/u.test(value.sha256) &&
		positiveBytes(value.size) !== null &&
		(typeof value.etag === "string" || value.etag === null) &&
		(typeof value.lastModified === "string" || value.lastModified === null) &&
		(value.contentLength === null || positiveBytes(value.contentLength) !== null)
	);
};

const cachedFilePath = (publicDirectory: string, value: CachedAsset): string =>
	path.join(path.dirname(publicDirectory), value.path);

const probeRemote = async (url: string, fetcher: typeof fetch): Promise<RemoteMetadata | null> => {
	const response = await retry(() => fetcher(url, { method: "HEAD", signal: AbortSignal.timeout(60_000) }));
	if (response.status === 404 || response.status === 410) throw new Error(`Upstream asset is missing: ${url}`);
	if (!response.ok) return null;
	return remoteMetadata(response.headers);
};

const verifyCachedFile = async (publicDirectory: string, value: CachedAsset): Promise<boolean> => {
	try {
		const filePath = cachedFilePath(publicDirectory, value);
		const metadata = await stat(filePath);
		if (!metadata.isFile() || metadata.size !== value.size) return false;
		const actual = await hashFile(filePath);
		return actual.size === value.size && actual.sha256 === value.sha256;
	} catch {
		return false;
	}
};

const mapConcurrent = async <T>(items: T[], limit: number, operation: (item: T) => Promise<void>): Promise<void> => {
	let cursor = 0;
	const worker = async () => {
		while (true) {
			const index = cursor++;
			if (index >= items.length) return;
			await operation(items[index] as T);
		}
	};
	await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
};

export type MirrorChartAssetsOptions = {
	catalogUrl?: string;
	publicDirectory?: string;
	cacheDirectory?: string;
	fetcher?: typeof fetch;
	concurrency?: number;
	/** Prepares downloaded audio before it is published. Defaults to [makeSeekableMp3]. */
	prepareAudio?: ChartAudioPreparer;
	/** Per-file limit of the static asset host. Defaults to [MAX_STATIC_ASSET_BYTES]. */
	maxAssetBytes?: number;
};

export const mirrorChartAssets = async (options: MirrorChartAssetsOptions = {}): Promise<MirrorChartAssetsResult> => {
	const catalogUrl = options.catalogUrl ?? CHART_CATALOG_URL;
	const outputDirectory = options.publicDirectory ?? OUTPUT_DIRECTORY;
	const cacheDirectory = options.cacheDirectory ?? CACHE_DIRECTORY;
	const fetcher = options.fetcher ?? fetch;
	const prepareAudio = options.prepareAudio ?? makeSeekableMp3;
	const maxAssetBytes = options.maxAssetBytes ?? MAX_STATIC_ASSET_BYTES;
	const concurrency =
		options.concurrency !== undefined && Number.isSafeInteger(options.concurrency) && options.concurrency > 0
			? options.concurrency
			: CONCURRENCY;
	const catalogResponse = await retry(() => fetcher(catalogUrl, { headers: { Accept: "application/json" } }));
	if (!catalogResponse.ok) throw new Error(`Failed to fetch ADX catalog: HTTP ${catalogResponse.status}`);
	const entries = parseChartCatalog(await catalogResponse.json());
	const cachePath = path.join(cacheDirectory, "source-cache.json");
	const oldCache = await readCache(cachePath);
	const nextCache: CacheFile = { schemaVersion: 1, assets: {} };
	const expectedCacheKeys = new Set<string>();
	const indexEntries = new Map<number, ChartAssetIndex["entries"][number]>();
	for (const entry of entries) indexEntries.set(entry.id, { id: entry.id, title: entry.title, files: {} });
	const tasks = entries.flatMap((entry) => entry.assets.map((asset) => ({ entry, asset })));
	let reused = 0;
	let downloaded = 0;
	let audioSeekable = 0;
	let audioUnchanged = 0;
	const failures: string[] = [];

	await mapConcurrent(tasks, Math.max(1, concurrency), async ({ entry, asset }) => {
		const key = assetKey(entry.id, asset.type);
		expectedCacheKeys.add(key);
		try {
			const remote = await probeRemote(asset.url, fetcher);
			const oldValue = oldCache.assets[key];
			if (
				remote &&
				hasUsefulValidator(remote) &&
				isValidCacheEntry(oldValue) &&
				oldValue.sourceUrl === asset.url &&
				sameMetadata(oldValue, remote) &&
				(asset.expectedBytes === null || oldValue.contentLength === asset.expectedBytes) &&
				oldValue.contentLength === remote.contentLength &&
				(await verifyCachedFile(outputDirectory, oldValue))
			) {
				nextCache.assets[key] = oldValue;
				indexEntries.get(entry.id)!.files[asset.type] = {
					path: `/${oldValue.path}`,
					sourceUrl: asset.url,
					size: oldValue.size,
					sha256: oldValue.sha256,
				};
				reused += 1;
				return;
			}
			const value = await downloadAssetWithFetcher(asset, remote, outputDirectory, fetcher, prepareAudio, maxAssetBytes);
			nextCache.assets[key] = value;
			indexEntries.get(entry.id)!.files[asset.type] = {
				path: `/${value.path}`,
				sourceUrl: asset.url,
				size: value.size,
				sha256: value.sha256,
			};
			downloaded += 1;
			if (asset.type === "audio") {
				if (value.strippedAudio) audioSeekable += 1;
				else audioUnchanged += 1;
			}
		} catch (error) {
			failures.push(`${asset.url}: ${error instanceof Error ? error.message : String(error)}`);
		}
	});

	if (failures.length > 0) {
		throw new Error(
			`Failed to mirror ${failures.length} chart asset(s):\n${failures.slice(0, 10).join("\n")}${failures.length > 10 ? `\n... and ${failures.length - 10} more` : ""}`,
		);
	}

	const index: ChartAssetIndex = {
		schemaVersion: 1,
		generatedAt: new Date().toISOString(),
		source: {
			name: "ADX 谱面资源",
			catalogUrl,
			attribution: CHART_ATTRIBUTION,
			metadataLicense: "CC BY 4.0",
			mediaRights: "Original rights holders; redistributed under maimaid authorization.",
		},
		entries: [...indexEntries.values()],
	};
	await mkdir(outputDirectory, { recursive: true });
	const removedCacheEntries = Object.keys(oldCache.assets).filter((key) => !expectedCacheKeys.has(key)).length;
	await writeJSONAtomically(cachePath, nextCache);
	await writeJSONAtomically(path.join(outputDirectory, "index.json"), index);
	await pruneUnusedAssets(outputDirectory, new Set(Object.values(nextCache.assets).map((value) => value.path)));
	console.log(`[chart-assets] ${reused} reused, ${downloaded} downloaded, ${tasks.length} total`);
	if (downloaded > 0) {
		console.log(
			`[chart-assets] audio rebuilt this run: ${audioSeekable} without a Xing/Info frame, ` +
				`${audioUnchanged} byte-identical`,
		);
	}
	return { index, reused, downloaded, changed: downloaded > 0 || removedCacheEntries > 0 };
};

const downloadAssetWithFetcher = async (
	asset: SourceAsset,
	remote: RemoteMetadata | null,
	outputDirectory: string,
	fetcher: typeof fetch,
	prepareAudio: ChartAudioPreparer,
	maxAssetBytes: number,
): Promise<CachedAsset> => {
	const response = await retry(() => fetcher(asset.url, { signal: AbortSignal.timeout(180_000) }));
	if (!response.ok) throw new Error(`Failed to download ${asset.url}: HTTP ${response.status}`);
	const responseMetadata = remoteMetadata(response.headers);
	const bytes = Buffer.from(await response.arrayBuffer());
	if (bytes.byteLength === 0) throw new Error(`Empty chart asset: ${asset.url}`);
	if (bytes.byteLength > maxAssetBytes) throw new Error(`Chart asset exceeds Workers limit: ${asset.url}`);
	const expectedLengths = [asset.expectedBytes, remote?.contentLength, responseMetadata.contentLength].filter(
		(value): value is number => value !== null,
	);
	if (expectedLengths.some((value) => value !== bytes.byteLength)) {
		throw new Error(
			`Chart asset byte count mismatch for ${asset.url}: downloaded ${bytes.byteLength}, expected ${expectedLengths.join(" / ")}`,
		);
	}
	if (remote && hasUsefulValidator(remote) && !compatibleMetadata(remote, responseMetadata)) {
		throw new Error(`Chart asset changed between HEAD and GET: ${asset.url}`);
	}
	let published: Buffer<ArrayBufferLike> = bytes;
	if (asset.type === "audio") {
		published = await prepareAudio(bytes, asset.url);
		if (published.byteLength === 0) throw new Error(`Empty prepared chart audio: ${asset.url}`);
		if (published.byteLength > maxAssetBytes) {
			throw new Error(`Prepared chart audio exceeds Workers limit: ${asset.url}`);
		}
	}
	const sha256 = createHash("sha256").update(published).digest("hex");
	const relativePath = `chart-assets/files/${sha256}.${asset.pathExtension}`;
	const destination = path.join(path.dirname(outputDirectory), relativePath);
	await mkdir(path.dirname(destination), { recursive: true });
	const temporaryPath = `${destination}.${process.pid}.${randomUUID()}.tmp`;
	try {
		await writeFile(temporaryPath, published);
		await rename(temporaryPath, destination);
	} catch (error) {
		await rm(temporaryPath, { force: true });
		throw error;
	}
	return {
		sourceUrl: asset.url,
		etag: responseMetadata.etag ?? remote?.etag ?? null,
		lastModified: responseMetadata.lastModified ?? remote?.lastModified ?? null,
		contentLength: responseMetadata.contentLength ?? remote?.contentLength ?? bytes.byteLength,
		path: relativePath,
		sha256,
		size: published.byteLength,
		pipeline: CHART_ASSET_PIPELINE_VERSION,
		strippedAudio: published.byteLength !== bytes.byteLength,
	};
};

const pruneUnusedAssets = async (outputDirectory: string, activePaths: Set<string>): Promise<void> => {
	const filesDirectory = path.join(outputDirectory, "files");
	let names: string[];
	try {
		names = await readdir(filesDirectory);
	} catch {
		return;
	}
	await Promise.all(
		names
			.filter((name) => !activePaths.has(`chart-assets/files/${name}`))
			.map((name) => rm(path.join(filesDirectory, name), { force: true })),
	);
};

export type VerifyChartAssetsOptions = { publicDirectory?: string; maxFiles?: number };

export const verifyChartAssets = async (options: VerifyChartAssetsOptions = {}): Promise<void> => {
	const outputDirectory = options.publicDirectory ?? OUTPUT_DIRECTORY;
	const maxFiles = options.maxFiles ?? MAX_STATIC_ASSET_FILES;
	const indexPath = path.join(outputDirectory, "index.json");
	const index = JSON.parse(await readFile(indexPath, "utf8")) as ChartAssetIndex;
	if (index.schemaVersion !== 1 || !Array.isArray(index.entries)) throw new Error("Invalid chart asset index");
	const referenced = new Set<string>();
	const chartIds = new Set<number>();
	for (const entry of index.entries) {
		if (!Number.isSafeInteger(entry.id) || entry.id <= 0) throw new Error(`Invalid chart ID in index: ${entry.id}`);
		if (chartIds.has(entry.id)) throw new Error(`Duplicate chart ID in index: ${entry.id}`);
		chartIds.add(entry.id);
		if (!entry.files.maidata || !entry.files.audio) throw new Error(`Chart ${entry.id} is missing maidata or audio`);
		for (const asset of Object.values(entry.files)) {
			if (!asset) continue;
			const relativePath = asset.path.replace(/^\//u, "");
			if (!/^chart-assets\/files\/[0-9a-f]{64}\.(txt|mp3)$/u.test(relativePath)) {
				throw new Error(`Invalid chart asset path: ${asset.path}`);
			}
			if (!/^[0-9a-f]{64}$/u.test(asset.sha256)) throw new Error(`Invalid SHA-256 for ${asset.path}`);
			const filePath = path.join(path.dirname(outputDirectory), relativePath);
			const metadata = await stat(filePath);
			if (!metadata.isFile() || metadata.size !== asset.size || metadata.size > MAX_STATIC_ASSET_BYTES) {
				throw new Error(`Invalid chart asset size: ${asset.path}`);
			}
			const actual = await hashFile(filePath);
			if (actual.sha256 !== asset.sha256 || path.basename(relativePath).split(".")[0] !== actual.sha256) {
				throw new Error(`Invalid chart asset hash: ${asset.path}`);
			}
			referenced.add(relativePath);
		}
	}
	const allFiles = await listFiles(path.dirname(outputDirectory));
	if (allFiles.length > maxFiles)
		throw new Error(`Workers static asset file count ${allFiles.length} exceeds limit ${maxFiles}`);
	console.log(`[chart-assets] verified ${referenced.size} referenced files; Worker tree has ${allFiles.length} files`);
};

const listFiles = async (directory: string): Promise<string[]> => {
	const entries = await readdir(directory, { withFileTypes: true });
	const nested = await Promise.all(
		entries.map(async (entry) => {
			const fullPath = path.join(directory, entry.name);
			if (entry.isDirectory()) return listFiles(fullPath);
			return entry.isFile() ? [fullPath] : [];
		}),
	);
	return nested.flat();
};
