import { describe, expect, it } from "vitest";
import { stripMp3MetadataFrame } from "../src/mp3-seekable.js";

const FRAME_BYTES = 768; // MPEG1 Layer III, 256 kbps, 48 kHz, no padding

const frame = (marker: number, metadata?: string, bitrateIndex = 13): Buffer => {
	const bytes = Buffer.alloc(FRAME_BYTES, marker);
	bytes[0] = 0xff;
	bytes[1] = 0xfb; // MPEG1, Layer III, no CRC
	bytes[2] = (bitrateIndex << 4) | 0x04; // bitrate index, 48 kHz, no padding
	bytes[3] = 0x00; // stereo
	if (metadata) bytes.write(metadata, 4 + 32, "latin1");
	return bytes;
};

const id3Tag = (payload: number): Buffer => {
	const bytes = Buffer.alloc(10 + payload, 0x00);
	bytes.write("ID3", 0, "latin1");
	bytes[3] = 3;
	bytes[6] = (payload >> 21) & 0x7f;
	bytes[7] = (payload >> 14) & 0x7f;
	bytes[8] = (payload >> 7) & 0x7f;
	bytes[9] = payload & 0x7f;
	return bytes;
};

describe("mp3 seekable preparation", () => {
	it("removes the Xing/Info frame and keeps the rest byte-identical", () => {
		const tag = id3Tag(100);
		const metadata = frame(0x11, "Info");
		const audio = frame(0x22);
		const result = stripMp3MetadataFrame(Buffer.concat([tag, metadata, audio]));
		expect(result.status).toBe("stripped");
		if (result.status !== "stripped") return;
		expect(result.bytes.length).toBe(tag.length + audio.length);
		expect(result.bytes.equals(Buffer.concat([tag, audio]))).toBe(true);
	});

	it("removes a VBRI frame as well", () => {
		const metadata = frame(0x11);
		metadata.write("VBRI", 36, "latin1");
		const audio = frame(0x22);
		const result = stripMp3MetadataFrame(Buffer.concat([metadata, audio]));
		expect(result.status).toBe("stripped");
		if (result.status !== "stripped") return;
		expect(result.bytes.equals(audio)).toBe(true);
	});

	it("leaves tracks without a metadata frame untouched", () => {
		const result = stripMp3MetadataFrame(Buffer.concat([frame(0x33), frame(0x44)]));
		expect(result).toEqual({ status: "unchanged", bytes: null, reason: "no-metadata" });
	});

	it("leaves variable bitrate audio untouched", () => {
		const metadata = frame(0x11, "Info");
		const audio = frame(0x22);
		const varying = frame(0x33, undefined, 12); // 224 kbps
		const result = stripMp3MetadataFrame(Buffer.concat([metadata, audio, varying]));
		expect(result).toEqual({ status: "unchanged", bytes: null, reason: "not-cbr" });
	});

	it("leaves data that is not an MPEG audio stream untouched", () => {
		const result = stripMp3MetadataFrame(Buffer.from("audio bytes"));
		expect(result).toEqual({ status: "unchanged", bytes: null, reason: "not-mp3" });
	});
});
