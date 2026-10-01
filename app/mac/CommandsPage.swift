import SwiftUI

struct CommandsPage: View {
    @ObservedObject var station: Station

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StationRows.subpageHeader("adb 命令") { station.page = .main }
            VStack(alignment: .leading, spacing: 12) {
                Text("点一行执行。铅笔用来修改。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if !station.commands.commands.isEmpty {
                    VStack(spacing: 0) {
                        ForEach(Array(station.commands.commands.enumerated()), id: \.element.id) { index, command in
                            if index > 0 {
                                StationRows.groupDivider.padding(.horizontal, 10)
                            }
                            commandRow(command)
                        }
                    }
                    .elevatedGroup()
                }
                if station.feedback.activity != nil {
                    StationRows.feedbackBanner(station.feedback.activity ?? "", busy: true)
                }
                if let output = station.commands.commandOutput {
                    commandOutputCard(output)
                }
                StationRows.navigationRow("新建命令", symbol: "plus") { station.commands.beginNew() }
                    .elevatedGroup()
                shellRow
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func commandRow(_ command: SavedAdbCommand) -> some View {
        HStack(spacing: 0) {
            Button {
                station.commands.run(command)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(command.name)
                        .font(.body)
                    Text(command.arguments)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .disabled(!station.canOperate)
            .opacity(station.canOperate ? 1 : 0.4)
            Button {
                station.commands.beginEdit(command)
            } label: {
                Image(systemName: "pencil")
                    .font(.system(size: 12))
                    .foregroundStyle(.tertiary)
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .padding(.trailing, 6)
            .accessibilityLabel("编辑\(command.name)")
            .help("编辑\(command.name)")
        }
        .background { HoverWash(radius: 0) }
    }

    private func commandOutputCard(_ output: CommandOutput) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(output.name)
                    .font(.body)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Button("复制") { station.commands.copyOutput() }
                    .buttonStyle(.borderless)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Text(output.text)
                .font(.system(size: 11, design: .monospaced))
                .textSelection(.enabled)
                .lineLimit(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(8)
                .background(StationPalette.background, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .padding(10)
        .elevatedGroup()
    }

    private var shellRow: some View {
        Button {
            station.commands.openDeviceShell()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "terminal")
                    .frame(width: 18)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    Text("在终端中打开 shell")
                    Text("在「终端」里打开，序列号已经绑上")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                Image(systemName: "arrow.up.forward.square")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .background { HoverWash(radius: 0) }
        .font(.body)
        .disabled(!station.canOperate)
        .opacity(station.canOperate ? 1 : 0.4)
        .elevatedGroup()
    }
}

struct CommandEditor: View {
    @ObservedObject var commands: CommandSession

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                commands.openPage(.commands)
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "chevron.left")
                        .font(.caption.weight(.semibold))
                        .accessibilityHidden(true)
                    Text(commands.commandDraft.id == nil ? "新建命令" : "编辑命令")
                        .font(.system(size: 16, weight: .semibold))
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .accessibilityLabel("返回")
            VStack(alignment: .leading, spacing: 14) {
                fieldBlock("名称") {
                    TextField("例如 前台应用", text: $commands.commandDraft.name)
                        .textFieldStyle(.plain)
                        .font(.system(size: 13))
                }
                fieldBlock("参数") {
                    ZStack(alignment: .topLeading) {
                        TextEditor(text: $commands.commandDraft.arguments)
                            .font(.system(size: 12, design: .monospaced))
                            .scrollContentBackground(.hidden)
                            .frame(height: 68)
                        if commands.commandDraft.arguments.isEmpty {
                            Text("shell \"dumpsys window | grep mCurrentFocus\"")
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(.tertiary)
                                .lineLimit(2)
                                .padding(.top, 8)
                                .padding(.leading, 5)
                                .allowsHitTesting(false)
                        }
                    }
                }
                Text("这些参数接在 adb -s 序列号 后面，不经过本机 shell。管道放在一对引号里，在手机上执行。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let errorText = commands.commandEditError {
                    Text(errorText)
                        .font(.subheadline)
                        .foregroundStyle(StationPalette.recording)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Button {
                    commands.save()
                } label: {
                    Text("保存")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                if let id = commands.commandDraft.id {
                    Button("删除这条命令") { commands.delete(id: id) }
                        .buttonStyle(.borderless)
                        .foregroundStyle(StationPalette.recording)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func fieldBlock<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(.secondary)
            content()
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(StationPalette.tile)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.12), lineWidth: 1)
                }
        }
    }
}
