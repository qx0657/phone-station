import Foundation

@main
enum AdbCommandLineTest {
    static func main() {
        checkQuotedPipe()
        checkSmartQuotes()
        checkUnclosedQuote()
        checkSerialRejected()
        checkEmpty()
        checkBackslash()
        checkBattery()
        print("adb command line ok")
    }

    private static func checkQuotedPipe() {
        let tokens = try! AdbCommandLine.validate(#"shell "dumpsys window | grep mCurrentFocus""#)
        precondition(tokens == ["shell", "dumpsys window | grep mCurrentFocus"])
    }

    private static func checkSmartQuotes() {
        let tokens = try! AdbCommandLine.validate("shell \u{201C}dumpsys window\u{201D}")
        precondition(tokens == ["shell", "dumpsys window"])
        let single = try! AdbCommandLine.validate("shell \u{2018}input keyevent\u{2019}")
        precondition(single == ["shell", "input keyevent"])
    }

    private static func checkUnclosedQuote() {
        do {
            _ = try AdbCommandLine.validate(#"shell "dumpsys"#)
            preconditionFailure("unclosed quote should fail")
        } catch let error as AdbCommandLine.ParseError {
            precondition(error.message == "参数的引号没有成对。")
        } catch {
            preconditionFailure("unexpected error")
        }
    }

    private static func checkSerialRejected() {
        for source in ["shell -s ABC", "shell --serial ABC"] {
            do {
                _ = try AdbCommandLine.validate(source)
                preconditionFailure("serial flag should fail")
            } catch let error as AdbCommandLine.ParseError {
                precondition(error.message == "序列号由当前手机决定，参数里不要写 -s。")
            } catch {
                preconditionFailure("unexpected error")
            }
        }
    }

    private static func checkEmpty() {
        do {
            _ = try AdbCommandLine.validate("   ")
            preconditionFailure("empty command should fail")
        } catch let error as AdbCommandLine.ParseError {
            precondition(error.message == "这条命令没有参数。")
        } catch {
            preconditionFailure("unexpected error")
        }
    }

    private static func checkBackslash() {
        let escaped = try! AdbCommandLine.validate(#"shell echo hello\ world"#)
        precondition(escaped == ["shell", "echo", "hello world"])
        let literal = try! AdbCommandLine.validate(#"shell echo 'hello\ world'"#)
        precondition(literal == ["shell", "echo", "hello\\ world"])
    }

    private static func checkBattery() {
        let charging = BatteryReport.parse("level: 50\nscale: 100\nstatus: 2\n")
        precondition(charging == BatteryReport.Reading(percent: 50, charging: true))
        let idle = BatteryReport.parse("level: 80\nstatus: 3\n")
        precondition(idle == BatteryReport.Reading(percent: 80, charging: false))
        precondition(BatteryReport.parse("status: 2\n") == nil)
        let scaled = BatteryReport.parse("level: 50\nscale: 200\nstatus: 2\n")
        precondition(scaled == BatteryReport.Reading(percent: 25, charging: true))
    }
}
