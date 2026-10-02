import SwiftUI

struct CloudAccountSummary: View {
    let user: BackendAuthUser?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                Image(systemName: user == nil ? "person.badge.key.fill" : "person.crop.circle.badge.checkmark")
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 72, height: 72)
                    .background(.blue.gradient, in: .rect(cornerRadius: 18))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 6) {
                    if let user {
                        Text(verbatim: user.handle)
                            .font(.headline.bold())
                        Text(verbatim: user.email)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } else {
                        Text("Rhythmeta")
                            .font(.headline.bold())
                        Text("settings.cloud.login.subtitle")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            Divider()
            Label("settings.cloud.privacy.hint", systemImage: "lock.shield.fill")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(20)
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 28))
    }
}
