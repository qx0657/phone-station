import AppKit
import Foundation

@MainActor
final class RecentFilesSession: ObservableObject {
    @Published private(set) var recentFiles: [URL] = []
    @Published private(set) var recentModified: [String: Date] = [:]
    @Published private(set) var recentThumbnails: [String: NSImage] = [:]
    @Published private(set) var hoveredRecentPath: String?
    @Published private(set) var copiedRecentPath: String?

    private let recentPreview = RecentPreviewPanel()
    private var recentHoverAnchor: NSRect?
    private weak var recentHost: NSWindow?
    private var thumbnailLoad: Task<Void, Never>?
    private var thumbnailToken = 0
    private var copyFeedback: Task<Void, Never>?

    func refresh() {
        let home = FileManager.default.homeDirectoryForCurrentUser
        let folders = [home.appendingPathComponent("Pictures/scrcpy"), home.appendingPathComponent("Movies/scrcpy")]
        let dated: [(URL, Date)] = folders.flatMap { folder in
            (try? FileManager.default.contentsOfDirectory(
                at: folder,
                includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey],
                options: [.skipsHiddenFiles])) ?? []
        }
        .filter { ["png", "mp4", "mkv"].contains($0.pathExtension.lowercased()) }
        .map { url in
            let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return (url, modified)
        }
        .sorted { $0.1 > $1.1 }
        .prefix(8)
        .map { $0 }
        recentFiles = dated.map(\.0)
        recentModified = Dictionary(uniqueKeysWithValues: dated.map { ($0.0.path, $0.1) })
        let keep = Set(recentFiles.map(\.path))
        recentThumbnails = recentThumbnails.filter { keep.contains($0.key) }
        if let hoveredRecentPath, !keep.contains(hoveredRecentPath) {
            dismissPreview()
        }
        loadRecentThumbnails()
    }

    func setHover(_ url: URL, inside: Bool) {
        if inside {
            hoveredRecentPath = url.path
        } else if hoveredRecentPath == url.path {
            dismissPreview()
        }
    }

    func setHoverAnchor(_ url: URL, row: NSRect, host: NSWindow) {
        guard hoveredRecentPath == url.path else { return }
        let sameRow = recentHoverAnchor?.equalTo(row) ?? false
        let sameHost = recentHost === host
        recentHoverAnchor = row
        recentHost = host
        if sameRow, sameHost, recentPreview.isVisible { return }
        layoutPreview()
    }

    func dismissPreview() {
        hoveredRecentPath = nil
        recentHoverAnchor = nil
        recentHost = nil
        recentPreview.hide()
    }

    func copy(_ url: URL) {
        let ext = url.pathExtension.lowercased()
        Task.detached(priority: .userInitiated) { [weak self] in
            let png = ext == "png" ? try? Data(contentsOf: url) : nil
            let path = ext == "png" ? nil : url.path
            let fileURL = url.absoluteString
            let owner = self
            await MainActor.run {
                owner?.writeCopy(url: url, png: png, path: path, fileURL: fileURL)
            }
        }
    }

    func reveal(_ url: URL) {
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    private func writeCopy(url: URL, png: Data?, path: String?, fileURL: String) {
        let board = NSPasteboard.general
        board.clearContents()
        let item = NSPasteboardItem()
        if let png {
            item.setData(png, forType: .png)
        }
        if let path {
            item.setString(path, forType: .string)
        }
        item.setString(fileURL, forType: .fileURL)
        guard board.writeObjects([item]) else { return }
        copiedRecentPath = url.path
        copyFeedback?.cancel()
        copyFeedback = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled, self.copiedRecentPath == url.path else { return }
            self.copiedRecentPath = nil
        }
    }

    private func loadRecentThumbnails() {
        thumbnailLoad?.cancel()
        thumbnailToken += 1
        let token = thumbnailToken
        let jobs = recentFiles.filter { recentThumbnails[$0.path] == nil }
        guard !jobs.isEmpty else { return }
        thumbnailLoad = Task.detached(priority: .utility) { [weak self] in
            for url in jobs {
                if Task.isCancelled { return }
                let cg = RecentFileThumbnail.cgImage(for: url, maxPixel: 720)
                let owner = self
                await MainActor.run {
                    owner?.storeThumbnail(cg, for: url, token: token)
                }
            }
        }
    }

    private func storeThumbnail(_ cg: CGImage?, for url: URL, token: Int) {
        guard thumbnailToken == token else { return }
        guard recentFiles.contains(where: { $0.path == url.path }) else { return }
        guard let cg else { return }
        let scale: CGFloat = 2
        recentThumbnails[url.path] = NSImage(
            cgImage: cg,
            size: NSSize(width: CGFloat(cg.width) / scale, height: CGFloat(cg.height) / scale))
        if hoveredRecentPath == url.path {
            layoutPreview()
        }
    }

    private func layoutPreview() {
        guard let path = hoveredRecentPath,
              let url = recentFiles.first(where: { $0.path == path }),
              let row = recentHoverAnchor,
              let host = recentHost else {
            recentPreview.hide()
            return
        }
        recentPreview.present(
            name: url.lastPathComponent,
            image: recentThumbnails[path],
            row: row,
            host: host)
    }
}
