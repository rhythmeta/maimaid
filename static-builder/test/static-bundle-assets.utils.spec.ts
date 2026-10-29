import { describe, expect, it } from "vitest";
import { collectStaticAssetCandidates } from "../src/assets.js";

describe("static bundle asset collection", () => {
	it("deduplicates covers and maps LXNS icon ids to upstream PNGs", () => {
		const assets = collectStaticAssetCandidates({
			resources: {
				data_json: {
					songs: [{ imageName: "cover one.png" }, { imageName: "cover one.png" }, { imageName: "cover-two.png" }],
				},
				lxns_icon_list: {
					icons: [{ id: 1 }, { id: 605905 }, { id: "invalid" }],
				},
			},
		});

		expect(assets).toEqual([
			{
				kind: "cover",
				name: "cover one.png",
				sourceUrls: [
					"https://dp4p6x0xfi5o9.cloudfront.net/maimai/img/cover/cover%20one.png",
					"https://shama.dxrating.net/images/cover/v2/cover%20one.jpg",
				],
			},
			{
				kind: "cover",
				name: "cover-two.png",
				sourceUrls: [
					"https://dp4p6x0xfi5o9.cloudfront.net/maimai/img/cover/cover-two.png",
					"https://shama.dxrating.net/images/cover/v2/cover-two.jpg",
				],
			},
			{
				kind: "presetAvatar",
				name: "1.png",
				sourceUrls: ["https://assets2.lxns.net/maimai/icon/1.png"],
			},
			{
				kind: "presetAvatar",
				name: "605905.png",
				sourceUrls: ["https://assets2.lxns.net/maimai/icon/605905.png"],
			},
		]);
	});
});
