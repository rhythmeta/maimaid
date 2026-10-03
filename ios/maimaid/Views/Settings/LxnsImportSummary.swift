import SwiftUI

struct LxnsImportSummary: View {
  let isConnected: Bool
  @ScaledMetric(relativeTo: .largeTitle) private var iconSize = 72

  var body: some View {
    VStack(alignment: .leading) {
      HStack {
        Image(systemName: isConnected ? "snowflake.circle.fill" : "link.badge.plus")
          .font(.largeTitle.bold())
          .foregroundStyle(.white)
          .frame(width: iconSize, height: iconSize)
          .background(
            (isConnected ? Color.cyan : Color.indigo).gradient,
            in: .rect(cornerRadius: 18)
          )
          .accessibilityHidden(true)

        VStack(alignment: .leading) {
          Text(isConnected ? "import.lxns.bound.header" : "import.lxns.step1.header")
            .font(.headline)
            .fontDesign(.rounded)
          Text(isConnected ? "import.lxns.status.connected" : "import.lxns.step1.footer")
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
        }
        Spacer(minLength: 0)
      }
      Divider()
      Label("import.lxns.summary.footer", systemImage: "square.and.arrow.down")
        .font(.footnote)
        .foregroundStyle(.secondary)
    }
    .padding(20)
  }
}
