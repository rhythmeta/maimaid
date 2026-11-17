import { verifyChartAssets } from "./chart-assets.js";

await verifyChartAssets().catch((error: unknown) => {
	console.error("[chart-assets] verification failed:", error instanceof Error ? error.message : error);
	process.exitCode = 1;
});
