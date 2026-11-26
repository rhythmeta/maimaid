import SwiftUI

/// The public exit address the title server will see.
///
/// Informational only: the endpoints are region filtered, so seeing the address and its
/// carrier is the first thing to check when an import fails.
struct SdgaEgressSection: View {
  let egress: SdgaEgress?
  let isLoading: Bool
  let refresh: () -> Void

  var body: some View {
    Section {
      HStack(spacing: 16) {
        Image(systemName: "network")
          .font(.title2)
          .foregroundStyle(.tint)
          .frame(width: 32)
          .accessibilityHidden(true)
        VStack(alignment: .leading, spacing: 2) {
          Text(address)
            .font(.body.monospaced())
          if let egress, !detail(egress).isEmpty {
            Text(detail(egress))
              .font(.footnote)
              .foregroundStyle(.secondary)
          }
        }
        Spacer()
        Button(action: refresh) {
          Image(systemName: "arrow.clockwise")
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(Text("import.sdga.egress.refresh"))
      }
    } header: {
      Text("import.sdga.egress")
    } footer: {
      Text("import.sdga.egress.hint")
    }
  }

  private var address: String {
    if let egress { return egress.address }
    return isLoading
      ? String(localized: "import.sdga.egress.loading")
      : String(localized: "import.sdga.egress.unknown")
  }

  /// `地区 · ISP`, the same wording as the Android card.
  private func detail(_ value: SdgaEgress) -> String {
    var parts: [String] = []
    if let code = value.countryCode {
      parts.append(Locale.current.localizedString(forRegionCode: code) ?? code)
    }
    if let isp = value.isp, !isp.isEmpty { parts.append(isp) }
    return parts.joined(separator: " · ")
  }
}
