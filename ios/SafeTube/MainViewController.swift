import SafeTubeCore
import UIKit
import WebKit

/// The child's screen: normal YouTube in a WKWebView, with filter.js running on every page.
/// Only YouTube (and Google sign-in) can load; every other link is swallowed.
/// The iPhone counterpart of MainActivity.kt and FilterBridge.kt.
final class MainViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    static let startUrl = URL(string: "https://m.youtube.com/")!
    /// Reopen the page the child was on if they left less than this long ago.
    private static let resumeWithinMs: Int64 = 2 * 60 * 60 * 1000

    private let prefs = Prefs.shared
    private let store = SqliteVerdictStore.shared
    /// Each page's batch of videos is decided here (two at a time, like the Android app).
    private let bridgeQueue: OperationQueue = {
        let q = OperationQueue()
        q.maxConcurrentOperationCount = 2
        q.qualityOfService = .userInitiated
        return q
    }()
    /// AI batches and title look-ups run side by side here so a page fills in faster.
    private let aiPool: OperationQueue = {
        let q = OperationQueue()
        q.maxConcurrentOperationCount = 4
        q.qualityOfService = .userInitiated
        return q
    }()
    private lazy var engine = DecisionEngine(store, batchSize: 8, aiPool: aiPool)

    private var webView: WKWebView!
    private let cover = UIView()
    private var filterScript = ""
    private var seenVersion = -1
    private var aiUnavailable: Bool {
        get { prefs.aiUnavailable }
        set { prefs.aiUnavailable = newValue }
    }
    private var pageGeneration = 0
    private var aiFailureRevision = 0
    private var lastErrorToast: Date?
    private var shownSetup = false
    /// Set when the Home Screen quick action asked for the parent settings before the view was ready.
    private var wantsParent = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .white
        if let url = Bundle.main.url(forResource: "filter", withExtension: "js"),
           let text = try? String(contentsOf: url, encoding: .utf8) {
            filterScript = text
        }

        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = .all
        // Google refuses or stalls sign-in in embedded browsers. Send the user agent Safari on this
        // device would: WKWebView's own already matches it except for the trailing Safari part.
        config.applicationNameForUserAgent = MainViewController.safariAppName()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        if #available(iOS 15.4, *) { config.preferences.isElementFullscreenEnabled = true }
        config.userContentController.add(WeakScriptHandler(self), name: "safetube")
        installScripts(config.userContentController)

        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        // The system link preview would steal the long-press that opens SafeTube's hold menu.
        webView.allowsLinkPreview = false
        webView.allowsBackForwardNavigationGestures = true
        webView.backgroundColor = .white
        webView.isOpaque = false
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)

        cover.backgroundColor = .white
        cover.translatesAutoresizingMaskIntoConstraints = false
        let spinner = UIActivityIndicatorView(style: .large)
        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.startAnimating()
        cover.addSubview(spinner)
        view.addSubview(cover)

        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            cover.topAnchor.constraint(equalTo: view.topAnchor),
            cover.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            cover.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            cover.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            spinner.centerXAnchor.constraint(equalTo: cover.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: cover.centerYAnchor),
        ])

        seenVersion = prefs.version
        // Load once the ad rules are in place, so the first page doesn't reach the ad servers.
        let first = launchUrl()
        applyAdBlocking { [weak self] in
            self?.applyRestrictedMode { _ in self?.webView.load(URLRequest(url: first)) }
        }

        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(startTicking), name: UIApplication.didBecomeActiveNotification, object: nil)
        nc.addObserver(self, selector: #selector(stopTicking), name: UIApplication.willResignActiveNotification, object: nil)
        startTicking()
        nc.addObserver(self, selector: #selector(rememberPage), name: UIApplication.willResignActiveNotification, object: nil)
        nc.addObserver(self, selector: #selector(rememberPage), name: UIApplication.didEnterBackgroundNotification, object: nil)
    }

    /// Where the child was, if they left recently; otherwise YouTube's home page.
    private func launchUrl() -> URL {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if now - prefs.lastUrlAt < MainViewController.resumeWithinMs, let s = prefs.lastUrl, let url = URL(string: s),
           url.scheme == "https", MainViewController.isYouTubeHost(url.host) {
            return url
        }
        return MainViewController.startUrl
    }

    // ------------------------------------------------------------------ ad blocking

    private static var adRules: WKContentRuleList?
    private var adRulesOn = false

    /// Blocks the ad and tracker addresses in AdBlocklist (the same list as Android's
    /// AdBlocklist.kt) while "Block ads" is on; filter.js skips the video ads themselves.
    private func applyAdBlocking(then done: (() -> Void)? = nil) {
        let controller = webView.configuration.userContentController
        let want = prefs.blockAds
        if !want {
            if let rules = MainViewController.adRules, adRulesOn { controller.remove(rules) }
            adRulesOn = false
            done?()
            return
        }
        if adRulesOn { done?(); return }
        if let rules = MainViewController.adRules {
            controller.add(rules)
            adRulesOn = true
            done?()
            return
        }
        let json = AdBlocklist.contentRuleListJSON()
        let id = "safetube-ads-" + String(Hashing.sha256(json).prefix(12))
        WKContentRuleListStore.default().compileContentRuleList(forIdentifier: id, encodedContentRuleList: json) {
            [weak self] rules, _ in
            DispatchQueue.main.async {
                guard let self = self else { return }
                if let rules = rules {
                    MainViewController.adRules = rules
                    if self.prefs.blockAds && !self.adRulesOn {
                        self.webView.configuration.userContentController.add(rules)
                        self.adRulesOn = true
                    }
                }
                done?()
            }
        }
    }

    @objc private func rememberPage() {
        if let url = webView?.url, MainViewController.isYouTubeHost(url.host) { prefs.rememberPage(url.absoluteString) }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !prefs.hasPin && !shownSetup {
            shownSetup = true
            presentParent(ParentViewController(currentUrl: nil, blockRequest: nil))
        } else if wantsParent {
            openParent()
        }
    }

    deinit {
        bridgeQueue.cancelAllOperations()
        aiPool.cancelAllOperations()
    }

    // ------------------------------------------------------------------ page scripts

    /// The shim that stands in for Android's SafeTubeAndroid bridge, then filter.js itself, both
    /// only on youtube.com. config() has to answer synchronously, so the settings are written in.
    private func installScripts(_ controller: WKUserContentController) {
        controller.removeAllUserScripts()
        let shim = """
            (function () {
              var h = String(location.hostname || '').toLowerCase();
              if (!(h === 'youtube.com' || h.slice(-12) === '.youtube.com')) return;
              var cfg = \(JSON.quote(prefs.pageConfigJson(aiUnavailable: aiUnavailable)));
              function post(m) {
                try { window.webkit.messageHandlers.safetube.postMessage(m); } catch (e) {}
              }
              window.SafeTubeAndroid = {
                config: function () { return cfg; },
                submit: function (json) { post({ kind: 'submit', json: String(json) }); },
                block: function (json) { post({ kind: 'block', json: String(json) }); },
                openParent: function () { post({ kind: 'openParent' }); },
                watched: function (json) { post({ kind: 'watched', json: String(json) }); }
              };
              // iOS: no callout or text selection on long-press, so SafeTube's hold menu and the
              // logo hold for parent settings get the touch instead.
              var css = '*{-webkit-touch-callout:none!important}' +
                'a,img,ytm-app *{-webkit-user-select:none!important;user-select:none!important}' +
                'input,textarea{-webkit-user-select:text!important;user-select:text!important}';
              function addStyle() {
                var root = document.head || document.documentElement;
                if (!root) return false;
                var s = document.createElement('style');
                s.id = 'st-ios-style';
                s.textContent = css;
                root.appendChild(s);
                return true;
              }
              if (!addStyle()) document.addEventListener('DOMContentLoaded', addStyle);
            })();
            """
        controller.addUserScript(WKUserScript(source: shim, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let filter = "if (window.SafeTubeAndroid) {\n\(filterScript)\n}"
        controller.addUserScript(WKUserScript(source: filter, injectionTime: .atDocumentStart, forMainFrameOnly: true))
    }

    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
        // Only YouTube pages may ask; the web view never loads anything else, but be sure.
        guard message.frameInfo.isMainFrame,
              MainViewController.isYouTubeHost(message.frameInfo.securityOrigin.host),
              let body = message.body as? [String: Any] else { return }
        switch JSON.optString(body, "kind") {
        case "submit": submit(JSON.optString(body, "json"))
        case "block": block(JSON.optString(body, "json"))
        case "openParent": openParent()
        case "watched": watched(JSON.optString(body, "json"))
        default: break
        }
    }

    /// The child has been on a video's page for a few seconds: add it to the parent's history.
    private func watched(_ json: String) {
        guard prefs.keepHistory, let o = JSON.object(json) else { return }
        let id = JSON.optString(o, "id")
        guard VideoInfo.isValidId(id) else { return }
        let entry = SqliteVerdictStore.Watched(
            id: id,
            title: String(JSON.optString(o, "title").prefix(300)),
            channel: String(JSON.optString(o, "channel").prefix(120)),
            channelId: ChannelBlocklist.normalizeId(JSON.optString(o, "channelId")) ?? "",
            ts: Int64(Date().timeIntervalSince1970 * 1000)
        )
        let store = self.store
        DispatchQueue.global(qos: .utility).async { store.recordWatched(entry) }
    }

    private func submit(_ json: String) {
        let videos = MainViewController.parseVideos(json)
        if videos.isEmpty { return }
        let settings = prefs.filterSettings()
        let version = prefs.version
        let failureRevision = aiFailureRevision
        let forceFresh = aiUnavailable
        let generation = pageGeneration
        let engine = self.engine
        bridgeQueue.addOperation { [weak self] in
            let deliver: ([Decision]) -> Void = { decisions in
                DispatchQueue.main.async {
                    guard let self = self, self.prefs.version == version, self.pageGeneration == generation else { return }
                    let wasUnavailable = self.aiUnavailable
                    let errors = decisions.filter { $0.source == .error }
                    if settings.blockOnAiFailure && !errors.isEmpty {
                        self.aiFailureRevision += 1
                        self.aiUnavailable = true
                        self.showAiError(errors[0].reason)
                    } else if forceFresh && self.aiFailureRevision == failureRevision && decisions.contains(where: { $0.freshAi }) {
                        self.aiUnavailable = false
                    }
                    if wasUnavailable != self.aiUnavailable { self.installScripts(self.webView.configuration.userContentController) }
                    self.prefs.recordChecked(decisions.filter { $0.verdict != .unknown }.count)
                    var result: [String: Any] = [:]
                    for d in decisions { result[d.id] = d.verdict.wire }
                    let js = "window.__safetube && (window.__safetube.setAiUnavailable(\(self.aiUnavailable)), window.__safetube.apply(\(JSON.string(result))))"
                    self.webView?.evaluateJavaScript(js, completionHandler: nil)
                }
            }
            if forceFresh {
                deliver(engine.decide(videos, settings, forceFresh: true))
            } else {
                engine.decide(videos, settings, emit: deliver)
            }
        }
    }

    private func block(_ json: String) {
        guard let o = JSON.object(json) else { return }
        let video = VideoInfo(
            JSON.optString(o, "id"),
            JSON.optString(o, "title"),
            JSON.optString(o, "channel"),
            channelId: ChannelBlocklist.normalizeId(JSON.optString(o, "channelId")) ?? ""
        )
        guard VideoInfo.isValidId(video.id) else { return }
        onBlockRequest(channel: JSON.optString(o, "kind") == "channel", video: video)
    }

    static func parseVideos(_ json: String) -> [VideoInfo] {
        guard let arr = JSON.array(json) else { return [] }
        return arr.prefix(200).compactMap { item in
            guard let o = item as? [String: Any] else { return nil }
            let v = VideoInfo(
                JSON.optString(o, "id"),
                JSON.optString(o, "title"),
                JSON.optString(o, "channel"),
                context: JSON.optString(o, "context"),
                channelId: ChannelBlocklist.normalizeId(JSON.optString(o, "channelId")) ?? ""
            )
            return VideoInfo.isValidId(v.id) ? v : nil
        }
    }

    /// Hides one video on the open page straight away.
    private func hideNow(_ id: String) {
        webView.evaluateJavaScript("window.__safetube && window.__safetube.apply(\(JSON.string([id: "block"])))", completionHandler: nil)
    }

    /// Settings changed: the page forgets its decisions and re-checks what's on screen,
    /// and the next page load gets the new options.
    private func applySettingsIfChanged(force: Bool = false) {
        guard force || prefs.version != seenVersion else { return }
        seenVersion = prefs.version
        pageGeneration += 1
        installScripts(webView.configuration.userContentController)
        applyAdBlocking()
        let config = prefs.pageConfigJson(aiUnavailable: aiUnavailable)
        applyRestrictedMode { [weak self] changed in
            guard let self = self else { return }
            if changed {
                self.webView.reload()
            } else {
                self.webView.evaluateJavaScript("window.__safetube && (window.__safetube.reset(\(config)), window.__safetube.setAiUnavailable(\(self.aiUnavailable)))", completionHandler: nil)
            }
        }
    }

    // ------------------------------------------------------------------ restricted mode

    /// YouTube's Restricted Mode lives in its PREF cookie (the f2 flag). Setting it in this web
    /// view's own cookie store changes only SafeTube on this device; the Google account and other
    /// devices are untouched. Calls back with true if the cookie changed (the page needs a reload).
    private func applyRestrictedMode(_ done: @escaping (Bool) -> Void) {
        let cookies = webView.configuration.websiteDataStore.httpCookieStore
        let wantOn = prefs.restrictedMode
        cookies.getAllCookies { all in
            let prefCookies = all.filter { $0.name == "PREF" && MainViewController.isYouTubeHost($0.domain.hasPrefix(".") ? String($0.domain.dropFirst()) : $0.domain) }
            let existing = prefCookies.first { $0.domain == ".youtube.com" } ?? prefCookies.first
            let current = existing?.value ?? ""
            var parts = current.split(separator: "&").map(String.init).filter { !$0.isBlank && !$0.hasPrefix("f2=") }
            if wantOn { parts.append("f2=8000000") }
            let wanted = parts.joined(separator: "&")
            if wanted == current {
                DispatchQueue.main.async { done(false) }
                return
            }
            let props: [HTTPCookiePropertyKey: Any] = [
                .name: "PREF", .value: wanted, .domain: ".youtube.com", .path: "/", .secure: "TRUE",
                .expires: Date().addingTimeInterval(2 * 365 * 24 * 3600),
            ]
            guard let cookie = HTTPCookie(properties: props) else {
                DispatchQueue.main.async { done(false) }
                return
            }
            // A host-only PREF on www/m.youtube.com would shadow the one we set; drop those.
            let stale = prefCookies.filter { $0.domain != ".youtube.com" }
            let group = DispatchGroup()
            for c in stale { group.enter(); cookies.delete(c) { group.leave() } }
            group.enter()
            cookies.setCookie(cookie) { group.leave() }
            group.notify(queue: .main) { done(true) }
        }
    }

    // ------------------------------------------------------------------ screen time

    private var ticker: Timer?
    private var lastTick = Date()
    private var timeUpView: UIView?
    private var timeUpTitle: UILabel?

    /// Counts viewing time while YouTube is on screen and closes it when time is up (every 15 s,
    /// like MainActivity's tick).
    @objc private func startTicking() {
        lastTick = Date()
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in self?.tick() }
        tick()
    }

    @objc private func stopTicking() {
        countTime()
        ticker?.invalidate()
        ticker = nil
    }

    private func tick() {
        countTime()
        showTimeUp(prefs.timeUp())
    }

    private func countTime() {
        let now = Date()
        let sec = min(max(Int(now.timeIntervalSince(lastTick)), 0), 60)
        lastTick = now
        // Time in the parent screens or behind the time-up screen doesn't count.
        if sec > 0 && timeUpView == nil && presentedViewController == nil && UIApplication.shared.applicationState == .active {
            prefs.addWatched(sec)
        }
    }

    private func showTimeUp(_ reason: String?) {
        guard let reason = reason else {
            timeUpView?.removeFromSuperview()
            timeUpView = nil
            return
        }
        if let label = timeUpTitle, timeUpView != nil {
            label.text = MainViewController.timeUpText(reason)
            return
        }
        webView.evaluateJavaScript(
            "document.querySelectorAll('video').forEach(function(v){try{v.pause();if(v.webkitExitFullscreen)v.webkitExitFullscreen();if(document.webkitExitFullscreen)document.webkitExitFullscreen();}catch(e){}})",
            completionHandler: nil)
        let navy = UIColor(red: 0x14 / 255.0, green: 0x18 / 255.0, blue: 0x2B / 255.0, alpha: 1)
        let cover = UIView()
        cover.backgroundColor = navy
        cover.translatesAutoresizingMaskIntoConstraints = false

        let moon = UIImageView(image: UIImage(systemName: "moon.stars.fill"))
        moon.tintColor = UIColor(red: 1, green: 0xC8 / 255.0, blue: 0x45 / 255.0, alpha: 1)
        moon.contentMode = .scaleAspectFit
        let title = UILabel()
        title.text = MainViewController.timeUpText(reason)
        title.font = .systemFont(ofSize: 34, weight: .bold)
        title.textColor = .white
        title.textAlignment = .center
        title.numberOfLines = 0
        let sub = UILabel()
        sub.text = "See you next time! Ask a grown-up if you need a few more minutes."
        sub.font = .systemFont(ofSize: 18)
        sub.textColor = UIColor(red: 0xC9 / 255.0, green: 0xCE / 255.0, blue: 0xDD / 255.0, alpha: 1)
        sub.textAlignment = .center
        sub.numberOfLines = 0
        let button = UIButton(type: .system)
        button.setTitle("Grown-ups", for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        button.setTitleColor(navy, for: .normal)
        button.backgroundColor = UIColor(red: 1, green: 0xC8 / 255.0, blue: 0x45 / 255.0, alpha: 1)
        button.layer.cornerRadius = 28
        button.contentEdgeInsets = UIEdgeInsets(top: 0, left: 28, bottom: 0, right: 28)
        button.addTarget(self, action: #selector(grownUps), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [moon, title, sub, button])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 16
        stack.setCustomSpacing(28, after: moon)
        stack.setCustomSpacing(40, after: sub)
        stack.translatesAutoresizingMaskIntoConstraints = false
        cover.addSubview(stack)
        view.addSubview(cover)
        NSLayoutConstraint.activate([
            cover.topAnchor.constraint(equalTo: view.topAnchor),
            cover.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            cover.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            cover.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            moon.widthAnchor.constraint(equalToConstant: 96),
            moon.heightAnchor.constraint(equalToConstant: 96),
            button.heightAnchor.constraint(equalToConstant: 56),
            stack.centerYAnchor.constraint(equalTo: cover.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: cover.leadingAnchor, constant: 32),
            stack.trailingAnchor.constraint(equalTo: cover.trailingAnchor, constant: -32),
        ])
        timeUpView = cover
        timeUpTitle = title
    }

    private static func timeUpText(_ reason: String) -> String {
        reason == "bedtime" ? "It's bedtime" : "That's all the YouTube for today"
    }

    @objc private func grownUps() { openParent() }

    // ------------------------------------------------------------------ navigation

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        // Frames inside a YouTube page (players, sign-in helpers) load as they like.
        if let frame = action.targetFrame, !frame.isMainFrame {
            decisionHandler(.allow)
            return
        }
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        decisionHandler(allowNavigation(url) ? .allow : .cancel)
    }

    /// Links that want a new window open in this one, if they're allowed at all.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if action.targetFrame == nil, let url = action.request.url, allowNavigation(url) {
            webView.load(action.request)
        }
        return nil
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { pageGeneration += 1 }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        cover.isHidden = true
        webView.evaluateJavaScript("window.__safetube && window.__safetube.setAiUnavailable(\(aiUnavailable))", completionHandler: nil)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { cover.isHidden = true }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        cover.isHidden = true
    }

    /// iOS sometimes kills the page's web process (low memory, long in the background). Load the
    /// same page again behind the cover instead of leaving a blank screen.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        cover.isHidden = false
        let current = webView.url.flatMap { MainViewController.isYouTubeHost($0.host) ? $0 : nil }
        webView.load(URLRequest(url: current ?? launchUrl()))
    }

    /// YouTube, plus the Google pages needed to sign in or accept cookies. Nothing else.
    private func allowNavigation(_ url: URL) -> Bool {
        let scheme = url.scheme?.lowercased() ?? ""
        if scheme == "about" || scheme == "blob" { return true } // blank frames and video data inside YouTube
        guard let host = url.host?.lowercased(), scheme == "https" || scheme == "http" else {
            toast("Links outside YouTube are turned off")
            return false
        }
        if host == "youtu.be" || host == "www.youtu.be" {
            let id = String(url.path.drop { $0 == "/" }.prefix(11))
            // Short links open the same video on YouTube itself, where the filter checks it.
            if let watch = URL(string: "https://m.youtube.com/watch?v=\(id)") { webView.load(URLRequest(url: watch)) }
            return false
        }
        if MainViewController.isYouTubeHost(host) { return true }
        if MainViewController.isSignInPage(host, url.path) { return true }
        toast("Links outside YouTube are turned off")
        return false
    }

    static func isYouTubeHost(_ host: String?) -> Bool {
        guard let h = host?.lowercased() else { return false }
        return h == "youtube.com" || h.hasSuffix(".youtube.com")
    }

    private static let signInHosts: Set<String> = [
        "accounts.google.com", "accounts.youtube.com", "consent.youtube.com", "consent.google.com",
        "myaccount.google.com", "gds.google.com",
    ]
    private static let signInPaths = ["/accounts", "/signin", "/ServiceLogin", "/sorry"]
    private static let google = try! NSRegularExpression(pattern: "^google\\.(com?\\.)?[a-z]{2,3}$")

    /// Google's sign-in hops across several hosts, including country domains like google.co.uk.
    static func isSignInPage(_ host: String, _ path: String) -> Bool {
        if signInHosts.contains(host) { return true }
        if host.hasPrefix("accounts."), isGoogle(String(host.dropFirst("accounts.".count))) { return true }
        let bare = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
        return isGoogle(bare) && signInPaths.contains { path.hasPrefix($0) }
    }

    private static func isGoogle(_ host: String) -> Bool {
        google.firstMatch(in: host, range: NSRange(location: 0, length: (host as NSString).length)) != nil
    }

    /// "Version/17.5 Mobile/15E148 Safari/604.1", as Mobile Safari ends its user agent.
    static func safariAppName() -> String {
        let parts = UIDevice.current.systemVersion.split(separator: ".")
        let version = parts.prefix(2).joined(separator: ".")
        return "Version/\(version.contains(".") ? version : version + ".0") Mobile/15E148 Safari/604.1"
    }

    // ------------------------------------------------------------------ parent settings

    /// Holding the YouTube logo for three seconds lands here (see filter.js), as does the
    /// "Parent settings" quick action on the Home Screen icon.
    func openParent() {
        guard isViewLoaded, view.window != nil else { wantsParent = true; return }
        wantsParent = false
        if presentedViewController != nil { return }
        // Pass the page that's open so the parent can hide that video or channel in one tap.
        presentParent(ParentViewController(currentUrl: webView.url?.absoluteString, blockRequest: nil))
    }

    private func presentParent(_ vc: ParentViewController) {
        vc.onClose = { [weak self] in
            self?.applySettingsIfChanged()
            // More time, or a new limit or bedtime, takes effect straight away.
            self?.lastTick = Date()
            self?.tick()
        }
        present(vc, animated: true)
    }

    // ------------------------------------------------------------------ hold-to-block

    private func onBlockRequest(channel: Bool, video: VideoInfo) {
        if prefs.kidsCanBlock {
            applyBlock(channel: channel, video: video, by: "child")
            return
        }
        // Hiding is parent-only in this mode: show the PIN pad, then hide it if the PIN was right.
        guard presentedViewController == nil else { return }
        let request: [String: Any] = ["kind": channel ? "channel" : "video", "title": video.title, "channel": video.channel]
        let vc = ParentViewController(currentUrl: nil, blockRequest: request)
        vc.onConfirmBlock = { [weak self] in self?.applyBlock(channel: channel, video: video, by: "parent") }
        presentParent(vc)
    }

    private func applyBlock(channel: Bool, video v: VideoInfo, by: String) {
        if !channel {
            store.setOverride(v.id, .block, title: v.title.isBlank ? nil : v.title, by: by)
            hideNow(v.id)
            syncVersion()
            toast(by == "child" ? "Hidden. Only a grown-up can bring it back." : "Hidden.")
            return
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            // The page may not show which channel a video is from; YouTube's oEmbed always does.
            let looked = v.channelId.isBlank || v.channel.isBlank ? YouTubeOEmbed.shared.fetch(v.id) : nil
            let handle = v.channelId.isBlank ? (looked?.channelId ?? "") : v.channelId
            let name = v.channel.isBlank ? (looked?.channel ?? "") : v.channel
            DispatchQueue.main.async {
                guard let self = self else { return }
                if handle.isBlank && name.isBlank {
                    self.toast("Couldn't tell which channel this is. Try again on the video's page.")
                    return
                }
                [handle, name].filter { !$0.isBlank }.forEach { self.prefs.addBlockedChannel($0) }
                self.prefs.bumpVersion()
                // Re-check everything on screen against the new channel list.
                self.applySettingsIfChanged(force: true)
                self.toast("Everything from \(name.isBlank ? handle : name) is hidden." +
                    (by == "child" ? " Only a grown-up can bring it back." : ""))
            }
        }
    }

    /// Record a settings change made from this screen so it isn't redone on return.
    private func syncVersion() {
        prefs.bumpVersion()
        seenVersion = prefs.version
    }

    private func showAiError(_ reason: String) {
        if let last = lastErrorToast, Date().timeIntervalSince(last) < 60 { return }
        lastErrorToast = Date()
        toast("Some videos are hidden because the AI check failed: \(String(reason.prefix(120)))")
    }

    private func toast(_ message: String) { Toast.show(message, in: view) }
}
