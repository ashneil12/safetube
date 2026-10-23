import SafeTubeCore
import UIKit
import WebKit

/// The PIN, first-run setup and parent settings. The screens themselves are the page in
/// app/src/main/assets/parent/ (shared with Android), shown in a web view that can't load anything
/// else; this class is the `SafeTubeParent` bridge between that page and the app's settings and
/// decision cache. The iPhone counterpart of ParentActivity.kt.
///
/// parent.js calls the bridge synchronously (B.state(), B.checkPin(pin)...), which WKWebView has no
/// direct way to do. The page's window.prompt() does block until the app answers, so the shim
/// below sends every call through prompt() and the answer comes back as the prompt's text.
final class ParentViewController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    static let scheme = "safetube"
    private static let pageUrl = URL(string: "safetube://app/parent/index.html")!
    private static let bridgeToken = "safetube-parent-bridge"

    private let prefs = Prefs.shared
    private let store = SqliteVerdictStore.shared
    private let currentUrl: String?
    /// A hide request from the YouTube screen that needs the parent's PIN.
    private let blockRequest: [String: Any]?
    private let io = DispatchQueue(label: "app.safetube.parent")
    private var webView: WKWebView!
    private let privacyCover = UIView()
    /// Set once the PIN has been entered (or first created) in this screen.
    private var unlocked = false

    /// Called after the screen has gone, whatever happened in it.
    var onClose: (() -> Void)?
    /// Called when the PIN was right for a hide request.
    var onConfirmBlock: (() -> Void)?

    init(currentUrl: String?, blockRequest: [String: Any]?) {
        self.currentUrl = currentUrl
        self.blockRequest = blockRequest
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        isModalInPresentation = true
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        // The first screen is always the PIN pad, so match its colour while the page loads.
        let navy = UIColor(red: 0x14 / 255.0, green: 0x18 / 255.0, blue: 0x2B / 255.0, alpha: 1)
        view.backgroundColor = navy

        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.setURLSchemeHandler(BundleSchemeHandler(), forURLScheme: ParentViewController.scheme)
        config.userContentController.addUserScript(
            WKUserScript(source: ParentViewController.bridgeShim, injectionTime: .atDocumentStart, forMainFrameOnly: true)
        )
        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = navy
        webView.scrollView.backgroundColor = navy
        webView.allowsLinkPreview = false
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        webView.load(URLRequest(url: ParentViewController.pageUrl))

        // Keep settings (and API keys) out of the app switcher's snapshot.
        privacyCover.backgroundColor = navy
        privacyCover.frame = view.bounds
        privacyCover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        privacyCover.isHidden = true
        view.addSubview(privacyCover)
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(hideContent), name: UIApplication.willResignActiveNotification, object: nil)
        nc.addObserver(self, selector: #selector(showContent), name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    @objc private func hideContent() { privacyCover.isHidden = false }
    @objc private func showContent() { privacyCover.isHidden = true }

    private func finish() {
        let done = onClose
        dismiss(animated: true) { done?() }
    }

    // ------------------------------------------------------------------ the page's bridge

    private static let bridgeShim = """
        (function () {
          function ask(m, args) {
            var r = window.prompt('\(bridgeToken)', JSON.stringify({ m: m, a: args }));
            return r == null ? '' : String(r);
          }
          function s(v) { return v == null ? '' : String(v); }
          window.SafeTubeParent = {
            state: function () { return ask('state', []); },
            lists: function () { return ask('lists', []); },
            checkPin: function (pin) { return ask('checkPin', [s(pin)]) === 'true'; },
            setPin: function (pin) { ask('setPin', [s(pin)]); },
            save: function (json) { ask('save', [s(json)]); },
            call: function (name, args, id) { ask('call', [s(name), s(args), Number(id)]); },
            allow: function (id, title) { ask('allow', [s(id), s(title)]); },
            undo: function (id) { ask('undo', [s(id)]); },
            blockVideo: function (id, title) { ask('blockVideo', [s(id), s(title)]); },
            blockChannel: function (json) { ask('blockChannel', [s(json)]); },
            unblockChannel: function (entry) { ask('unblockChannel', [s(entry)]); },
            forgetAi: function () { ask('forgetAi', []); },
            openLink: function (url) { ask('openLink', [s(url)]); },
            finishSetup: function () { ask('finishSetup', []); },
            close: function () { ask('close', []); },
            confirmBlock: function () { ask('confirmBlock', []); }
          };
        })();
        """

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        guard prompt == ParentViewController.bridgeToken,
              frame.isMainFrame, frame.securityOrigin.protocol == ParentViewController.scheme,
              let req = JSON.object(defaultText ?? "") else {
            completionHandler(nil)
            return
        }
        let args = req["a"] as? [Any] ?? []
        let arg = { (i: Int) -> String in
            guard i < args.count else { return "" }
            if let s = args[i] as? String { return s }
            if let n = JSON.number(args[i]) { return String(Int(n)) }
            return ""
        }
        completionHandler(handle(JSON.optString(req, "m"), arg))
    }

    // Plain alerts and confirms from the page, should it ever use them.
    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(a, animated: true)
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let a = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(false) })
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        present(a, animated: true)
    }

    /// Everything the page can ask of the app. Runs on the main thread.
    private func handle(_ method: String, _ arg: (Int) -> String) -> String {
        switch method {
        case "state":
            return JSON.string(stateJson())
        case "lists":
            return lists()
        case "checkPin":
            let ok = prefs.checkPin(arg(0))
            if ok { unlocked = true }
            return ok ? "true" : "false"
        case "setPin":
            // First-run setup, or a parent who already got past the PIN pad.
            let pin = arg(0)
            if allowed(), (4...8).contains(pin.count), pin.allSatisfy({ $0.isASCII && $0.isNumber }) {
                prefs.setPin(pin)
                unlocked = true
            }
        case "save":
            save(arg(0))
        case "call":
            call(arg(0), arg(1), Int(arg(2)) ?? 0)
        case "allow":
            let id = arg(0), title = arg(1)
            changed { store.setOverride(id, .allow, title: title.isBlank ? nil : title, by: "parent") }
        case "undo":
            let id = arg(0)
            changed { store.setOverride(id, nil) }
        case "blockVideo":
            let id = arg(0), title = arg(1)
            if VideoInfo.isValidId(id) { changed { store.setOverride(id, .block, title: title.isBlank ? nil : title, by: "parent") } }
        case "blockChannel":
            let entries = (JSON.array(arg(0)) ?? []).compactMap { ($0 as? String)?.trimmed }.filter { !$0.isEmpty }
            changed { entries.forEach(prefs.addBlockedChannel) }
        case "unblockChannel":
            let entry = arg(0)
            changed { prefs.removeBlockedChannel(entry) }
        case "forgetAi":
            changed { store.clearDecisions() }
        case "openLink":
            let link = arg(0)
            if link.hasPrefix("https://"), let url = URL(string: link) {
                DispatchQueue.main.async { UIApplication.shared.open(url) }
            }
        case "finishSetup", "close":
            DispatchQueue.main.async { self.finish() }
        case "confirmBlock":
            // The PIN was right for a hide request from the YouTube screen.
            DispatchQueue.main.async {
                if self.blockRequest != nil && self.allowed() { self.onConfirmBlock?() }
                self.finish()
            }
        default:
            break
        }
        return ""
    }

    private var mode: String {
        if blockRequest != nil { return "block" }
        if !prefs.hasPin { return "setup" }
        return "unlock"
    }

    private func stateJson() -> [String: Any] {
        [
            "mode": mode,
            "pinLength": prefs.pinLength,
            "version": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
            "current": JSON.orNull(currentPage()),
            "block": JSON.orNull(blockRequest),
            "settings": allowed() ? settingsJson() as Any : NSNull(),
            "defaults": [
                "geminiBaseUrl": AppDefaults.geminiBaseUrl,
                "geminiModel": AppDefaults.geminiModel,
                "models": [
                    "CLEF": ProviderConfig.defaultClefModel,
                    "JEV": ProviderConfig.defaultJevModel,
                    "ANTHROPIC": ProviderConfig.defaultAnthropicModel,
                ],
            ] as [String: Any],
        ]
    }

    private func lists() -> String {
        guard allowed() else { return "{}" }
        let choices = store.parentChoices()
        let byChild = Set(choices.filter { $0.verdict == .block && $0.by == "child" }.map { $0.id })
        let result: [String: Any] = [
            "stats": ["checkedToday": prefs.checkedToday(), "hiddenToday": store.hiddenSince(startOfToday())],
            "hidden": store.recentlyHidden().map { h -> [String: Any] in
                ["id": h.id, "title": h.title, "channel": h.channel, "reason": byChild.contains(h.id) ? "Set by child" : h.reason]
            },
            "blocked": choices.filter { $0.verdict == .block }.map { ["id": $0.id, "title": $0.title, "by": $0.by] },
            "allowed": choices.filter { $0.verdict == .allow }.map { ["id": $0.id, "title": $0.title] },
            "channels": prefs.blockedChannelList(),
        ]
        return JSON.string(result)
    }

    private func save(_ json: String) {
        guard allowed(), let o = JSON.object(json) else { return }
        prefs.rules = JSON.optString(o, "rules").trimmed
        var seen = Set<String>()
        let words = (o["keywords"] as? [Any] ?? []).compactMap { ($0 as? String)?.trimmed }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
        prefs.keywords = words.joined(separator: ", ")
        prefs.strictness = Strictness(rawValue: JSON.optString(o, "strictness")) ?? .strict
        if let p = o["provider"] as? [String: Any] {
            prefs.providerType = ProviderType(rawValue: JSON.optString(p, "type")) ?? .none
            prefs.accountId = JSON.optString(p, "accountId").trimmed
            prefs.apiKey = JSON.optString(p, "apiKey").trimmed
            prefs.baseUrl = JSON.optString(p, "baseUrl").trimmed
            prefs.model = JSON.optString(p, "model").trimmed
            prefs.sendThumbnails = JSON.optBool(p, "sendThumbnails", true)
        }
        if let h = o["helper"] as? [String: Any] {
            prefs.helperType = ProviderType(rawValue: JSON.optString(h, "type")) ?? .none
            prefs.helperKey = JSON.optString(h, "apiKey").trimmed
            prefs.helperBaseUrl = JSON.optString(h, "baseUrl").trimmed
            prefs.helperModel = JSON.optString(h, "model").trimmed
        }
        prefs.hideShorts = JSON.optBool(o, "hideShorts", true)
        prefs.hideComments = JSON.optBool(o, "hideComments", true)
        prefs.kidsCanBlock = JSON.optBool(o, "kidsCanBlock", true)
        prefs.blockAds = JSON.optBool(o, "blockAds", true)
        prefs.bumpVersion()
    }

    /// Slow work; the answer goes back to window.__parent.done(id, json).
    private func call(_ name: String, _ argsJson: String, _ id: Int) {
        guard allowed() else { return }
        let args = JSON.object(argsJson) ?? [:]
        io.async { [weak self] in
            guard let self = self else { return }
            var result: [String: Any]
            do {
                switch name {
                case "lookup": result = self.lookup(JSON.optString(args, "id"))
                case "suggest": result = try self.suggest(args)
                case "testAi": result = try self.testAi()
                case "blockLink": result = self.blockLink(JSON.optString(args, "text"))
                default: result = ["error": "Unknown request"]
                }
            } catch {
                result = ["error": (error as? ClassifierException)?.message ?? error.localizedDescription]
            }
            let js = "window.__parent.done(\(id), \(JSON.quote(JSON.string(result))))"
            DispatchQueue.main.async { self.webView?.evaluateJavaScript(js, completionHandler: nil) }
        }
    }

    /// Nothing can be read or changed before the PIN is entered, except during first-run setup.
    private func allowed() -> Bool { unlocked || !prefs.hasPin }

    /// Applies a change and tells the YouTube page to re-check what it shows.
    private func changed(_ block: () -> Void) {
        guard allowed() else { return }
        block()
        prefs.bumpVersion()
    }

    private func settingsJson() -> [String: Any] {
        [
            "rules": prefs.rules,
            "keywords": KeywordFilter.parseList(prefs.keywords),
            "strictness": prefs.strictness.rawValue,
            "provider": [
                "type": prefs.providerType.rawValue,
                "accountId": prefs.accountId,
                "apiKey": prefs.apiKey,
                "baseUrl": prefs.baseUrl,
                "model": prefs.model,
                "sendThumbnails": prefs.sendThumbnails,
            ] as [String: Any],
            "helper": [
                "type": prefs.helperType.rawValue,
                "apiKey": prefs.helperKey,
                "baseUrl": prefs.helperBaseUrl,
                "model": prefs.helperModel,
            ],
            "hideShorts": prefs.hideShorts,
            "hideComments": prefs.hideComments,
            "kidsCanBlock": prefs.kidsCanBlock,
            "blockAds": prefs.blockAds,
        ]
    }

    /// The video or channel that was open when the parent came in, if any.
    private func currentPage() -> [String: Any]? {
        guard let url = currentUrl else { return nil }
        let videoId = VideoLinks.videoId(url)
        let channelRef = videoId == nil ? ChannelBlocklist.normalizeId(URL(string: url)?.path ?? "") : nil
        if videoId == nil && channelRef == nil { return nil }
        return ["videoId": JSON.orNull(videoId), "channelRef": JSON.orNull(channelRef)]
    }

    private func lookup(_ id: String) -> [String: Any] {
        let info = VideoInfo.isValidId(id) ? YouTubeOEmbed.shared.fetch(id) : nil
        return ["title": info?.title ?? "", "channel": info?.channel ?? "", "channelId": info?.channelId ?? ""]
    }

    private func suggest(_ args: [String: Any]) throws -> [String: Any] {
        let h = args["helper"] as? [String: Any] ?? [:]
        let config = ProviderConfig(
            ProviderType(rawValue: JSON.optString(h, "type")) ?? .none,
            apiKey: JSON.optString(h, "apiKey").trimmed,
            baseUrl: JSON.optString(h, "baseUrl").trimmed,
            model: JSON.optString(h, "model").trimmed
        )
        if config.type == .none { return ["error": "Set up the keyword helper first."] }
        let existing = (args["keywords"] as? [Any] ?? []).map { ($0 as? String) ?? "" }
        let added = try KeywordSuggester(config).suggest(JSON.optString(args, "rules"), existing: existing)
        return ["keywords": added]
    }

    /// Runs the saved rules against two example videos so the parent can see it working.
    private func testAi() throws -> [String: Any] {
        let settings = prefs.filterSettings()
        var provider = settings.provider
        provider.sendThumbnails = false
        guard let classifier = Classifiers.create(provider) else { return ["error": "Pick a checker first."] }
        let samples = [
            VideoInfo("aaaaaaaaaaa", "Five Little Pumpkins | Halloween Song for Kids", "Sing Along Street"),
            VideoInfo("bbbbbbbbbbb", "Counting to 20 with Building Blocks", "Number Garden"),
        ]
        var scores: [String: Score] = [:]
        for s in try classifier.classify(samples, rules: settings.rules) { scores[s.id] = s }
        return ["results": samples.map { v -> [String: Any] in
            let s = scores[v.id]
            return [
                "title": v.title,
                "hidden": s.map { settings.strictness.verdictFor($0.p) == .block } ?? false,
                "reason": s?.reason ?? "No answer",
            ]
        }]
    }

    /// A pasted video link hides that video; a channel link, @handle or name hides the channel.
    private func blockLink(_ text: String) -> [String: Any] {
        let t = text.trimmed
        let videoId = ChannelBlocklist.isChannelLink(t) ? nil : VideoLinks.videoId(t)
        if let videoId = videoId {
            let title = YouTubeOEmbed.shared.fetch(videoId)?.title
            DispatchQueue.main.sync { changed { store.setOverride(videoId, .block, title: title, by: "parent") } }
            return ["ok": true, "message": "\(title.map { "“\($0)”" } ?? "That video") is now always hidden."]
        }
        guard let channel = ChannelBlocklist.parse(t) else {
            return ["ok": false, "message": "That doesn’t look like a YouTube video or channel."]
        }
        DispatchQueue.main.sync { changed { prefs.addBlockedChannel(channel) } }
        return ["ok": true, "message": "\(channel) is now hidden."]
    }

    private func startOfToday() -> Int64 {
        Int64(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000)
    }

    // The settings page never navigates; links out go through openLink().
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        decisionHandler(action.request.url?.scheme == ParentViewController.scheme ? .allow : .cancel)
    }
}

/// Serves the shared parent screens (bundled from app/src/main/assets/parent) at safetube://app/parent/...
final class BundleSchemeHandler: NSObject, WKURLSchemeHandler {
    private static let types = [
        "html": "text/html", "js": "text/javascript", "css": "text/css", "woff2": "font/woff2",
        "woff": "font/woff", "svg": "image/svg+xml", "png": "image/png", "txt": "text/plain", "json": "application/json",
    ]

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url, let root = Bundle.main.resourceURL?.appendingPathComponent("parent").standardizedFileURL else {
            task.didFailWithError(URLError(.badURL))
            return
        }
        var path = url.path
        if path.hasPrefix("/parent/") { path.removeFirst("/parent/".count) } else { path = "" }
        let file = root.appendingPathComponent(path).standardizedFileURL
        guard !path.isEmpty, file.path.hasPrefix(root.path + "/"), let data = try? Data(contentsOf: file) else {
            let r = HTTPURLResponse(url: url, statusCode: 404, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "text/plain"])!
            task.didReceive(r)
            task.didReceive(Data())
            task.didFinish()
            return
        }
        let type = BundleSchemeHandler.types[file.pathExtension.lowercased()] ?? "application/octet-stream"
        let headers = ["Content-Type": type.hasPrefix("text/") ? "\(type); charset=utf-8" : type, "Cache-Control": "no-store"]
        let r = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!
        task.didReceive(r)
        task.didReceive(data)
        task.didFinish()
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}
}
