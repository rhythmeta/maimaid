import Foundation

/// Charts the CN server shipped while it was still running an older version.
///
/// Their catalog `version` is the JP one, so the version-index rule alone would
/// file them under the old-song bucket, even though CN players receive them as
/// current-version songs.
enum CnSyncedSongPolicy {
    /// Provider song ids of the CN-synced charts. Keep the list sorted and
    /// comment every entry with its title.
    ///
    /// - `12025` 魔法仕掛けのファンタジア — MAGiCAL in JP, synced to CN while CN
    ///   is on PRiSM PLUS (WEC 2026).
    private static let syncedSongIds: Set<Int> = [12025]

    /// Returns true when `songId` belongs to a chart the CN server synced ahead
    /// of its own version, so it counts as a CN current-version song.
    static func contains(songId: Int?) -> Bool {
        guard let songId, songId > 0 else { return false }
        if syncedSongIds.contains(songId) { return true }
        // STD charts of the same song carry the base id (12025 -> 2025).
        return songId < 10_000 && syncedSongIds.contains(songId + 10_000)
    }
}
