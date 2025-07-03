import Foundation

@objc public class NPE: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
