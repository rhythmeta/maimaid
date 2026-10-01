import { buildSongIdMapping } from "./chart-fit.js";
type Song = { songId: string; title: string; artist: string; imageName: string };
export function communityIndex(payload: Record<string, unknown>, base: string) {
	const resources = payload.resources as Record<string, unknown>;
	const catalog = (resources.data_json as { songs: Song[] }).songs;
	const mapping = buildSongIdMapping(resources.data_json, resources.songid_json);
	const aliases = (resources.lxns_aliases as { aliases: Array<{ song_id: number; aliases: string[] }> }).aliases;
	const byId = new Map(aliases.map((row) => [row.song_id, row.aliases]));
	const songs: Record<string, string[]> = {};
	for (const song of catalog) {
		const ids = [...(mapping.byTitle.get(song.title.normalize("NFKC").trim().toLowerCase().replace(/\s+/gu, " ")) ?? [])];
		const ownId = Number(song.songId);
		if (Number.isFinite(ownId)) ids.push(ownId);
		songs[String(song.songId)] = [...new Set(ids.flatMap((id) => byId.get(id) ?? []))];
	}
	return {
		schemaVersion: 1,
		game: "maimaid",
		songs,
		catalog: catalog.map((song) => ({
			songIdentifier: String(song.songId),
			title: song.title,
			artist: song.artist,
			coverUrl: `${base}/covers/${encodeURIComponent(song.imageName)}`,
		})),
	};
}
