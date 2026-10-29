export type StaticAssetKind = "cover" | "presetAvatar";

export type StaticAssetCandidate = {
	kind: StaticAssetKind;
	name: string;
	/** Ordered sources; the first one that returns an image wins. */
	sourceUrls: string[];
};

const COVER_SOURCE_BASE_URL = "https://dp4p6x0xfi5o9.cloudfront.net/maimai/img/cover/";
// dxrating keeps the same content-hash file names but stores them as JPEG, and it carries newly
// released songs before the CloudFront mirror does. Only consulted when the primary source fails.
const COVER_SOURCE_FALLBACK_BASE_URL = "https://shama.dxrating.net/images/cover/v2/";
const PRESET_AVATAR_SOURCE_BASE_URL = "https://assets2.lxns.net/maimai/icon/";

/** dxrating stores covers as JPEG while keeping the content-hash file stem. */
const toFallbackCoverName = (name: string): string => name.replace(/\.png$/iu, ".jpg");

const toRecord = (value: unknown): Record<string, unknown> | null =>
	typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;

export const collectStaticAssetCandidates = (payload: Record<string, unknown>): StaticAssetCandidate[] => {
	const resources = toRecord(payload.resources);
	const catalog = toRecord(resources?.data_json);
	const songs = Array.isArray(catalog?.songs) ? catalog.songs : [];
	const iconList = toRecord(resources?.lxns_icon_list);
	const icons = Array.isArray(iconList?.icons) ? iconList.icons : [];
	if (songs.length === 0) {
		throw new Error("Composed bundle has no songs to mirror covers for.");
	}
	if (icons.length === 0) {
		throw new Error("Composed bundle has no LXNS icons to mirror.");
	}

	const assets = new Map<string, StaticAssetCandidate>();
	for (const rawSong of songs) {
		const imageName = toRecord(rawSong)?.imageName;
		if (typeof imageName !== "string" || !imageName.trim()) {
			continue;
		}
		const name = imageName.trim();
		const candidate = {
			kind: "cover" as const,
			name,
			sourceUrls: [
				`${COVER_SOURCE_BASE_URL}${encodeURIComponent(name)}`,
				`${COVER_SOURCE_FALLBACK_BASE_URL}${encodeURIComponent(toFallbackCoverName(name))}`,
			],
		};
		assets.set(`${candidate.kind}|${candidate.name}`, candidate);
	}

	for (const rawIcon of icons) {
		const id = Number(toRecord(rawIcon)?.id);
		if (!Number.isSafeInteger(id) || id < 0) {
			continue;
		}
		const name = `${id}.png`;
		const candidate = {
			kind: "presetAvatar" as const,
			name,
			sourceUrls: [`${PRESET_AVATAR_SOURCE_BASE_URL}${name}`],
		};
		assets.set(`${candidate.kind}|${candidate.name}`, candidate);
	}

	return [...assets.values()];
};
