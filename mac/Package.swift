// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "EarHop",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "EarHop",
            path: "Sources/EarHop",
            exclude: ["Info.plist"],
            linkerSettings: [
                .unsafeFlags(["-Xlinker", "-sectcreate", "-Xlinker", "__TEXT",
                              "-Xlinker", "__info_plist", "-Xlinker", "Sources/EarHop/Info.plist"])
            ]
        )
    ]
)
