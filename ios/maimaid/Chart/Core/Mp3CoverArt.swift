import Foundation

/// Reads the cover art that community MP3s carry in their ID3v2 tag.
///
/// Those images are usually much larger than the catalog cover the app downloads separately, so the
/// chart preview prefers them when they exist.
nonisolated enum Mp3CoverArt {
    private static let maxTagBytes = 16 * 1024 * 1024
    private static let pictureTypeFrontCover = 3

    /// Front-cover image bytes embedded in the file, or nil when it has no usable picture.
    static func read(fileURL: URL) -> Data? {
        guard let handle = try? FileHandle(forReadingFrom: fileURL) else { return nil }
        defer { try? handle.close() }
        guard let header = try? handle.read(upToCount: 10), header.count == 10 else { return nil }
        let bytes = [UInt8](header)
        guard bytes[0] == UInt8(ascii: "I"), bytes[1] == UInt8(ascii: "D"), bytes[2] == UInt8(ascii: "3") else {
            return nil
        }
        let major = Int(bytes[3])
        guard (2...4).contains(major) else { return nil }
        let size = syncSafe(bytes, offset: 6)
        guard size > 0 else { return nil }
        let length = min(size, maxTagBytes)
        guard let data = try? handle.read(upToCount: length), data.count == length else { return nil }
        return pictures([UInt8](data), major: major)
    }

    private static func pictures(_ tag: [UInt8], major: Int) -> Data? {
        let headerSize = major == 2 ? 6 : 10
        var offset = 0
        var fallback: Data?
        while offset + headerSize <= tag.count {
            if tag[offset] == 0 { break }  // padding
            let idBytes = major == 2 ? 3 : 4
            let id = String(decoding: tag[offset..<(offset + idBytes)], as: UTF8.self)
            let frameSize: Int
            if major == 2 {
                frameSize = (Int(tag[offset + 3]) << 16) | (Int(tag[offset + 4]) << 8) | Int(tag[offset + 5])
            } else if major >= 4 {
                frameSize = syncSafe(tag, offset: offset + 4)
            } else {
                frameSize = (Int(tag[offset + 4]) << 24) | (Int(tag[offset + 5]) << 16)
                    | (Int(tag[offset + 6]) << 8) | Int(tag[offset + 7])
            }
            if frameSize <= 0 || offset + headerSize + frameSize > tag.count { break }
            let bodyStart = offset + headerSize
            switch id {
            case "APIC":
                let picture = apic(tag, start: bodyStart, end: bodyStart + frameSize)
                if picture.0 == pictureTypeFrontCover { return picture.1 }
                if fallback == nil { fallback = picture.1 }
            case "PIC":
                let picture = pic(tag, start: bodyStart, end: bodyStart + frameSize)
                if picture.0 == pictureTypeFrontCover { return picture.1 }
                if fallback == nil { fallback = picture.1 }
            default:
                break
            }
            offset = bodyStart + frameSize
        }
        return fallback
    }

    /// APIC: encoding, NUL-terminated MIME, picture type, NUL-terminated description, data.
    private static func apic(_ tag: [UInt8], start: Int, end: Int) -> (Int, Data?) {
        var index = start
        guard index < end else { return (-1, nil) }
        let encoding = Int(tag[index])
        index += 1
        index = skipTerminated(tag, start: index, end: end, terminatedByTwoBytes: false)
        guard index >= 0, index < end else { return (-1, nil) }
        let pictureType = Int(tag[index])
        index += 1
        index = skipTerminated(tag, start: index, end: end, terminatedByTwoBytes: encoding == 1 || encoding == 2)
        guard index >= 0, index < end else { return (-1, nil) }
        return (pictureType, Data(tag[index..<end]))
    }

    /// PIC (ID3v2.2): encoding, 3-character image format, picture type, description, data.
    private static func pic(_ tag: [UInt8], start: Int, end: Int) -> (Int, Data?) {
        var index = start
        guard index + 4 < end else { return (-1, nil) }
        let encoding = Int(tag[index])
        index += 4
        let pictureType = Int(tag[index])
        index += 1
        index = skipTerminated(tag, start: index, end: end, terminatedByTwoBytes: encoding == 1)
        guard index >= 0, index < end else { return (-1, nil) }
        return (pictureType, Data(tag[index..<end]))
    }

    private static func skipTerminated(_ bytes: [UInt8], start: Int, end: Int,
                                       terminatedByTwoBytes: Bool) -> Int {
        var index = start
        while index < end {
            if terminatedByTwoBytes {
                if bytes[index] == 0 && index + 1 < end && bytes[index + 1] == 0 {
                    return index + 2
                }
                index += 2
            } else {
                if bytes[index] == 0 { return index + 1 }
                index += 1
            }
        }
        return -1
    }

    private static func syncSafe(_ bytes: [UInt8], offset: Int) -> Int {
        guard offset + 3 < bytes.count else { return 0 }
        return (Int(bytes[offset] & 0x7F) << 21) | (Int(bytes[offset + 1] & 0x7F) << 14)
            | (Int(bytes[offset + 2] & 0x7F) << 7) | Int(bytes[offset + 3] & 0x7F)
    }
}
