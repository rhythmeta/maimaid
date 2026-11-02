import SwiftUI

struct ScoreTintBadge: View {
    let text: String
    let tint: Color
    var font: Font = .caption.bold()

    var body: some View {
        Text(verbatim: text)
            .font(font)
            .foregroundStyle(tint)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(tint.opacity(0.14), in: .rect(cornerRadius: 5))
            .fixedSize()
    }
}
