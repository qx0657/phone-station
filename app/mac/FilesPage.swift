import SwiftUI

struct FilesPage: View {
    @ObservedObject var station: Station

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("最近文件") { station.page = .main }
            if station.files.recentFiles.isEmpty {
                Text("截图和录屏完成后，会显示在这里。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(station.files.recentFiles.enumerated()), id: \.element.path) { index, file in
                        if index > 0 {
                            StationRows.groupDivider.padding(.leading, 56).padding(.trailing, 10)
                        }
                        recentFileRow(file)
                    }
                }
                .elevatedGroup()
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
            }
        }
        .onAppear { station.files.refresh() }
        .onDisappear { station.files.dismissPreview() }
    }

    private func recentFileRow(_ file: URL) -> some View {
        let name = file.lastPathComponent
        let copied = station.files.copiedRecentPath == file.path
        let hovered = station.files.hoveredRecentPath == file.path
        return HStack(spacing: 0) {
            Button {
                station.files.copy(file)
            } label: {
                HStack(spacing: 10) {
                    recentThumb(file)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Text(recentMeta(file))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Text(copied ? "已复制" : "复制")
                        .font(.system(size: 12))
                        .foregroundStyle(copied ? StationPalette.connected : Color.secondary)
                        .frame(width: 46, alignment: .trailing)
                }
                .padding(.leading, 10)
                .padding(.vertical, 7)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .accessibilityLabel(copied ? "已复制 \(name)" : "复制 \(name)")
            .help(RecentFileStyle.kind(for: file) == "录屏" ? "把文件复制到剪贴板" : "把图片复制到剪贴板")
            Button {
                station.files.reveal(file)
            } label: {
                Image(systemName: "arrow.up.forward.square")
                    .font(.system(size: 12))
                    .foregroundStyle(.tertiary)
                    .frame(width: 28, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .padding(.trailing, 6)
            .accessibilityLabel("在 Finder 中显示 \(name)")
            .help("在 Finder 中显示")
        }
        .background {
            HoverWash(
                radius: 0,
                onInside: { inside in station.files.setHover(file, inside: inside) },
                onFrame: hovered ? { rect, window in
                    station.files.setHoverAnchor(file, row: rect, host: window)
                } : nil)
        }
    }

    private func recentThumb(_ file: URL) -> some View {
        let symbol = RecentFileStyle.kind(for: file) == "录屏" ? "film" : "photo"
        return ZStack {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(StationPalette.background)
            if let image = station.files.recentThumbnails[file.path] {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 36, height: 36, alignment: .top)
                    .clipped()
            } else {
                Image(systemName: symbol)
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: 36, height: 36)
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        .accessibilityHidden(true)
    }

    private func recentMeta(_ file: URL) -> String {
        let kind = RecentFileStyle.kind(for: file)
        guard let date = station.files.recentModified[file.path] else { return kind }
        return "\(kind) · \(RecentFileStyle.date.string(from: date))"
    }
}
