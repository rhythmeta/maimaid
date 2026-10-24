import SwiftUI

/// Shared 3/5-column cover grid with continuous pinch scaling and anchored settling.
struct MaimaiCoverGrid<Item, Header: View, Tile: View>: View {
    let items: [Item]
    let itemID: (Item) -> String
    let preferenceKey: String
    let contentTopPadding: CGFloat
    let contentBottomPadding: CGFloat
    let header: () -> Header
    let tile: (Item, Int, CGFloat, Bool) -> Tile

    @AppStorage private var savedColumns: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var zoom = 1.0
    @State private var initialZoom: Double?
    @State private var offset = 0.0
    @State private var viewport = 800.0
    @State private var headerHeight = 0.0
    @State private var anchorIndex = 0
    @State private var anchorY = 0.0
    @State private var scroll = ScrollPosition()

    init(
        items: [Item],
        itemID: @escaping (Item) -> String,
        preferenceKey: String,
        contentTopPadding: CGFloat = 0,
        contentBottomPadding: CGFloat = 96,
        @ViewBuilder header: @escaping () -> Header,
        @ViewBuilder tile: @escaping (Item, Int, CGFloat, Bool) -> Tile
    ) {
        self.items = items
        self.itemID = itemID
        self.preferenceKey = preferenceKey
        self.contentTopPadding = contentTopPadding
        self.contentBottomPadding = contentBottomPadding
        self.header = header
        self.tile = tile
        _savedColumns = AppStorage(wrappedValue: 5, preferenceKey)
    }

    var body: some View {
        GeometryReader { proxy in
            let horizontalPadding: CGFloat = 0
            let gridWidth = max(1, proxy.size.width - horizontalPadding * 2)
            let geometry = MaimaiCatalogGridGeometry(width: gridWidth, count: items.count, zoom: zoom)
            let gridTop = headerHeight + contentTopPadding

            ScrollView {
                VStack(spacing: 0) {
                    header()
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { _, value in
                            headerHeight = value
                        }

                    ZStack(alignment: .topLeading) {
                        Color.clear.frame(height: geometry.height)
                        ForEach(
                            geometry.visible(top: max(0, offset - gridTop), height: viewport),
                            id: \.self
                        ) { index in
                            tile(items[index], geometry.columns, geometry.size, initialZoom != nil)
                                .frame(width: geometry.size, height: geometry.size)
                                .offset(x: geometry.frame(index).minX, y: geometry.frame(index).minY)
                                .id(itemID(items[index]))
                        }
                    }
                    .frame(width: gridWidth, height: geometry.height, alignment: .topLeading)
                    .padding(.top, contentTopPadding)
                    .padding(.bottom, contentBottomPadding)
                }
            }
            .scrollPosition($scroll)
            .scrollDisabled(initialZoom != nil)
            .onScrollGeometryChange(for: Double.self) {
                $0.contentOffset.y + $0.contentInsets.top
            } action: { _, value in
                offset = max(0, value)
            }
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
                viewport = size.height
            }
            .simultaneousGesture(makePinchGesture(gridWidth: gridWidth, gridTop: gridTop))
            .onAppear { zoom = savedColumns == 3 ? 2 : 1 }
            .onChange(of: savedColumns) { _, value in
                guard initialZoom == nil else { return }
                zoom = value == 3 ? 2 : 1
            }
            .accessibilityAction(named: String(localized: "grid_zoom_in")) {
                savedColumns = 3
            }
            .accessibilityAction(named: String(localized: "grid_zoom_out")) {
                savedColumns = 5
            }
        }
    }

    private func makePinchGesture(gridWidth: CGFloat, gridTop: CGFloat) -> some Gesture {
        MagnifyGesture(minimumScaleDelta: 0.02)
            .onChanged { value in
                if initialZoom == nil {
                    initialZoom = zoom
                    let base = MaimaiCatalogGridGeometry(width: gridWidth, count: items.count, zoom: zoom)
                    let focusY = offset + value.startAnchor.y * viewport - gridTop
                    let row = max(0, Int(focusY / base.step))
                    let index = min(max(0, items.count - 1), row * base.columns + base.columns / 2)
                    anchorIndex = index
                    anchorY = gridTop + base.frame(index).midY - offset
                }

                let base = MaimaiCatalogGridGeometry(
                    width: gridWidth,
                    count: items.count,
                    zoom: initialZoom ?? zoom
                )
                zoom = base.zoomForSize(base.size * value.magnification)
                preserveAnchor(gridWidth: gridWidth, gridTop: gridTop)
            }
            .onEnded { _ in
                let target = zoom < 1.5 ? 1.0 : 2.0
                withAnimation(reduceMotion ? nil : .spring(duration: 0.3, bounce: 0)) {
                    zoom = target
                    preserveAnchor(gridWidth: gridWidth, gridTop: gridTop)
                }
                savedColumns = target == 1 ? 5 : 3
                initialZoom = nil
            }
    }

    private func preserveAnchor(gridWidth: CGFloat, gridTop: CGFloat) {
        let geometry = MaimaiCatalogGridGeometry(width: gridWidth, count: items.count, zoom: zoom)
        let maximum = max(0, gridTop + geometry.height + contentBottomPadding - viewport)
        let target = gridTop + geometry.frame(anchorIndex).midY - anchorY
        scroll.scrollTo(y: min(max(target, 0), maximum))
    }
}
