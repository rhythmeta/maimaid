import SwiftUI

struct CloudAccountNotice: View {
    let message: String
    let isError: Bool

    var body: some View {
        HStack {
            Image(systemName: isError ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                .foregroundStyle(isError ? .red : .green)
                .accessibilityHidden(true)
            Text(verbatim: message)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.black.opacity(0.82), in: .capsule)
        .accessibilityElement(children: .combine)
    }
}
