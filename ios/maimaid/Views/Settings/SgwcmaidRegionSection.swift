import SwiftUI

/// Manual region selection for the SGWCMAID cabinet pool.
///
/// The choice is persisted locally and never derived from the device location.
/// A menu-style picker keeps the selection inline instead of pushing a page.
struct SgwcmaidRegionSection: View {
  let model: SgwcmaidImportViewModel

  var body: some View {
    Section {
      Picker("import.sgwcmaid.region.title", selection: selection) {
        Text("import.sgwcmaid.region.auto")
          .tag(Int?.none)
        ForEach(model.provinces) { province in
          Text(verbatim: province.name)
            .tag(Optional(province.id))
        }
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
