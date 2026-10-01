//
//  maimaidApp.swift
//  maimaid
//
//  Created by 西 宮缄 on 2/23/26.
//

import SwiftData
import SwiftUI

@main
struct MaimaidApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var collectionImportCoordinator = CollectionImportCoordinator()

    private let sharedModelContainer: ModelContainer = {
        do {
            let container = try ModelContainer(
                for: Song.self,
                Sheet.self,
                Score.self,
                PlayRecord.self,
                SyncConfig.self,
                MaimaiIcon.self,
                UserProfile.self,
                CommunityAliasCache.self,
                SongCollection.self,
                SongCollectionItem.self
            )
            try CloudSnapshotStore.recover(context: container.mainContext)
            return container
        } catch {
            fatalError("Failed to create ModelContainer: \(error)")
        }
    }()

    var body: some Scene {
        WindowGroup {
            MainTabView()
                .disabled(CloudBackupService.shared.isBusy)
                .overlay {
                    if CloudBackupService.shared.isBusy {
                        ZStack {
                            Rectangle().fill(.ultraThinMaterial).ignoresSafeArea()
                            ProgressView()
                        }
                    }
                }
                .environment(collectionImportCoordinator)
                .onOpenURL { url in
                    guard !CloudBackupService.shared.isBusy else { return }
                    if CollectionSharingService.isCollectionLink(url) {
                        Task {
                            collectionImportCoordinator.prepareImport(from: url.absoluteString)
                        }
                    } else {
                        BackendSessionManager.shared.handleAuthRedirect(url)
                    }
                }
        }
        .modelContainer(sharedModelContainer)
        .onChange(of: scenePhase) { _, newPhase in
            guard newPhase == .active else { return }

            Task { @MainActor in
                guard BackendSessionManager.shared.isConfigured else { return }

                await BackendSessionManager.shared.checkSession()
                await MaimaiDataFetcher.shared.syncApprovedCommunityAliasesIfNeeded(
                    container: sharedModelContainer
                )
            }
        }
    }
}
