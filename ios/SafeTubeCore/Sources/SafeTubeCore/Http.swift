import Foundation

/// Tiny synchronous HTTP helper. The engine is synchronous (like the Kotlin one); call it off the main thread.
public enum Http {
    public struct Response {
        public let code: Int
        public let body: String
    }

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.httpCookieStorage = nil
        c.urlCache = nil
        return URLSession(configuration: c)
    }()

    public static func request(
        _ url: String,
        method: String = "GET",
        headers: [String: String] = [:],
        body: String? = nil,
        timeoutMs: Int = 30_000
    ) throws -> Response {
        guard let u = URL(string: url), let scheme = u.scheme?.lowercased(), scheme == "https" || scheme == "http" else {
            throw ClassifierException("Network error talking to \(url): bad URL")
        }
        var req = URLRequest(url: u, timeoutInterval: Double(timeoutMs) / 1000)
        req.httpMethod = method
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        if let body = body {
            req.httpBody = Data(body.utf8)
            req.setValue("application/json", forHTTPHeaderField: "content-type")
        }
        let done = DispatchSemaphore(value: 0)
        var result: Result<Response, Error> = .failure(ClassifierException("Network error talking to \(url): no reply"))
        let task = session.dataTask(with: req) { data, response, error in
            if let error = error {
                result = .failure(error)
            } else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
                result = .success(Response(code: code, body: text))
            }
            done.signal()
        }
        task.resume()
        done.wait()
        switch result {
        case .success(let r): return r
        case .failure(let e as ClassifierException): throw e
        case .failure(let e): throw ClassifierException("Network error talking to \(url): \(e.localizedDescription)")
        }
    }
}
