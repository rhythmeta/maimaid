import { appendFile } from "node:fs/promises";
import { mirrorChartAssets } from "./chart-assets.js";

try {
	const result = await mirrorChartAssets();
	if (process.env.GITHUB_OUTPUT) {
		await appendFile(
			process.env.GITHUB_OUTPUT,
			`reused=${result.reused}\ndownloaded=${result.downloaded}\nchanged=${result.changed}\n`,
		);
	}
} catch (error) {
	console.error("[chart-assets] failed:", error instanceof Error ? error.message : error);
	process.exitCode = 1;
}
