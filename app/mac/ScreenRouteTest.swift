import Foundation

@main
enum ScreenRouteTest {
    static func main() {
        precondition(ScreenRoute.choose(hasFrame: true) == "native")
        precondition(ScreenRoute.choose(hasFrame: false) == "scrcpy")
        print("ScreenRouteTest passed")
    }
}
