import SwiftUI
import SwiftData

/// Song header shared by the candidate and approved board sections.
struct CommunityAliasSongGroupHeader: View {
    let song: Song?
    let title: String
    let subtitle: String

    var body: some View {
        Group {
            if let song {
                SongDetailLink(song: song) {
                    HeaderContent(song: song, title: title, subtitle: subtitle)
                }
            } else {
                HeaderContent(song: nil, title: title, subtitle: subtitle)
            }
        }
        .textCase(nil)
    }

    private struct HeaderContent: View {
        let song: Song?
        let title: String
        let subtitle: String

        var body: some View {
            HStack(spacing: 12) {
                if let song {
                    SongJacketView(
                        imageName: song.imageName,
                        size: 46,
                        cornerRadius: 9,
                        useThumbnail: true
                    )
                } else {
                    RoundedRectangle(cornerRadius: 9)
                        .fill(Color.primary.opacity(0.08))
                        .frame(width: 46, height: 46)
                        .overlay {
                            Image(systemName: "music.note")
                                .foregroundStyle(.secondary)
                        }
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 14, weight: .semibold))
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.secondary)
                }

                Spacer()
            }
            .padding(.vertical, 4)
        }
    }
}
