import Foundation

/// Touch sensor layout shared by rendering and hit testing.
nonisolated enum TouchGeometry {
    private static let touchBaseAngle = -Double.pi / 2
    private static let touchButtonAngleOffset = Double.pi / 8
    private static let touchButtonAngleStep = Double.pi / 4

    private static let sensorRadii: [Character: Double] = [
        "A": 400.0 / 480.0,
        "B": 220.0 / 480.0,
        "C": 0.0,
        "D": 410.0 / 480.0,
        "E": 310.0 / 480.0,
    ]

    private static let horizontalAB = [0, 8, 7, 6, 5, 4, 3, 2, 1]
    private static let horizontalDE = [0, 1, 8, 7, 6, 5, 4, 3, 2]
    private static let verticalAB = [0, 4, 3, 2, 1, 8, 7, 6, 5]
    private static let verticalDE = [0, 5, 4, 3, 2, 1, 8, 7, 6]
    private static let rotate180 = [0, 5, 6, 7, 8, 1, 2, 3, 4]

    /// Applies the mirror transform to a sensor name; the C sensor never moves.
    static func mirrorTouchSensor(_ position: String, _ mirrorMode: MirrorMode) -> String {
        guard mirrorMode != .none else { return position }
        let characters = Array(position)
        guard let region = characters.first else { return position }
        if region == "C" { return position }
        let sensor = characters.count > 1 ? Int(String(characters[1])) ?? 0 : 0
        let mapping: [Int]
        switch mirrorMode {
        case .horizontal:
            mapping = (region == "A" || region == "B") ? horizontalAB : horizontalDE
        case .vertical:
            mapping = (region == "A" || region == "B") ? verticalAB : verticalDE
        case .rotate180:
            mapping = rotate180
        case .none:
            return position
        }
        let mapped = (sensor >= 0 && sensor < mapping.count) ? mapping[sensor] : sensor
        return "\(region)\(mapped)"
    }

    /// Canvas-space position of a sensor point.
    static func sensorPoint(_ position: String, centerX: Double, centerY: Double, radius: Double,
                            mirrorMode: MirrorMode) -> ChartPoint {
        let mirrored = mirrorTouchSensor(position, mirrorMode)
        let characters = Array(mirrored)
        guard let region = characters.first else { return ChartPoint(centerX, centerY) }
        let sensor = characters.count > 1 ? Int(String(characters[1])) ?? 0 : 0
        if region == "C" { return ChartPoint(centerX, centerY) }

        let distance = radius * (sensorRadii[region] ?? 0.0)
        let angle: Double
        if region == "D" || region == "E" {
            angle = touchBaseAngle + Double(sensor - 1) * touchButtonAngleStep
        } else {
            angle = touchBaseAngle + touchButtonAngleOffset + Double(sensor - 1) * touchButtonAngleStep
        }
        return ChartPoint(centerX + cos(angle) * distance, centerY + sin(angle) * distance)
    }
}
