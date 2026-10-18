import Foundation

/// 本地投屏和录屏的后端。有画面输入走原生解码，没有才用 scrcpy。
enum ScreenRoute {
    static func choose(hasFrame: Bool) -> String {
        hasFrame ? "native" : "scrcpy"
    }
}
