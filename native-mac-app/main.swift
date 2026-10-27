import Cocoa
import Foundation

// MARK: - Configuration & Colors
// Exact Apple macOS Sequoia iPhone Mirroring Palette
let BEZEL_COLOR = NSColor(red: 28/255.0, green: 28/255.0, blue: 30/255.0, alpha: 1.0) // #1C1C1E
let BORDER_COLOR = NSColor(white: 1.0, alpha: 0.14)
let SCREEN_BG = NSColor.black
let ACCENT_BLUE = NSColor(red: 0/255.0, green: 122/255.0, blue: 255/255.0, alpha: 1.0) // Apple System Blue

let ADB_BIN = ProcessInfo.processInfo.environment["ADB_BIN"] ?? "adb"
let TARGET_DEVICE = ProcessInfo.processInfo.environment["TARGET"] ?? "127.0.0.1:5555"
let COMPANION_URL = ProcessInfo.processInfo.environment["COMPANION_URL"] ?? "http://127.0.0.1:8899"

let PHONE_RES_W: CGFloat = 1080
let PHONE_RES_H: CGFloat = 2412

// MARK: - Phone Mirror View
class PhoneMirrorView: NSView {
    var imageView: NSImageView!
    var notchView: NSView!
    var homeBar: NSView!
    var topControlsView: NSView!
    
    var homeButton: NSButton!
    var recentsButton: NSButton!
    
    var isHoveringTop: Bool = false
    var trackingArea: NSTrackingArea?
    
    var dragStartPoint: NSPoint?
    var dragStartTime: Date?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setupLayers()
        setupControls()
    }
    
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupLayers()
        setupControls()
    }
    
    private func setupLayers() {
        self.wantsLayer = true
        self.layer?.cornerRadius = 46.0
        self.layer?.masksToBounds = true
        self.layer?.backgroundColor = BEZEL_COLOR.cgColor
        self.layer?.borderColor = BORDER_COLOR.cgColor
        self.layer?.borderWidth = 1.0
        
        // Inner Screen View
        let inset: CGFloat = 6.0
        let screenFrame = NSRect(x: inset, y: inset, width: bounds.width - (inset * 2), height: bounds.height - (inset * 2))
        imageView = NSImageView(frame: screenFrame)
        imageView.wantsLayer = true
        imageView.layer?.cornerRadius = 40.0
        imageView.layer?.masksToBounds = true
        imageView.layer?.backgroundColor = SCREEN_BG.cgColor
        imageView.imageScaling = .scaleAxesIndependently
        imageView.autoresizingMask = [.width, .height]
        addSubview(imageView)
        
        // Dynamic Island / Camera Notch
        let notchW: CGFloat = 100.0
        let notchH: CGFloat = 26.0
        let notchX = (bounds.width - notchW) / 2.0
        let notchY = bounds.height - notchH - 12.0
        notchView = NSView(frame: NSRect(x: notchX, y: notchY, width: notchW, height: notchH))
        notchView.wantsLayer = true
        notchView.layer?.cornerRadius = 13.0
        notchView.layer?.backgroundColor = NSColor.black.cgColor
        notchView.layer?.shadowColor = NSColor.black.cgColor
        notchView.layer?.shadowOpacity = 0.5
        notchView.layer?.shadowRadius = 4
        notchView.layer?.shadowOffset = CGSize(width: 0, height: -2)
        
        // Camera lens dot inside notch
        let lens = NSView(frame: NSRect(x: notchW - 24, y: 7, width: 12, height: 12))
        lens.wantsLayer = true
        lens.layer?.cornerRadius = 6
        lens.layer?.backgroundColor = NSColor(red: 15/255.0, green: 23/255.0, blue: 42/255.0, alpha: 1.0).cgColor
        lens.layer?.borderColor = NSColor(white: 1.0, alpha: 0.1).cgColor
        lens.layer?.borderWidth = 1
        notchView.addSubview(lens)
        
        addSubview(notchView)
        
        // Home Indicator Bar
        let barW: CGFloat = 134.0
        let barH: CGFloat = 5.0
        let barX = (bounds.width - barW) / 2.0
        let barY: CGFloat = 14.0
        homeBar = NSView(frame: NSRect(x: barX, y: barY, width: barW, height: barH))
        homeBar.wantsLayer = true
        homeBar.layer?.cornerRadius = 2.5
        homeBar.layer?.backgroundColor = NSColor(white: 1.0, alpha: 0.45).cgColor
        addSubview(homeBar)
    }
    
    private func setupControls() {
        // Top Hover Bar (Home & App Switcher pills, matching Sequoia iPhone Mirroring)
        topControlsView = NSView(frame: NSRect(x: bounds.width - 90, y: bounds.height - 38, width: 80, height: 26))
        topControlsView.wantsLayer = true
        topControlsView.layer?.cornerRadius = 13
        topControlsView.layer?.backgroundColor = NSColor(white: 0.1, alpha: 0.8).cgColor
        topControlsView.alphaValue = 0.0 // Hidden until hover
        
        // Home button
        homeButton = NSButton(frame: NSRect(x: 8, y: 3, width: 20, height: 20))
        homeButton.bezelStyle = .texturedRounded
        homeButton.isBordered = false
        homeButton.title = "⚪"
        homeButton.font = NSFont.systemFont(ofSize: 10)
        homeButton.target = self
        homeButton.action = #selector(onHomeClicked)
        topControlsView.addSubview(homeButton)
        
        // Recents button
        recentsButton = NSButton(frame: NSRect(x: 46, y: 3, width: 24, height: 20))
        recentsButton.bezelStyle = .texturedRounded
        recentsButton.isBordered = false
        recentsButton.title = "🔲"
        recentsButton.font = NSFont.systemFont(ofSize: 10)
        recentsButton.target = self
        recentsButton.action = #selector(onRecentsClicked)
        topControlsView.addSubview(recentsButton)
        
        addSubview(topControlsView)
    }
    
    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let existing = trackingArea {
            removeTrackingArea(existing)
        }
        trackingArea = NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .mouseMoved, .activeAlways], owner: self, userInfo: nil)
        addTrackingArea(trackingArea!)
    }
    
    override func mouseMoved(with event: NSEvent) {
        let loc = convert(event.locationInWindow, from: nil)
        let isTop = loc.y > (bounds.height - 60)
        if isTop != isHoveringTop {
            isHoveringTop = isTop
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.2
                self.topControlsView.animator().alphaValue = isTop ? 1.0 : 0.0
            }
        }
    }
    
    override func mouseExited(with event: NSEvent) {
        isHoveringTop = false
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.2
            self.topControlsView.animator().alphaValue = 0.0
        }
    }
    
    // MARK: - Input Mapping
    private func toDeviceCoords(_ point: NSPoint) -> (x: Int, y: Int) {
        let normX = max(0, min(1, point.x / bounds.width))
        let normY = max(0, min(1, (bounds.height - point.y) / bounds.height)) // Flip Y for Android
        return (Int(normX * PHONE_RES_W), Int(normY * PHONE_RES_H))
    }
    
    override func mouseDown(with event: NSEvent) {
        let loc = convert(event.locationInWindow, from: nil)
        dragStartPoint = loc
        dragStartTime = Date()
    }
    
    override func mouseUp(with event: NSEvent) {
        guard let start = dragStartPoint, let startTime = dragStartTime else { return }
        let end = convert(event.locationInWindow, from: nil)
        let durationMs = Int(Date().timeIntervalSince(startTime) * 1000)
        let dist = hypot(end.x - start.x, end.y - start.y)
        
        let p1 = toDeviceCoords(start)
        let p2 = toDeviceCoords(end)
        
        if dist < 6.0 {
            // Tap
            runAdbAsync(["shell", "input", "tap", "\(p1.x)", "\(p1.y)"])
        } else {
            // Swipe
            let safeDuration = max(150, min(600, durationMs))
            runAdbAsync(["shell", "input", "swipe", "\(p1.x)", "\(p1.y)", "\(p2.x)", "\(p2.y)", "\(safeDuration)"])
        }
        
        dragStartPoint = nil
        dragStartTime = nil
    }
    
    override func scrollWheel(with event: NSEvent) {
        let loc = convert(event.locationInWindow, from: nil)
        let p = toDeviceCoords(loc)
        let dy = event.scrollingDeltaY
        if abs(dy) > 2.0 {
            let swipeDist = Int(dy * 25.0)
            let startY = max(200, min(Int(PHONE_RES_H) - 200, p.y))
            let endY = max(100, min(Int(PHONE_RES_H) - 100, startY + swipeDist))
            runAdbAsync(["shell", "input", "swipe", "\(p.x)", "\(startY)", "\(p.x)", "\(endY)", "180"])
        }
    }
    
    @objc func onHomeClicked() {
        runAdbAsync(["shell", "input", "keyevent", "3"]) // KEYCODE_HOME
    }
    
    @objc func onRecentsClicked() {
        runAdbAsync(["shell", "input", "keyevent", "187"]) // KEYCODE_APP_SWITCH
    }
}

// MARK: - ADB & Network Helpers
func runAdbAsync(_ args: [String]) {
    DispatchQueue.global(qos: .userInitiated).async {
        let fullArgs = ["-s", TARGET_DEVICE] + args
        let task = Process()
        task.launchPath = ADB_BIN
        task.arguments = fullArgs
        try? task.run()
    }
}

// MARK: - App Delegate & Controller
class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    var window: NSWindow!
    var mirrorView: PhoneMirrorView!
    var isStreaming = true
    var lastHostClipboard = ""
    var lastDeviceClipboard = ""

    func applicationDidFinishLaunching(_ notification: Notification) {
        setupWindow()
        startScreenStream()
        startInvisibleClipboardSync()
    }
    
    private func setupWindow() {
        let width: CGFloat = 375
        let height: CGFloat = 812 // Standard modern iPhone / OnePlus aspect ratio
        
        let screenRect = NSScreen.main?.visibleFrame ?? NSRect(x: 100, y: 100, width: 1440, height: 900)
        let posX = screenRect.origin.x + (screenRect.width - width) / 2
        let posY = screenRect.origin.y + (screenRect.height - height) / 2
        
        let rect = NSRect(x: posX, y: posY, width: width, height: height)
        
        window = NSWindow(contentRect: rect,
                          styleMask: [.titled, .closable, .miniaturizable, .fullSizeContentView],
                          backing: .buffered,
                          defer: false)
        
        window.title = "Android Mirroring"
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.isMovableByWindowBackground = true
        window.backgroundColor = .clear
        window.isOpaque = false
        window.hasShadow = true
        window.level = .normal
        window.delegate = self
        
        mirrorView = PhoneMirrorView(frame: NSRect(x: 0, y: 0, width: width, height: height))
        window.contentView = mirrorView
        
        // Position window buttons (traffic lights) smoothly
        if let closeBtn = window.standardWindowButton(.closeButton),
           let miniBtn = window.standardWindowButton(.miniaturizeButton),
           let zoomBtn = window.standardWindowButton(.zoomButton) {
            zoomBtn.isHidden = true
            closeBtn.frame.origin = NSPoint(x: 18, y: height - 32)
            miniBtn.frame.origin = NSPoint(x: 38, y: height - 32)
        }
        
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    // MARK: - Screen Mirror Stream (Background Thread)
    private func startScreenStream() {
        DispatchQueue.global(qos: .userInteractive).async { [weak self] in
            while self?.isStreaming == true {
                let pipe = Pipe()
                let task = Process()
                task.launchPath = ADB_BIN
                task.arguments = ["-s", TARGET_DEVICE, "exec-out", "screencap", "-p"]
                task.standardOutput = pipe
                
                do {
                    try task.run()
                    let data = pipe.fileHandleForReading.readDataToEndOfFile()
                    task.waitUntilExit()
                    
                    if !data.isEmpty, let img = NSImage(data: data) {
                        DispatchQueue.main.async {
                            self?.mirrorView.imageView.image = img
                        }
                    }
                } catch {}
                
                // Low-latency frame pacing (approx 20-30 fps screencap stream)
                usleep(40000)
            }
        }
    }
    
    // MARK: - Invisible Universal Clipboard Sync
    private func startInvisibleClipboardSync() {
        Timer.scheduledTimer(withTimeInterval: 1.2, repeats: true) { [weak self] _ in
            self?.checkAndSyncClipboard()
        }
    }
    
    private func checkAndSyncClipboard() {
        // 1. Check Mac host clipboard
        let pb = NSPasteboard.general
        if let currentHost = pb.string(forType: .string), currentHost != lastHostClipboard, !currentHost.isEmpty {
            lastHostClipboard = currentHost
            // Push silently to phone companion
            if let url = URL(string: "\(COMPANION_URL)/api/clipboard") {
                var req = URLRequest(url: url)
                req.httpMethod = "POST"
                req.setValue("application/json", forHTTPHeaderField: "Content-Type")
                req.httpBody = try? JSONSerialization.data(withJSONObject: ["text": currentHost])
                URLSession.shared.dataTask(with: req).resume()
            }
        }
        
        // 2. Poll phone clipboard silently
        if let url = URL(string: "\(COMPANION_URL)/api/clipboard") {
            URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
                guard let self = self, let data = data,
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let clip = json["clipboard"] as? String,
                      !clip.isEmpty, clip != self.lastDeviceClipboard, clip != self.lastHostClipboard else { return }
                
                self.lastDeviceClipboard = clip
                self.lastHostClipboard = clip
                DispatchQueue.main.async {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(clip, forType: .string)
                }
            }.resume()
        }
    }

    // MARK: - Keyboard Handling
    func windowDidResignKey(_ notification: Notification) {}

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return true
    }
}

// Subclass NSApplication for native keyboard event interception
class ContinuityApplication: NSApplication {
    override func sendEvent(_ event: NSEvent) {
        if event.type == .keyDown {
            let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
            
            // Cmd+1 -> Back
            if flags == .command && event.charactersIgnoringModifiers == "1" {
                runAdbAsync(["shell", "input", "keyevent", "4"])
                return
            }
            // Cmd+2 -> Home
            if flags == .command && event.charactersIgnoringModifiers == "2" {
                runAdbAsync(["shell", "input", "keyevent", "3"])
                return
            }
            // Cmd+3 -> App Switcher (Recents)
            if flags == .command && event.charactersIgnoringModifiers == "3" {
                runAdbAsync(["shell", "input", "keyevent", "187"])
                return
            }
            // Cmd+V -> Paste Mac clipboard to Android
            if flags == .command && event.charactersIgnoringModifiers?.lowercased() == "v" {
                if let str = NSPasteboard.general.string(forType: .string) {
                    if let url = URL(string: "\(COMPANION_URL)/api/clipboard") {
                        var req = URLRequest(url: url)
                        req.httpMethod = "POST"
                        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
                        req.httpBody = try? JSONSerialization.data(withJSONObject: ["text": str])
                        URLSession.shared.dataTask(with: req).resume()
                    }
                    // Trigger paste keyevent
                    runAdbAsync(["shell", "input", "keyevent", "279"]) // KEYCODE_PASTE
                }
                return
            }
            // Escape -> Back
            if event.keyCode == 53 {
                runAdbAsync(["shell", "input", "keyevent", "4"])
                return
            }
            // Ordinary typing
            if let chars = event.characters, !flags.contains(.command), !flags.contains(.control) {
                if event.keyCode == 36 { // Enter
                    runAdbAsync(["shell", "input", "keyevent", "66"])
                    return
                } else if event.keyCode == 51 { // Backspace
                    runAdbAsync(["shell", "input", "keyevent", "67"])
                    return
                } else if chars.count == 1 {
                    let escaped = chars.replacingOccurrences(of: " ", with: "%s")
                        .replacingOccurrences(of: "'", with: "")
                        .replacingOccurrences(of: "\"", with: "")
                    runAdbAsync(["shell", "input", "text", escaped])
                    return
                }
            }
        }
        super.sendEvent(event)
    }
}

// MARK: - App Entrypoint
let app = ContinuityApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.run()
