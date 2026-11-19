import CoreGraphics
import Foundation
import ImageIO

/// Packages the selected chart as a shareable zip with `maidata.txt`, `track.mp3` and `bg.png`,
/// mirroring the Android chart-preview export.
nonisolated enum ChartZipExporter {
    /// Builds the package in a temporary directory and returns its file URL.
    static func export(maidata: String, title: String, difficulty: String, audioURL: URL?,
                       coverData: Data?, noteDesigner: String?) throws -> URL {
        var entries: [(name: String, data: Data)] = []
        entries.append(("maidata.txt", Data(maidata.utf8)))
        if let audioURL, let audio = try? Data(contentsOf: audioURL) {
            entries.append(("track.mp3", audio))
        }
        if let coverData, let png = pngData(from: coverData) {
            entries.append(("bg.png", png))
        }

        let zip = ChartZipArchive.archive(entries: entries)
        let fileManager = FileManager.default
        let directory = fileManager.temporaryDirectory.appending(path: "chart-export", directoryHint: .isDirectory)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let name = fileName(title: title, difficulty: difficulty)
        let url = directory.appending(path: name)
        try? fileManager.removeItem(at: url)
        try zip.write(to: url, options: .atomic)
        return url
    }

    /// Sanitized archive name offered by the share sheet.
    private static func fileName(title: String, difficulty: String) -> String {
        let base = [title.trimmingCharacters(in: .whitespacesAndNewlines),
                    difficulty.trimmingCharacters(in: .whitespacesAndNewlines)]
            .filter { !$0.isEmpty }
            .joined(separator: " - ")
        let sanitized = base.isEmpty ? "chart" : base
        let illegal = CharacterSet(charactersIn: "\\/:*?\"<>|").union(.newlines)
        let cleaned = sanitized.unicodeScalars.map { illegal.contains($0) ? "_" : String($0) }.joined()
        return "\(String(cleaned.prefix(120))).zip"
    }

    /// Re-encodes arbitrary image bytes as PNG so the package always contains `bg.png`.
    private static func pngData(from data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, "public.png" as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }
}

/// Minimal STORE-only zip writer: ZIP64 is not needed for chart packages, and every consumer of a
/// maimaid chart zip (including the Android app) accepts stored entries.
nonisolated enum ChartZipArchive {
    static func archive(entries: [(name: String, data: Data)], date: Date = .now) -> Data {
        var output = Data()
        var central = Data()
        let (dosTime, dosDate) = dosTimestamp(date)

        for entry in entries {
            guard let nameData = entry.name.data(using: .utf8) else { continue }
            let crc = crc32(entry.data)
            let size = UInt32(entry.data.count)
            let offset = UInt32(output.count)

            // Local file header.
            output.append(contentsOf: le32(0x0403_4B50))
            output.append(contentsOf: le16(20))                 // version needed
            output.append(contentsOf: le16(0x0800))             // UTF-8 names
            output.append(contentsOf: le16(0))                  // method: store
            output.append(contentsOf: le16(dosTime))
            output.append(contentsOf: le16(dosDate))
            output.append(contentsOf: le32(crc))
            output.append(contentsOf: le32(size))
            output.append(contentsOf: le32(size))
            output.append(contentsOf: le16(UInt16(nameData.count)))
            output.append(contentsOf: le16(0))                  // extra length
            output.append(nameData)
            output.append(entry.data)

            // Central directory header.
            central.append(contentsOf: le32(0x0201_4B50))
            central.append(contentsOf: le16(20))                // version made by
            central.append(contentsOf: le16(20))                // version needed
            central.append(contentsOf: le16(0x0800))
            central.append(contentsOf: le16(0))
            central.append(contentsOf: le16(dosTime))
            central.append(contentsOf: le16(dosDate))
            central.append(contentsOf: le32(crc))
            central.append(contentsOf: le32(size))
            central.append(contentsOf: le32(size))
            central.append(contentsOf: le16(UInt16(nameData.count)))
            central.append(contentsOf: le16(0))                 // extra length
            central.append(contentsOf: le16(0))                 // comment length
            central.append(contentsOf: le16(0))                 // disk number
            central.append(contentsOf: le16(0))                 // internal attributes
            central.append(contentsOf: le32(0))                 // external attributes
            central.append(contentsOf: le32(offset))
            central.append(nameData)
        }

        let centralOffset = UInt32(output.count)
        output.append(central)
        // End of central directory.
        output.append(contentsOf: le32(0x0605_4B50))
        output.append(contentsOf: le16(0))
        output.append(contentsOf: le16(0))
        output.append(contentsOf: le16(UInt16(entries.count)))
        output.append(contentsOf: le16(UInt16(entries.count)))
        output.append(contentsOf: le32(UInt32(central.count)))
        output.append(contentsOf: le32(centralOffset))
        output.append(contentsOf: le16(0))
        return output
    }

    private static func le16(_ value: UInt16) -> [UInt8] {
        [UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF)]
    }

    private static func le32(_ value: UInt32) -> [UInt8] {
        [UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF), UInt8((value >> 16) & 0xFF), UInt8((value >> 24) & 0xFF)]
    }

    private static func dosTimestamp(_ date: Date) -> (time: UInt16, date: UInt16) {
        let calendar = Calendar(identifier: .gregorian)
        let components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let year = max(1980, components.year ?? 1980)
        let month = components.month ?? 1
        let day = components.day ?? 1
        let hour = components.hour ?? 0
        let minute = components.minute ?? 0
        let second = (components.second ?? 0) / 2
        let dosDate = UInt16((year - 1980) << 9 | month << 5 | day)
        let dosTime = UInt16(hour << 11 | minute << 5 | second)
        return (dosTime, dosDate)
    }

    private static let crcTable: [UInt32] = {
        (0...255).map { index -> UInt32 in
            var value = UInt32(index)
            for _ in 0..<8 {
                value = (value & 1) == 1 ? (0xEDB8_8320 ^ (value >> 1)) : (value >> 1)
            }
            return value
        }
    }()

    private static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc = crcTable[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }
}
