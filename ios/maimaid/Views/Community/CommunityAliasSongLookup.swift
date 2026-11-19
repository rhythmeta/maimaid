/// Prebuilt song lookups for the community alias board. Building them once per body keeps the
/// group sort from rebuilding a song dictionary on every comparison.
struct CommunityAliasSongLookup {
    private let songsByIdentifier: [String: Song]
    private let titlesByIdentifier: [String: String]

    init(songs: [Song]) {
        var songsByIdentifier: [String: Song] = [:]
        var titlesByIdentifier: [String: String] = [:]
        songsByIdentifier.reserveCapacity(songs.count)
        titlesByIdentifier.reserveCapacity(songs.count)
        for song in songs {
            songsByIdentifier[song.songIdentifier] = song
            titlesByIdentifier[song.songIdentifier] = song.title
        }
        self.songsByIdentifier = songsByIdentifier
        self.titlesByIdentifier = titlesByIdentifier
    }

    func song(_ songIdentifier: String) -> Song? {
        songsByIdentifier[songIdentifier]
    }

    func title(_ songIdentifier: String) -> String {
        titlesByIdentifier[songIdentifier] ?? songIdentifier
    }
}
