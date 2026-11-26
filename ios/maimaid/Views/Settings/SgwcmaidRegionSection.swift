import SwiftUI

/// Manual region selection for the SGWCMAID cabinet pool.
///
/// The choice is persisted locally and never derived from the device location.
/// A menu-style picker keeps the selection inline instead of pushing a page.
struct SgwcmaidRegionSection: View {
  let model: SgwcmaidImportViewModel

  var body: some View {
    Section {
      Picker(selection: selection) {
        Text("import.sgwcmaid.region.auto")
          .tag(Int?.none)
        ForEach(model.provinces) { province in
          Text(verbatim: province.name)
            .tag(Optional(province.id))
        }
      } label: {
        // Same row style as the SDGA region pickers and the Settings rows.
        SettingsStyleRowLabel(
          icon: "map", color: .green, title: "import.sgwcmaid.region.title")
      }
      .pickerStyle(.menu)
    } header: {
      Text("import.sgwcmaid.region.header")
    }
  }

  private var selection: Binding<Int?> {
    Binding(
      get: { model.selectedProvinceID },
      set: { model.selectProvince($0) })
  }
}
