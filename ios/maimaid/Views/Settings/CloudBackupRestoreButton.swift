import SwiftUI

struct CloudBackupRestoreButton: View {
    let backup: CloudBackup
    var restore: () -> Void

    @State private var showingConfirmation = false

    var body: some View {
        Button {
            showingConfirmation = true
        } label: {
            CloudBackupRow(backup: backup)
        }
        .buttonStyle(.plain)
        .confirmationDialog(
            "settings.cloud.restore",
            isPresented: $showingConfirmation,
            titleVisibility: .visible
        ) {
            Button("settings.cloud.restore", role: .destructive, action: restore)
        } message: {
            Text("settings.cloud.restore.replaceHint")
        }
    }
}
