import AppKit

/// EarHop in the Share menu (Finder, Preview, Photos export and others). The extension is sandboxed and holds no settings,
/// so it only collects the shared files and hands their paths to the EarHop app with an earhop://send-files link.
/// EarHop then asks which device gets them and sends them over Wi-Fi like the "Send file" button.
final class ShareViewController: NSViewController {
    override func loadView() { view = NSView(frame: NSRect(x: 0, y: 0, width: 1, height: 1)) }

    override func viewDidLoad() {
        super.viewDidLoad()
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        var paths: [String] = []
        let lock = NSLock()
        let group = DispatchGroup()
        for p in providers where p.hasItemConformingToTypeIdentifier("public.file-url") {
            group.enter()
            p.loadItem(forTypeIdentifier: "public.file-url", options: nil) { item, _ in
                var url: URL?
                if let u = item as? URL { url = u }
                else if let d = item as? Data { url = URL(dataRepresentation: d, relativeTo: nil) }
                else if let s = item as? String { url = URL(string: s) }
                if let u = url, u.isFileURL { lock.lock(); paths.append(u.path); lock.unlock() }
                group.leave()
            }
        }
        group.notify(queue: .main) { [weak self] in
            var c = URLComponents()
            c.scheme = "earhop"
            c.host = "send-files"
            c.queryItems = paths.map { URLQueryItem(name: "path", value: $0) }
            if !paths.isEmpty, let link = c.url { NSWorkspace.shared.open(link) }
            self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        }
    }
}
