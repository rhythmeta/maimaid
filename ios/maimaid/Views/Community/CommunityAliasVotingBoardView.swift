import SwiftUI
import SwiftData

struct CommunityAliasVotingBoardView: View {
    private enum BoardMode: Hashable {
        case candidates
        case approved
    }

    @Query private var songs: [Song]

    private let service = CommunityAliasService.shared
    @State private var backendSessionManager = BackendSessionManager.shared
    @State private var mode: BoardMode = .candidates
    @State private var items: [CommunityAliasVotingBoardItem] = []
    @State private var approvedAliases: [CommunityAliasApprovedItem] = []
    @State private var myCandidateIds: Set<String> = []
    @State private var isLoading = false
    @State private var isApprovedLoading = false
    @State private var inFlightVoteCandidateId: String?
    @State private var tipMessage: String?
    @State private var tipDismissTask: Task<Void, Never>?

    private struct SongVotingGroup: Identifiable {
        let songIdentifier: String
        let items: [CommunityAliasVotingBoardItem]
        var id: String { songIdentifier }
    }

    private struct SongApprovedGroup: Identifiable {
        let songIdentifier: String
        let aliases: [CommunityAliasApprovedItem]
        var id: String { songIdentifier }
    }

    private var modeTitle: LocalizedStringKey {
        switch mode {
        case .candidates: "community.alias.board.mode.candidates"
        case .approved: "community.alias.board.mode.approved"
        }
    }

    var body: some View {
        let lookup = CommunityAliasSongLookup(songs: songs)

        return Group {
            switch mode {
            case .candidates:
                candidatesList(lookup)
            case .approved:
                approvedList(lookup)
            }
        }
        .navigationTitle(String(localized: "community.alias.board.title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("community.alias.board.mode", selection: $mode) {
                        Text("community.alias.board.mode.candidates").tag(BoardMode.candidates)
                        Text("community.alias.board.mode.approved").tag(BoardMode.approved)
                    }
                } label: {
                    Label(modeTitle, systemImage: "rectangle.2.swap")
                        .labelStyle(.titleAndIcon)
                }
            }
        }
        .overlay(alignment: .bottom) {
            if let tipMessage {
                Text(tipMessage)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.82), in: Capsule())
                    .padding(.bottom, 20)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .task(id: mode) {
            await reloadActiveMode()
        }
        .onDisappear {
            tipDismissTask?.cancel()
            tipDismissTask = nil
        }
    }

    private func candidatesList(_ lookup: CommunityAliasSongLookup) -> some View {
        List {
            if !backendSessionManager.isConfigured {
                CommunityAliasMessageSection(
                    title: "community.alias.board.config.title",
                    message: "community.alias.board.config.message"
                )
            } else if !backendSessionManager.isAuthenticated {
                CommunityAliasMessageSection(
                    title: "community.alias.board.login.title",
                    message: "community.alias.board.login.message",
                    actionTitle: "community.alias.board.login.refresh",
                    onAction: {
                        Task {
                            await backendSessionManager.checkSession()
                            await reloadBoard()
                        }
                    }
                )
            } else if items.isEmpty && !isLoading {
                CommunityAliasMessageSection(
                    title: "community.alias.board.empty.title",
                    message: "community.alias.board.empty.message"
                )
            } else {
                boardSection(lookup)
            }
        }
        .refreshable {
            await reloadBoard()
        }
    }

    private func approvedList(_ lookup: CommunityAliasSongLookup) -> some View {
        List {
            if !backendSessionManager.isConfigured {
                CommunityAliasMessageSection(
                    title: "community.alias.board.config.title",
                    message: "community.alias.board.config.message"
                )
            } else if approvedAliases.isEmpty && !isApprovedLoading {
                CommunityAliasMessageSection(
                    title: "community.alias.board.approved.empty.title",
                    message: "community.alias.board.approved.empty.message"
                )
            } else {
                approvedBoardSection(lookup)
            }
        }
        .refreshable {
            await reloadApproved()
        }
    }

    private func boardSection(_ lookup: CommunityAliasSongLookup) -> some View {
        Group {
            if isLoading {
                Section {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("community.alias.board.loading")
                            .foregroundStyle(.secondary)
                    }
                }
            }

            ForEach(groupedItems(lookup)) { group in
                Section {
                    ForEach(group.items) { item in
                        CommunityAliasCandidateRow(
                            item: item,
                            isVoting: inFlightVoteCandidateId == item.id
                        ) { support in
                            Task {
                                await submitVote(candidateId: item.id, support: support)
                            }
                        }
                    }
                } header: {
                    CommunityAliasSongGroupHeader(
                        song: lookup.song(group.songIdentifier),
                        title: lookup.title(group.songIdentifier),
                        subtitle: String(
                            localized: "community.alias.board.group.subtitle \(group.items.count)"
                        )
                    )
                }
            }
        }
    }

    private func approvedBoardSection(_ lookup: CommunityAliasSongLookup) -> some View {
        Group {
            if isApprovedLoading {
                Section {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("community.alias.board.approved.loading")
                            .foregroundStyle(.secondary)
                    }
                }
            }

            ForEach(groupedApprovedAliases(lookup)) { group in
                Section {
                    ForEach(group.aliases) { alias in
                        CommunityAliasApprovedRow(
                            alias: alias,
                            isMine: myCandidateIds.contains(alias.candidateId)
                        )
                    }
                } header: {
                    CommunityAliasSongGroupHeader(
                        song: lookup.song(group.songIdentifier),
                        title: lookup.title(group.songIdentifier),
                        subtitle: String(
                            localized: "community.alias.board.approved.group.subtitle \(group.aliases.count)"
                        )
                    )
                }
            }
        }
    }

    private func groupedItems(_ lookup: CommunityAliasSongLookup) -> [SongVotingGroup] {
        Dictionary(grouping: items, by: \.songIdentifier)
            .map { key, value in
                SongVotingGroup(
                    songIdentifier: key,
                    items: value.sorted { lhs, rhs in
                        let lhsDate = lhs.voteCloseAt ?? .distantFuture
                        let rhsDate = rhs.voteCloseAt ?? .distantFuture
                        if lhsDate != rhsDate {
                            return lhsDate < rhsDate
                        }
                        return lhs.createdAt > rhs.createdAt
                    }
                )
            }
            .sorted { lhs, rhs in
                let lhsTitle = lookup.title(lhs.songIdentifier)
                let rhsTitle = lookup.title(rhs.songIdentifier)
                return lhsTitle.localizedStandardCompare(rhsTitle) == .orderedAscending
            }
    }

    private func groupedApprovedAliases(_ lookup: CommunityAliasSongLookup) -> [SongApprovedGroup] {
        Dictionary(grouping: approvedAliases, by: \.songIdentifier)
            .map { key, value in
                SongApprovedGroup(songIdentifier: key, aliases: value)
            }
            .sorted { lhs, rhs in
                let lhsTitle = lookup.title(lhs.songIdentifier)
                let rhsTitle = lookup.title(rhs.songIdentifier)
                return lhsTitle.localizedStandardCompare(rhsTitle) == .orderedAscending
            }
    }

    private func reloadActiveMode() async {
        switch mode {
        case .candidates:
            await reloadBoard()
        case .approved:
            await reloadApproved()
        }
    }

    private func reloadApproved() async {
        guard backendSessionManager.isConfigured else {
            approvedAliases = []
            return
        }

        isApprovedLoading = true
        approvedAliases = await service.fetchApprovedAliases()
        // The approved feed is public; only a signed-in account can match its own submissions.
        myCandidateIds = await service.fetchMyCandidateIds()
        isApprovedLoading = false
    }

    private func reloadBoard() async {
        guard backendSessionManager.isConfigured else { return }
        if !backendSessionManager.isAuthenticated {
            await backendSessionManager.checkSession()
        }
        guard backendSessionManager.isAuthenticated else {
            items = []
            return
        }

        isLoading = true
        items = await service.fetchVotingBoard(limit: 150)
        isLoading = false
    }

    private func submitVote(candidateId: String, support: Bool) async {
        guard backendSessionManager.isAuthenticated else {
            showTip(String(localized: "community.alias.board.tip.loginRequired"))
            return
        }

        inFlightVoteCandidateId = candidateId
        defer { inFlightVoteCandidateId = nil }

        guard let result = await service.vote(candidateId: candidateId, support: support) else {
            showTip(service.lastVoteErrorMessage ?? String(localized: "community.alias.board.tip.voteFailedFallback"))
            return
        }

        if let index = items.firstIndex(where: { $0.id == candidateId }) {
            let old = items[index]
            items[index] = CommunityAliasVotingBoardItem(
                candidateId: old.candidateId,
                songIdentifier: old.songIdentifier,
                aliasText: old.aliasText,
                submitterId: old.submitterId,
                voteOpenAt: old.voteOpenAt,
                voteCloseAt: old.voteCloseAt,
                supportCount: result.supportCount,
                opposeCount: result.opposeCount,
                myVote: result.myVote,
                createdAt: old.createdAt
            )
        }

        showTip(String(localized: "community.alias.board.tip.voteUpdated"))
    }

    private func showTip(_ message: String) {
        tipDismissTask?.cancel()
        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
            tipMessage = message
        }

        tipDismissTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(1.6))
            guard !Task.isCancelled else { return }
            if tipMessage == message {
                withAnimation {
                    tipMessage = nil
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        CommunityAliasVotingBoardView()
    }
}
