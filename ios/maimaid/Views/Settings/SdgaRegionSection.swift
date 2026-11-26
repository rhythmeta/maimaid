import SwiftUI

/// Manual country/arcade selection for the SDGA cabinet pool.
///
/// The choice is persisted locally and never derived from the device location, matching
/// `SgwcmaidRegionSection`.
struct SdgaRegionSection: View {
  let model: SdgaImportViewModel

  var body: some View {
    Section {
      Picker(selection: country) {
        Text("import.sdga.auto").tag(String?.none)
        ForEach(model.countries, id: \.self) { code in
          Text(verbatim: RegionNames.displayName(code))
            .tag(Optional(code))
        }
      } label: {
        SettingsStyleRowLabel(
          icon: "globe.asia.australia", color: .indigo, title: "import.sdga.country")
      }
      .pickerStyle(.menu)

      if !model.places.isEmpty {
        Picker(selection: place) {
          Text("import.sdga.auto").tag(Int?.none)
          ForEach(model.places) { item in
            Text(verbatim: item.displayName).tag(Optional(item.placeID))
          }
        } label: {
          SettingsStyleRowLabel(
            icon: "mappin.and.ellipse", color: .teal, title: "import.sdga.place")
        }
        .pickerStyle(.menu)
      }
    } header: {
      Text("import.sdga.region")
    } footer: {
      Text("import.sdga.region.footer")
    }
  }

  private var country: Binding<String?> {
    Binding(get: { model.country }, set: { model.selectCountry($0) })
  }

  private var place: Binding<Int?> {
    Binding(get: { model.placeID }, set: { model.selectPlace($0) })
  }
}
