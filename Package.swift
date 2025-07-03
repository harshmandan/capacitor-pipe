// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorCommunityNpe",
    platforms: [.iOS(.v14)],
    products: [
        .library(
            name: "CapacitorCommunityNpe",
            targets: ["NPEPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "7.0.0")
    ],
    targets: [
        .target(
            name: "NPEPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/NPEPlugin"),
        .testTarget(
            name: "NPEPluginTests",
            dependencies: ["NPEPlugin"],
            path: "ios/Tests/NPEPluginTests")
    ]
)