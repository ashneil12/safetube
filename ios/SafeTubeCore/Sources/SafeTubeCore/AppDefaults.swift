import Foundation

/// The starting settings, the same as the Android app's Prefs.kt.
public enum AppDefaults {
    public static let rules =
        "Nothing involving witches, witchcraft, spells or potions, Halloween, ghosts, demons, " +
        "zombies, skeletons, monsters meant to be scary, or anything occult or designed to frighten " +
        "children. Ordinary fairy tales and silly cartoon fun are fine."
    public static let geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"
    public static let geminiModel = "gemini-2.5-flash"
    public static let keywords = "halloween, witch, witchcraft, ghost, zombie, demon, spooky, trick or treat"
    /// Where the video checker's base URL starts for OpenAI-compatible providers.
    public static let checkerBaseUrl = "https://openrouter.ai/api/v1"
}
