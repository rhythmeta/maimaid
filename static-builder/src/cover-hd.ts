import { createHash } from "node:crypto";
import { open, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import type { StaticAssetCandidate } from "./assets.js";

/**
 * Community chart audio often carries the jacket art in its ID3v2 tag, and that image is usually
 * sharper than the catalog cover the app would otherwise show. During the build every song is
 * matched to its chart audio, and a larger embedded image replaces the catalog cover under an
 * `hd-` prefixed, content-addressed name. The prefix is the marker: clients (and later builds)
 * can tell the cover was promoted without inspecting the MP3 again.
 */

const MAX_TAG_BYTES = 16 * 1024 * 1024;
const IMAGE_HEADER_BYTES = 64 * 1024;
const FRONT_COVER = 3;

type ImageSize = { width: number; height: number };

const toRecord = (value: unknown): Record<string, unknown> | null =>
	typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;

/** Same mapping as the Android client: DX charts above 10 000, plus the fallback ids. */
export const chartIdCandidates = (providerSongId: unknown, chartType: unknown): number[] => {
	const id =
		typeof providerSongId === "number" && Number.isSafeInteger(providerSongId) && providerSongId > 0 ? providerSongId : null;
	if (id === null) return [];
	const isDx = typeof chartType === "string" && chartType.toLowerCase() === "dx";
	const resolved = isDx && id < 10_000 ? id + 10_000 : id;
	const candidates = [resolved];
	if (resolved >= 100_000) {
		const base = resolved % 100_000;
		if (base > 0) candidates.push(base);
		if (base >= 1 && base <= 9_999) candidates.push(base + 10_000);
	} else if (resolved >= 10_000) {
		candidates.push(resolved % 10_000);
	} else {
		candidates.push(resolved + 10_000);
	}
	return [...new Set(candidates)];
};

const syncSafe = (bytes: Buffer, offset: number): number =>
	((bytes[offset] as number) & 0x7f) * 0x200000 +
	((bytes[offset + 1] as number) & 0x7f) * 0x4000 +
	((bytes[offset + 2] as number) & 0x7f) * 0x80 +
	((bytes[offset + 3] as number) & 0x7f);

const skipTerminated = (bytes: Buffer, start: number, end: number, wide: boolean): number => {
	let index = start;
	while (index < end) {
		if (wide) {
			if (bytes[index] === 0 && index + 1 < end && bytes[index + 1] === 0) return index + 2;
			index += 2;
		} else {
			if (bytes[index] === 0) return index + 1;
			index += 1;
		}
	}
	return -1;
};

type Picture = { bytes: Buffer; format: "png" | "jpg" };

/** Parses an APIC/PIC frame out of the file's ID3v2 tag; front covers win over other pictures. */
export const readEmbeddedPicture = async (file: string): Promise<Picture | null> => {
	const handle = await open(file, "r");
	try {
		const header = Buffer.alloc(10);
		const head = await handle.read(header, 0, 10, 0);
		if (head.bytesRead < 10) return null;
		if (header[0] !== 0x49 || header[1] !== 0x44 || header[2] !== 0x33) return null;
		const major = header[3] as number;
		if (major < 2 || major > 4) return null;
		const tagSize = syncSafe(header, 6);
		if (tagSize <= 0) return null;
		const tag = Buffer.alloc(Math.min(tagSize, MAX_TAG_BYTES));
		await handle.read(tag, 0, tag.length, 10);

		const headerSize = major === 2 ? 6 : 10;
		let offset = 0;
		let fallback: Picture | null = null;
		while (offset + headerSize <= tag.length && tag[offset] !== 0) {
			const idLength = major === 2 ? 3 : 4;
			const id = tag.subarray(offset, offset + idLength).toString("latin1");
			const frameSize =
				major === 2
					? ((tag[offset + 3] as number) << 16) | ((tag[offset + 4] as number) << 8) | (tag[offset + 5] as number)
					: major >= 4
						? syncSafe(tag, offset + 4)
						: tag.readUInt32BE(offset + 4);
			const body = offset + headerSize;
			if (frameSize <= 0 || body + frameSize > tag.length) break;
			if (id === "APIC" || id === "PIC") {
				const picture = id === "APIC" ? parseApic(tag, body, body + frameSize) : parsePic(tag, body, body + frameSize);
				if (picture?.type === FRONT_COVER) return picture.picture;
				if (!fallback && picture) fallback = picture.picture;
			}
			offset = body + frameSize;
		}
		return fallback;
	} catch {
		return null;
	} finally {
		await handle.close();
	}
};

const toPicture = (bytes: Buffer): Picture | null => {
	if (bytes.length > 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) {
		return { bytes, format: "png" };
	}
	if (bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
		return { bytes, format: "jpg" };
	}
	return null;
};

const parseApic = (tag: Buffer, start: number, end: number): { type: number; picture: Picture } | null => {
	if (start >= end) return null;
	const encoding = tag[start] as number;
	let index = skipTerminated(tag, start + 1, end, false); // MIME
	if (index < 0 || index >= end) return null;
	const type = tag[index] as number;
	index = skipTerminated(tag, index + 1, end, encoding === 1 || encoding === 2);
	if (index < 0 || index >= end) return null;
	const picture = toPicture(tag.subarray(index, end));
	return picture ? { type, picture } : null;
};

const parsePic = (tag: Buffer, start: number, end: number): { type: number; picture: Picture } | null => {
	if (start + 4 >= end) return null;
	const encoding = tag[start] as number;
	const type = tag[start + 4] as number;
	const index = skipTerminated(tag, start + 5, end, encoding === 1);
	if (index < 0 || index >= end) return null;
	const picture = toPicture(tag.subarray(index, end));
	return picture ? { type, picture } : null;
};

/** Reads image dimensions from a PNG/JPEG header without decoding the pixels. */
export const imageSizeOf = (bytes: Buffer): ImageSize | null => {
	if (bytes.length >= 24 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) {
		return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
	}
	if (bytes.length > 4 && bytes[0] === 0xff && bytes[1] === 0xd8) {
		let index = 2;
		while (index + 9 < bytes.length) {
			if (bytes[index] !== 0xff) {
				index += 1;
				continue;
			}
			const marker = bytes[index + 1] as number;
			if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
				index += 2;
				continue;
			}
			const length = bytes.readUInt16BE(index + 2);
			const isStartOfFrame = marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc;
			if (isStartOfFrame) {
				return { height: bytes.readUInt16BE(index + 5), width: bytes.readUInt16BE(index + 7) };
			}
			index += 2 + length;
		}
	}
	return null;
};

const imageSizeOfFile = async (file: string): Promise<ImageSize | null> => {
	try {
		const handle = await open(file, "r");
		try {
			const head = Buffer.alloc(IMAGE_HEADER_BYTES);
			const { bytesRead } = await handle.read(head, 0, head.length, 0);
			return imageSizeOf(head.subarray(0, bytesRead));
		} finally {
			await handle.close();
		}
	} catch {
		return null;
	}
};

export type EmbeddedCoverOptions = {
	outputDirectory: string;
	chartAssetsDirectory: string;
};

export type EmbeddedCoverResult = {
	checked: number;
	replaced: number;
	skippedWithoutAudio: number;
	published: StaticAssetCandidate[];
};

/**
 * Replaces catalog covers with larger embedded art, mutating `imageName` on the payload songs.
 * Must run after the catalog covers were downloaded (their dimensions are the baseline).
 */
export const promoteEmbeddedCovers = async (
	payload: Record<string, unknown>,
	options: EmbeddedCoverOptions,
): Promise<EmbeddedCoverResult> => {
	const result: EmbeddedCoverResult = { checked: 0, replaced: 0, skippedWithoutAudio: 0, published: [] };
	const resources = toRecord(payload.resources);
	const catalog = toRecord(resources?.data_json);
	const songs = Array.isArray(catalog?.songs) ? catalog.songs : [];
	if (songs.length === 0) return result;

	let index: unknown;
	try {
		index = JSON.parse(await readFile(path.join(options.chartAssetsDirectory, "index.json"), "utf8"));
	} catch {
		return result;
	}
	const audioById = new Map<number, string>();
	const publicRoot = path.dirname(options.chartAssetsDirectory);
	for (const entry of Array.isArray(toRecord(index)?.entries) ? (toRecord(index)!.entries as unknown[]) : []) {
		const record = toRecord(entry);
		const id = record?.id;
		const audioPath = toRecord(toRecord(record?.files)?.audio)?.path;
		if (typeof id !== "number" || typeof audioPath !== "string") continue;
		audioById.set(id, path.join(publicRoot, audioPath.replace(/^\/+/, "")));
	}

	const publishedNames = new Set<string>();
	const pictureCache = new Map<string, Picture | null>();
	for (const rawSong of songs) {
		const song = toRecord(rawSong);
		const imageName = song?.imageName;
		if (!song || typeof imageName !== "string" || !imageName.trim()) continue;

		const sheets = Array.isArray(song.sheets) ? song.sheets : [];
		let picture: Picture | null = null;
		for (const rawSheet of sheets) {
			const sheet = toRecord(rawSheet);
			for (const id of chartIdCandidates(sheet?.internalId, sheet?.type)) {
				const file = audioById.get(id);
				if (!file) continue;
				if (!pictureCache.has(file)) pictureCache.set(file, await readEmbeddedPicture(file));
				picture = pictureCache.get(file) ?? null;
				if (picture) break;
			}
			if (picture) break;
		}
		if (!picture) {
			if (sheets.length > 0) result.skippedWithoutAudio += 1;
			continue;
		}
		result.checked += 1;

		const embedded = imageSizeOf(picture.bytes);
		if (!embedded) continue;
		const catalogSize = await imageSizeOfFile(path.join(options.outputDirectory, "covers", imageName));
		if (catalogSize && catalogSize.width * catalogSize.height >= embedded.width * embedded.height) continue;

		const sha256 = createHash("sha256").update(picture.bytes).digest("hex");
		const hdName = `hd-${sha256}.${picture.format}`;
		const hdPath = path.join(options.outputDirectory, "covers", hdName);
		if (!publishedNames.has(hdName)) {
			await writeFile(hdPath, picture.bytes);
			publishedNames.add(hdName);
			result.published.push({ kind: "cover", name: hdName, sourceUrls: [] });
		}
		song.imageName = hdName;
		result.replaced += 1;
	}
	return result;
};
