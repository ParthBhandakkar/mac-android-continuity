import Cocoa
import WebKit

class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    var window: NSWindow!
    var webView: WKWebView!

    func applicationDidFinishLaunching(_ notification: Notification) {
        let width: CGFloat = 720
        let height: CGFloat = 780
        
        let screenRect = NSScreen.main?.visibleFrame ?? NSRect(x: 100, y: 100, width: 1440, height: 900)
        let posX = screenRect.origin.x + (screenRect.width - width) / 2
        let posY = screenRect.origin.y + (screenRect.height - height) / 2
        let rect = NSRect(x: posX, y: posY, width: width, height: height)

        window = NSWindow(contentRect: rect,
                          styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                          backing: .buffered,
                          defer: false)

        window.title = "Continuity Hub"
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.isMovableByWindowBackground = true
        window.backgroundColor = NSColor(red: 28/255.0, green: 28/255.0, blue: 30/255.0, alpha: 1.0) // #1C1C1E
        window.hasShadow = true
        window.delegate = self

        // Configure WKWebView
        let config = WKWebViewConfiguration()
        webView = WKWebView(frame: NSRect(x: 0, y: 0, width: width, height: height), configuration: config)
        webView.autoresizingMask = [.width, .height]
        webView.setValue(false, forKey: "drawsBackground") // Transparent background

        // Load HTML dynamically from bundle, relative path, or bridge
        var targetURL: URL? = Bundle.main.url(forResource: "hub_ui", withExtension: "html")
        if targetURL == nil {
            let execDir = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent()
            let sibling = execDir.appendingPathComponent("hub_ui.html")
            let inResources = execDir.deletingLastPathComponent().appendingPathComponent("Resources/hub_ui.html")
            let localFile = URL(fileURLWithPath: "hub_ui.html")
            
            if FileManager.default.fileExists(atPath: sibling.path) {
                targetURL = sibling
            } else if FileManager.default.fileExists(atPath: inResources.path) {
                targetURL = inResources
            } else if FileManager.default.fileExists(atPath: localFile.path) {
                targetURL = localFile
            } else {
                targetURL = URL(string: "http://127.0.0.1:8898")
            }
        }

        if let fileURL = targetURL {
            if fileURL.isFileURL {
                webView.loadFileURL(fileURL, allowingReadAccessTo: fileURL.deletingLastPathComponent())
            } else {
                webView.load(URLRequest(url: fileURL))
            }
        }

        window.contentView = webView
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return true
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
