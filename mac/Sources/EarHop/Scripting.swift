import AppKit

/// AppleScript commands (EarHop.sdef): tell application "EarHop" to take earbuds / release earbuds / send clipboard /
/// start sleep timer 30 / cancel sleep timer. Each runs the same command as the earhop:// links.
@objc(EHScriptCommand)
final class EHScriptCommand: NSScriptCommand {
    override func performDefaultImplementation() -> Any? {
        let name: String
        switch commandDescription.commandName {
        case "take earbuds": name = "take"
        case "release earbuds": name = "release"
        case "send clipboard": name = "send-clipboard"
        case "start sleep timer": name = "sleep"
        case "cancel sleep timer": name = "sleep-cancel"
        default: return false
        }
        let minutes = (directParameter as? NSNumber)?.intValue
        return (NSApp.delegate as? AppDelegate)?.run(command: name, minutes: minutes) ?? false
    }
}
