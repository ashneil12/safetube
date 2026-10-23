// swift-tools-version:5.7
// The filtering engine, ported from core/ (Kotlin) for the iPhone and iPad app.
// Plain Foundation, no UIKit, so `swift test` runs it on a Mac without a simulator.
import PackageDescription

let package = Package(
    name: "SafeTubeCore",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "SafeTubeCore", targets: ["SafeTubeCore"]),
    ],
    targets: [
        .target(name: "SafeTubeCore"),
        .testTarget(name: "SafeTubeCoreTests", dependencies: ["SafeTubeCore"]),
    ]
)
