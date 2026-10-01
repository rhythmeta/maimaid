import type { StaticSourceTarget } from "./bundle.js";
export const sources: StaticSourceTarget[] = [
	{
		category: "data_json",
		activeUrl: "https://raw.githubusercontent.com/gekichumai/dxrating/refs/heads/main/packages/dxdata/dxdata.json",
		fallbackUrls: [],
	},
	{ category: "songid_json", activeUrl: new URL("../songid.json", import.meta.url).href, fallbackUrls: [] },
	{ category: "utage_note_json", activeUrl: new URL("../utage_chart_stats.json", import.meta.url).href, fallbackUrls: [] },
	{ category: "lxns_aliases", activeUrl: "https://maimai.lxns.net/api/v0/maimai/alias/list", fallbackUrls: [] },
	{ category: "lxns_song_list", activeUrl: "https://maimai.lxns.net/api/v0/maimai/song/list", fallbackUrls: [] },
	{ category: "lxns_icon_list", activeUrl: "https://maimai.lxns.net/api/v0/maimai/icon/list", fallbackUrls: [] },
	{
		category: "chart_fit",
		activeUrl: "https://www.diving-fish.com/api/maimaidxprober/chart_stats",
		fallbackUrls: [],
	},
	{ category: "dan_info", activeUrl: "https://dp4p6x0xfi5o9.cloudfront.net/maimai/gallery.yaml", fallbackUrls: [] },
];
