/**
 * Image downloads with ordered source fallbacks, split out of `build.ts` so the retry/fallback
 * behaviour can be unit tested.
 */
import { writeFile } from "node:fs/promises";

export type DownloadFirstAvailableImageOptions = {
	/** Used in the failure message, e.g. `cover b546….png`. */
	label: string;
	/** Tried in order; the first one that yields an image wins. */
	sourceUrls: string[];
	attempt: (sourceUrl: string) => Promise<void>;
	/** Called with the winning source when the primary source had to be skipped. */
	onFallback?: (sourceUrl: string) => void;
};

export const downloadImageFromSource = async (
	sourceUrl: string,
	destination: string,
	timeoutMs: number,
): Promise<void> => {
	const response = await fetch(sourceUrl, {
		headers: { accept: "image/*", "user-agent": "maimaid-static-bundle-builder" },
		signal: AbortSignal.timeout(timeoutMs),
	});
	if (!response.ok) throw new Error(`HTTP ${response.status} ${sourceUrl}`);
	const contentType = response.headers.get("content-type") ?? "";
	if (!contentType.toLocaleLowerCase().startsWith("image/")) {
		throw new Error(`Unexpected content type ${contentType || "unknown"}: ${sourceUrl}`);
	}
	const body = Buffer.from(await response.arrayBuffer());
	if (body.byteLength === 0) throw new Error(`Empty image: ${sourceUrl}`);
	await writeFile(destination, body);
};

/**
 * Walks the sources until one produces an image. The caller owns the retry policy through
 * `attempt`, so a source that is genuinely gone is not retried by this function.
 */
export const downloadFirstAvailableImage = async (options: DownloadFirstAvailableImageOptions): Promise<void> => {
	const failures: string[] = [];
	for (const [index, sourceUrl] of options.sourceUrls.entries()) {
		try {
			await options.attempt(sourceUrl);
			if (index > 0) options.onFallback?.(sourceUrl);
			return;
		} catch (error) {
			failures.push(error instanceof Error ? error.message : String(error));
		}
	}
	throw new Error(`Failed to download ${options.label}: ${failures.join("; ")}`);
};
