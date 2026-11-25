import SwiftUI

struct BestTableDXStarFilterSection: View {
    @Binding var selection: Set<Int>

    var body: some View {
        Section("bestTable.stats.stars") {
            FlowLayout {
                ForEach(0...5, id: \.self) { stars in
                    FilterChip(
                        title: "\(stars)★",
                        isSelected: selection.contains(stars),
                        color: .yellow
                    ) {
                        toggle(stars)
                    }
                    .accessibilityAddTraits(selection.contains(stars) ? .isSelected : [])
                }
            }
        }
    }

    private func toggle(_ stars: Int) {
        if selection.contains(stars) {
            selection.remove(stars)
        } else {
            selection.insert(stars)
        }
    }
}
