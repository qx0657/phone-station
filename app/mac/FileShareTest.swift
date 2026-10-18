import Foundation

@main
enum FileShareTest {
    static func main() {
        precondition(FileShare.phoneDownload("photo.png") == "/storage/emulated/0/Download/photo.png")
        precondition(FileShare.phoneDownload("..") == nil)
        precondition(FileShare.phoneDownload("a/b") == nil)
        precondition(FileShare.macName("/storage/emulated/0/Download/photo.png") == "photo.png")
        precondition(FileShare.macName("/storage/emulated/0/Android/data/secret") == nil)
        precondition(FileShare.macName("/tmp/a") == nil)
        precondition(FileShare.shareURL(endpoint: "http://127.0.0.1:18765/mcp")?.path == "/v2/share")
        precondition(FileShare.shareURL(endpoint: "notaurl") == nil)
        print("FileShareTest ok")
    }
}
