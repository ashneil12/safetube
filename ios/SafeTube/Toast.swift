import UIKit

/// A short message at the bottom of the screen, like Android's Toast.
enum Toast {
    private static weak var current: UIView?

    static func show(_ message: String, in view: UIView) {
        current?.removeFromSuperview()
        let label = PaddedLabel()
        label.text = message
        label.numberOfLines = 0
        label.textAlignment = .center
        label.font = .systemFont(ofSize: 15)
        label.textColor = .white
        label.backgroundColor = UIColor(white: 0.1, alpha: 0.92)
        label.layer.cornerRadius = 12
        label.clipsToBounds = true
        label.alpha = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            label.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32),
        ])
        current = label
        UIView.animate(withDuration: 0.2) { label.alpha = 1 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.5) {
            UIView.animate(withDuration: 0.3, animations: { label.alpha = 0 }) { _ in label.removeFromSuperview() }
        }
    }

    private final class PaddedLabel: UILabel {
        private let inset = UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 16)
        override func drawText(in rect: CGRect) { super.drawText(in: rect.inset(by: inset)) }
        override var intrinsicContentSize: CGSize {
            let s = super.intrinsicContentSize
            return CGSize(width: s.width + inset.left + inset.right, height: s.height + inset.top + inset.bottom)
        }
        override func textRect(forBounds bounds: CGRect, limitedToNumberOfLines n: Int) -> CGRect {
            let r = super.textRect(forBounds: bounds.inset(by: inset), limitedToNumberOfLines: n)
            return r.inset(by: UIEdgeInsets(top: -inset.top, left: -inset.left, bottom: -inset.bottom, right: -inset.right))
        }
    }
}

/// Holds a WKScriptMessageHandler weakly; WKUserContentController keeps a strong reference otherwise.
import WebKit

final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(c, didReceive: message)
    }
}
