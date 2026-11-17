const MPEG1_BITRATES = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320];
const MPEG2_BITRATES = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160];
const SAMPLE_RATES: Record<number, number[]> = {
	3: [44_100, 48_000, 32_000],
	2: [22_050, 24_000, 16_000],
	0: [11_025, 12_000, 8_000],
};
const SCAN_WINDOW_BYTES = 64 * 1024;
const TRAILING_TAG_BYTES = 128;

type Frame = {
	offset: number;
	size: number;
	bitrate: number;
	sampleRate: number;
	versionBits: number;
	mono: boolean;
};

export type Mp3SeekableResult =
	| { status: "stripped"; bytes: Buffer; reason: null }
	| { status: "unchanged"; bytes: null; reason: "not-mp3" | "no-metadata" | "not-cbr" };

/**
 * Removes the leading Xing/Info metadata frame from a constant-bitrate MP3.
 *
 * Android's `MP3Extractor` seeks with the Xing table of contents whenever one is present. The
 * table stores a byte position per percent of the track in 1/256 steps, so the byte it lands on
 * can be a few hundred milliseconds away from the requested time — yet the extractor keeps the
 * *requested* time as the buffer timestamp (`mCurrentTimeUs = actualSeekTimeUs`). Playback then
 * reports a position that does not match the audible content until the next seek.
 *
 * Without the metadata frame `mSeeker == NULL`, so the extractor takes its CBR path: it estimates
 * the byte offset from the bitrate and then rewrites the timestamp from the frame it actually
 * landed on (`mCurrentTimeUs = (mCurrentPos - mFirstFramePos) * 8000 / bitrate`). Content and
 * timestamp are self-consistent within one frame (~24 ms at 48 kHz).
 *
 * The rewrite is only sound for constant-bitrate audio, so anything that is not byte-for-byte
 * CBR is left untouched.
 */
export const stripMp3MetadataFrame = (source: Buffer): Mp3SeekableResult => {
	const first = firstFrame(source);
	if (first === null) return { status: "unchanged", bytes: null, reason: "not-mp3" };
	if (!hasMetadataHeader(source, first)) {
		return { status: "unchanged", bytes: null, reason: "no-metadata" };
	}
	if (!isConstantBitrate(source, first)) {
		return { status: "unchanged", bytes: null, reason: "not-cbr" };
	}
	return {
		status: "stripped",
		bytes: Buffer.concat([source.subarray(0, first.offset), source.subarray(first.offset + first.size)]),
		reason: null,
	};
};

const firstFrame = (source: Buffer): Frame | null => {
	if (source.length < 4) return null;
	const tagLength = id3Length(source);
	const limit = Math.min(source.length - tagLength, SCAN_WINDOW_BYTES);
	for (let index = 0; index + 4 <= limit; index++) {
		const frame = parseFrame(source, tagLength + index);
		if (frame !== null) return frame;
	}
	return null;
};

const isConstantBitrate = (source: Buffer, first: Frame): boolean => {
	let offset = first.offset;
	let frames = 0;
	while (offset + 4 <= source.length) {
		const frame = parseFrame(source, offset);
		if (frame === null) break;
		if (frame.bitrate !== first.bitrate || frame.sampleRate !== first.sampleRate) return false;
		offset += frame.size;
		frames += 1;
	}
	if (frames < 2) return false;
	// A trailing ID3v1/APE tag or zero padding is fine; junk inside the stream is not.
	return offset >= source.length - TRAILING_TAG_BYTES;
};

const hasMetadataHeader = (source: Buffer, frame: Frame): boolean => {
	const sideInfo = frame.versionBits === 3 ? (frame.mono ? 17 : 32) : frame.mono ? 9 : 17;
	const magicOffset = frame.offset + 4 + sideInfo;
	return (
		matches(source, magicOffset, "Xing") || matches(source, magicOffset, "Info") || matches(source, frame.offset + 36, "VBRI")
	);
};

const parseFrame = (bytes: Buffer, offset: number): Frame | null => {
	if (offset + 4 > bytes.length) return null;
	if (bytes[offset] !== 0xff) return null;
	const second = bytes[offset + 1] as number;
	if ((second & 0xe0) !== 0xe0) return null;
	const versionBits = (second >> 3) & 0x03;
	if (versionBits === 1) return null;
	const layerBits = (second >> 1) & 0x03;
	if (layerBits !== 1) return null; // Layer III only
	const third = bytes[offset + 2] as number;
	const bitrateIndex = (third >> 4) & 0x0f;
	if (bitrateIndex === 0 || bitrateIndex === 0x0f) return null;
	const sampleRateIndex = (third >> 2) & 0x03;
	if (sampleRateIndex === 3) return null;
	const padding = (third >> 1) & 0x01;
	const mono = (bytes[offset + 3] as number) >> 6 === 3;
	const mpeg1 = versionBits === 3;
	const bitrate = (mpeg1 ? MPEG1_BITRATES : MPEG2_BITRATES)[bitrateIndex] as number;
	const sampleRate = SAMPLE_RATES[versionBits]?.[sampleRateIndex];
	if (!bitrate || !sampleRate) return null;
	const size = Math.floor(((mpeg1 ? 144 : 72) * bitrate * 1000) / sampleRate) + padding;
	if (size < 4) return null;
	return { offset, size, bitrate, sampleRate, versionBits, mono };
};

const id3Length = (bytes: Buffer): number => {
	if (bytes.length < 10) return 0;
	if (bytes[0] !== 0x49 || bytes[1] !== 0x44 || bytes[2] !== 0x33) return 0;
	const size =
		(((bytes[6] as number) & 0x7f) << 21) |
		(((bytes[7] as number) & 0x7f) << 14) |
		(((bytes[8] as number) & 0x7f) << 7) |
		((bytes[9] as number) & 0x7f);
	const footer = ((bytes[5] as number) & 0x10) !== 0 ? 10 : 0;
	return Math.min(10 + size + footer, bytes.length);
};

const matches = (bytes: Buffer, offset: number, magic: string): boolean => {
	if (offset < 0 || offset + magic.length > bytes.length) return false;
	return bytes.subarray(offset, offset + magic.length).toString("latin1") === magic;
};
