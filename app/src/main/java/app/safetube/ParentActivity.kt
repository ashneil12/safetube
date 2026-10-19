package app.safetube

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.safetube.core.ChannelBlocklist
import app.safetube.core.Classifiers
import app.safetube.core.KeywordFilter
import app.safetube.core.KeywordSuggester
import app.safetube.core.ProviderConfig
import app.safetube.core.ProviderType
import app.safetube.core.Strictness
import app.safetube.core.Verdict
import app.safetube.core.VideoInfo
import app.safetube.core.VideoLinks
import app.safetube.core.YouTubeOEmbed
import java.util.concurrent.Executors

/**
 * PIN-protected settings: the rules in plain English, the keyword list, how strict to be,
 * which AI to use, and the list of hidden videos the parent can let through.
 */
class ParentActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var store: SqliteVerdictStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var rules: EditText
    private lateinit var keywords: EditText
    private lateinit var strictness: RadioGroup
    private lateinit var provider: RadioGroup
    private lateinit var accountId: EditText
    private lateinit var apiKey: EditText
    private lateinit var baseUrl: EditText
    private lateinit var model: EditText
    private lateinit var thumbs: CheckBox
    private lateinit var hideShorts: CheckBox
    private lateinit var hideComments: CheckBox
    private lateinit var aiFields: LinearLayout
    private lateinit var baseUrlRow: View
    private lateinit var testResult: TextView
    private lateinit var hiddenList: LinearLayout
    private lateinit var choicesList: LinearLayout
    private lateinit var channelsList: LinearLayout
    private lateinit var blockInput: EditText
    private lateinit var suggestResult: TextView
    private lateinit var helper: RadioGroup
    private lateinit var helperFields: LinearLayout
    private lateinit var helperKey: EditText
    private lateinit var helperBaseUrl: EditText
    private lateinit var helperModel: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep settings (and API keys) out of screenshots and the recent-apps preview.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        prefs = Prefs(this)
        store = SqliteVerdictStore(this)

        val setup = intent.getBooleanExtra(EXTRA_SETUP, false) || !prefs.hasPin
        if (setup) askNewPin(firstTime = true) else askPin()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ PIN

    private fun askPin() {
        val input = pinField()
        AlertDialog.Builder(this)
            .setTitle("Parent PIN")
            .setView(padded(input))
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                if (prefs.checkPin(input.text.toString())) showSettings()
                else { toast("Wrong PIN"); finish() }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun askNewPin(firstTime: Boolean) {
        val first = pinField().apply { hint = "New PIN (4+ digits)" }
        val second = pinField().apply { hint = "Repeat PIN" }
        val box = column().apply {
            if (firstTime) {
                addView(TextView(this@ParentActivity).apply {
                    text = "Choose a PIN. Your child will need it to change what's filtered."
                    setPadding(0, 0, 0, dp(8))
                })
            }
            addView(first)
            addView(second)
        }
        AlertDialog.Builder(this)
            .setTitle(if (firstTime) "Welcome to SafeTube" else "Change PIN")
            .setView(padded(box))
            .setCancelable(!firstTime)
            .setPositiveButton("Save") { _, _ ->
                val a = first.text.toString()
                when {
                    a.length < 4 -> { toast("PIN must be at least 4 digits"); askNewPin(firstTime) }
                    a != second.text.toString() -> { toast("PINs didn't match"); askNewPin(firstTime) }
                    else -> {
                        prefs.setPin(a)
                        toast("PIN saved")
                        if (firstTime) showSettings()
                    }
                }
            }
            .apply { if (!firstTime) setNegativeButton("Cancel", null) }
            .show()
    }

    // ------------------------------------------------------------------ settings screen

    private fun showSettings() {
        val page = column().apply { setPadding(dp(20), dp(20), dp(20), dp(40)) }

        addCurrentPageSection(page)

        page.addView(heading("What shouldn't your child see?"))
        page.addView(note("Describe it in your own words. The AI hides videos that match."))
        rules = EditText(this).apply {
            setText(prefs.rules)
            minLines = 4
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        page.addView(rules)

        page.addView(heading("Always hide videos that mention"))
        page.addView(note("Comma-separated. Checked instantly on the device, no AI needed."))
        keywords = EditText(this).apply {
            setText(prefs.keywords)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        page.addView(keywords)
        suggestResult = note("")
        page.addView(button("Suggest more keywords from my rules (AI)") { suggestKeywords() })
        page.addView(suggestResult)

        page.addView(heading("Block a video or channel"))
        page.addView(note("Paste a video link to hide just that video, or a channel link, @handle or channel name to hide everything from that channel."))
        blockInput = EditText(this).apply {
            hint = "youtube.com/watch?v=... or @channel"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        page.addView(blockInput)
        page.addView(button("Block it") { blockFromInput() })
        page.addView(note("Blocked channels:"))
        channelsList = column()
        page.addView(channelsList)

        page.addView(heading("How careful should the AI be?"))
        strictness = RadioGroup(this)
        listOf(
            Strictness.STRICT to "Strict: hide anything that might match",
            Strictness.BALANCED to "Balanced: hide likely matches",
            Strictness.PERMISSIVE to "Relaxed: hide only clear matches",
        ).forEach { (s, label) ->
            strictness.addView(radio(label, s.ordinal + 1, prefs.strictness == s))
        }
        page.addView(strictness)

        page.addView(heading("Video checker AI"))
        page.addView(note("Decides each video against your rules. Only titles and channel names are sent (plus thumbnails for chat models), using your own key."))
        provider = RadioGroup(this)
        listOf(
            ProviderType.NONE to "Off (keywords and blocks only)",
            ProviderType.CLEF to "Clef by Cloudflare: fast, free daily allowance (recommended)",
            ProviderType.JEV to "Jev via OpenRouter: fast, pay per use",
            ProviderType.ANTHROPIC to "Claude (Anthropic API key)",
            ProviderType.OPENAI_COMPATIBLE to "OpenAI-compatible (OpenAI, Gemini, Groq, Ollama...)",
        ).forEach { (t, label) -> provider.addView(radio(label, t.ordinal + 1, prefs.providerType == t)) }
        page.addView(provider)

        aiFields = column()
        accountId = EditText(this).apply {
            hint = "Cloudflare account ID"
            setText(prefs.accountId)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        apiKey = EditText(this).apply {
            hint = "API key"
            setText(prefs.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        baseUrl = EditText(this).apply {
            hint = "Base URL, e.g. https://openrouter.ai/api/v1"
            setText(prefs.baseUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        model = EditText(this).apply {
            setText(prefs.model)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        thumbs = CheckBox(this).apply {
            text = "Also check thumbnails (catches more, costs a little more)"
            isChecked = prefs.sendThumbnails
        }
        baseUrlRow = baseUrl
        aiFields.addView(accountId)
        aiFields.addView(apiKey)
        aiFields.addView(baseUrl)
        aiFields.addView(model)
        aiFields.addView(thumbs)
        testResult = note("")
        aiFields.addView(button("Test the AI") { testAi() })
        aiFields.addView(testResult)
        page.addView(aiFields)
        provider.setOnCheckedChangeListener { _, _ ->
            // A model name for one provider means nothing to another.
            model.setText("")
            refreshProviderFields()
        }
        refreshProviderFields()

        page.addView(heading("Keyword helper AI (optional)"))
        page.addView(note("A chat model that turns your rules into keywords. It only sees your rules, never videos. Gemini's free tier works well."))
        helper = RadioGroup(this)
        listOf(
            ProviderType.NONE to "None",
            ProviderType.OPENAI_COMPATIBLE to "OpenAI-compatible (Gemini, OpenAI, OpenRouter...)",
            ProviderType.ANTHROPIC to "Claude (Anthropic API key)",
        ).forEach { (t, label) -> helper.addView(radio(label, t.ordinal + 1, prefs.helperType == t)) }
        page.addView(helper)
        helperFields = column()
        helperKey = EditText(this).apply {
            hint = "API key"
            setText(prefs.helperKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        helperBaseUrl = EditText(this).apply {
            hint = "Base URL"
            setText(prefs.helperBaseUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        helperModel = EditText(this).apply {
            hint = "Model"
            setText(prefs.helperModel)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        helperFields.addView(button("Use Gemini (free tier)") {
            helper.check(ProviderType.OPENAI_COMPATIBLE.ordinal + 1)
            helperBaseUrl.setText(Prefs.GEMINI_BASE_URL)
            helperModel.setText(Prefs.GEMINI_MODEL)
        })
        helperFields.addView(helperKey)
        helperFields.addView(helperBaseUrl)
        helperFields.addView(helperModel)
        page.addView(helperFields)
        helper.setOnCheckedChangeListener { _, _ -> refreshHelperFields() }
        refreshHelperFields()

        page.addView(heading("YouTube"))
        hideShorts = CheckBox(this).apply { text = "Hide Shorts"; isChecked = prefs.hideShorts }
        hideComments = CheckBox(this).apply { text = "Hide comments"; isChecked = prefs.hideComments }
        page.addView(hideShorts)
        page.addView(hideComments)

        page.addView(button("Save") { save(); finish() })

        page.addView(heading("Recently hidden"))
        page.addView(note("Tap Allow to let a video through. It stays allowed even if the rules change."))
        hiddenList = column()
        page.addView(hiddenList)

        page.addView(heading("Your choices"))
        choicesList = column()
        page.addView(choicesList)
        refreshLists()

        page.addView(heading("More"))
        page.addView(button("Change PIN") { askNewPin(firstTime = false) })
        page.addView(button("Forget all AI decisions") {
            io.execute { store.clearDecisions() }
            prefs.bumpVersion()
            toast("Every video will be checked again")
            refreshListsLater()
        })

        setContentView(ScrollView(this).apply { addView(page) })
    }

    private fun refreshProviderFields() {
        val type = selectedProvider()
        aiFields.visibility = if (type == ProviderType.NONE) View.GONE else View.VISIBLE
        baseUrlRow.visibility = if (type == ProviderType.OPENAI_COMPATIBLE) View.VISIBLE else View.GONE
        accountId.visibility = if (type == ProviderType.CLEF) View.VISIBLE else View.GONE
        thumbs.visibility = if (type == ProviderType.ANTHROPIC || type == ProviderType.OPENAI_COMPATIBLE) View.VISIBLE else View.GONE
        apiKey.hint = when (type) {
            ProviderType.CLEF -> "Cloudflare API token (Workers AI)"
            ProviderType.JEV -> "OpenRouter API key"
            else -> "API key"
        }
        model.hint = when (type) {
            ProviderType.CLEF -> "Model (default ${ProviderConfig.DEFAULT_CLEF_MODEL}; clef-flash is even faster)"
            ProviderType.JEV -> "Model (default ${ProviderConfig.DEFAULT_JEV_MODEL})"
            ProviderType.ANTHROPIC -> "Model (default ${ProviderConfig.DEFAULT_ANTHROPIC_MODEL})"
            else -> "Model, e.g. gpt-4o-mini or llava"
        }
    }

    private fun refreshHelperFields() {
        val type = selectedHelper()
        helperFields.visibility = if (type == ProviderType.NONE) View.GONE else View.VISIBLE
        helperBaseUrl.visibility = if (type == ProviderType.OPENAI_COMPATIBLE) View.VISIBLE else View.GONE
        helperModel.hint = if (type == ProviderType.ANTHROPIC) "Model (default ${ProviderConfig.DEFAULT_ANTHROPIC_MODEL})" else "Model"
    }

    private fun selectedHelper(): ProviderType =
        ProviderType.entries.firstOrNull { it.ordinal + 1 == helper.checkedRadioButtonId } ?: ProviderType.NONE

    private fun selectedProvider(): ProviderType =
        ProviderType.entries.firstOrNull { it.ordinal + 1 == provider.checkedRadioButtonId } ?: ProviderType.NONE

    private fun selectedStrictness(): Strictness =
        Strictness.entries.firstOrNull { it.ordinal + 1 == strictness.checkedRadioButtonId } ?: Strictness.STRICT

    private fun save() {
        prefs.rules = rules.text.toString().trim()
        prefs.keywords = keywords.text.toString().trim()
        prefs.strictness = selectedStrictness()
        prefs.providerType = selectedProvider()
        prefs.accountId = accountId.text.toString().trim()
        prefs.apiKey = apiKey.text.toString().trim()
        prefs.baseUrl = baseUrl.text.toString().trim()
        prefs.model = model.text.toString().trim()
        prefs.sendThumbnails = thumbs.isChecked
        prefs.helperType = selectedHelper()
        prefs.helperKey = helperKey.text.toString().trim()
        prefs.helperBaseUrl = helperBaseUrl.text.toString().trim()
        prefs.helperModel = helperModel.text.toString().trim()
        prefs.hideShorts = hideShorts.isChecked
        prefs.hideComments = hideComments.isChecked
        prefs.bumpVersion()
        toast("Saved")
    }

    /** Runs the current rules against two example videos so the parent can see it working. */
    private fun testAi() {
        val config = ProviderConfig(
            type = selectedProvider(),
            apiKey = apiKey.text.toString().trim(),
            baseUrl = baseUrl.text.toString().trim(),
            model = model.text.toString().trim(),
            accountId = accountId.text.toString().trim(),
            sendThumbnails = false,
        )
        val ruleText = rules.text.toString()
        testResult.text = "Checking…"
        io.execute {
            val message = try {
                val classifier = Classifiers.create(config) ?: throw IllegalStateException("Pick a provider first")
                val samples = listOf(
                    VideoInfo("aaaaaaaaaaa", "Five Little Pumpkins | Halloween Song for Kids", "Super Simple Songs"),
                    VideoInfo("bbbbbbbbbbb", "Wheels On The Bus | Kids Songs", "Super Simple Songs"),
                )
                val scores = classifier.classify(samples, ruleText).associateBy { it.id }
                val strict = selectedStrictness()
                samples.joinToString("\n") { v ->
                    val s = scores[v.id]
                    if (s == null) "• ${v.title}: no answer"
                    else {
                        val verdict = if (strict.verdictFor(s.p) == Verdict.BLOCK) "HIDDEN" else "shown"
                        "• ${v.title}: $verdict (${(s.p * 100).toInt()}%, ${s.reason})"
                    }
                }
            } catch (e: Exception) {
                "Didn't work: ${e.message}"
            }
            main.post { testResult.text = message }
        }
    }

    // ------------------------------------------------------------------ blocking videos and channels

    /** When opened from a video or channel page, offer to block it in one tap. */
    private fun addCurrentPageSection(page: LinearLayout) {
        val url = intent.getStringExtra(EXTRA_CURRENT_URL) ?: return
        val videoId = VideoLinks.videoId(url)
        val channelRef = ChannelBlocklist.normalizeId(Uri.parse(url).path ?: "")
        if (videoId == null && channelRef == null) return

        page.addView(heading("Open right now"))
        val label = note(videoId?.let { "Video $it" } ?: channelRef!!)
        page.addView(label)
        val buttons = column()
        page.addView(buttons)

        if (videoId == null) {
            buttons.addView(button("Block this channel") { blockChannel(listOf(channelRef!!)) })
            return
        }
        // Look up the title and channel so the parent sees what they're blocking.
        io.execute {
            val info = YouTubeOEmbed.fetch(videoId)
            main.post {
                if (info != null) label.text = "\u201c${info.title}\u201d from ${info.channel}"
                buttons.addView(button("Block this video") { blockVideo(videoId, info?.title) })
                if (info != null && info.channel.isNotBlank()) {
                    buttons.addView(button("Block everything from ${info.channel}") {
                        blockChannel(listOfNotNull(info.channelId.ifBlank { null }, info.channel))
                    })
                }
            }
        }
    }

    private fun blockFromInput() {
        val text = blockInput.text.toString().trim()
        if (text.isEmpty()) return
        val videoId = if (ChannelBlocklist.isChannelLink(text)) null else VideoLinks.videoId(text)
        if (videoId != null) {
            io.execute {
                val title = YouTubeOEmbed.fetch(videoId)?.title
                main.post { blockVideo(videoId, title) }
            }
        } else {
            blockChannel(listOfNotNull(ChannelBlocklist.parse(text)))
        }
        blockInput.setText("")
    }

    private fun blockVideo(id: String, title: String?) {
        io.execute { store.setOverride(id, Verdict.BLOCK, title) }
        prefs.bumpVersion()
        toast("That video is now always hidden")
        refreshListsLater()
    }

    /** Stores the handle and the display name, so the block works whichever one a page shows. */
    private fun blockChannel(entries: List<String>) {
        entries.forEach { prefs.addBlockedChannel(it) }
        prefs.bumpVersion()
        toast("Channel blocked")
        refreshListsLater()
    }

    private fun suggestKeywords() {
        val config = ProviderConfig(
            type = selectedHelper(),
            apiKey = helperKey.text.toString().trim(),
            baseUrl = helperBaseUrl.text.toString().trim(),
            model = helperModel.text.toString().trim(),
        )
        if (config.type == ProviderType.NONE) {
            suggestResult.text = "Pick a keyword helper AI further down first (Gemini's free tier works)."
            return
        }
        val ruleText = rules.text.toString()
        val existing = KeywordFilter.parseList(keywords.text.toString())
        suggestResult.text = "Thinking\u2026"
        io.execute {
            val result = try {
                Result.success(KeywordSuggester(config).suggest(ruleText, existing))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                result.onSuccess { added ->
                    if (added.isEmpty()) {
                        suggestResult.text = "No new keywords to add."
                    } else {
                        keywords.setText((existing + added).joinToString(", "))
                        suggestResult.text = "Added ${added.size} keywords. Remove any you don't want, then Save."
                    }
                }.onFailure { suggestResult.text = "Didn't work: ${it.message}" }
            }
        }
    }

    // ------------------------------------------------------------------ hidden videos and overrides

    private fun refreshListsLater() = main.postDelayed({ refreshLists() }, 300)

    private fun refreshLists() {
        io.execute {
            val hidden = store.recentlyHidden()
            val choices = store.parentChoices()
            main.post {
                hiddenList.removeAllViews()
                if (hidden.isEmpty()) hiddenList.addView(note("Nothing hidden yet."))
                hidden.forEach { h ->
                    val why = buildString {
                        append(h.reason.ifBlank { "Matched your rules" })
                        h.score?.let { append(" · ").append((it * 100).toInt()).append('%') }
                    }
                    hiddenList.addView(row(h.title.ifBlank { h.id }, listOf(h.channel, why).filter { it.isNotBlank() }.joinToString(" · "), "Allow") {
                        io.execute { store.setOverride(h.id, Verdict.ALLOW, h.title) }
                        prefs.bumpVersion()
                        refreshListsLater()
                    })
                }
                channelsList.removeAllViews()
                val channels = prefs.blockedChannelList()
                if (channels.isEmpty()) channelsList.addView(note("None."))
                channels.forEach { c ->
                    channelsList.addView(row(c, "Every video hidden", "Remove") {
                        prefs.removeBlockedChannel(c)
                        prefs.bumpVersion()
                        refreshListsLater()
                    })
                }
                choicesList.removeAllViews()
                if (choices.isEmpty()) choicesList.addView(note("You haven't allowed or blocked anything by hand."))
                choices.forEach { c ->
                    val label = if (c.verdict == Verdict.ALLOW) "Always allowed" else "Always hidden"
                    choicesList.addView(row(c.title.ifBlank { c.id }, label, "Undo") {
                        io.execute { store.setOverride(c.id, null) }
                        prefs.bumpVersion()
                        refreshListsLater()
                    })
                }
            }
        }
    }

    // ------------------------------------------------------------------ small view helpers

    private fun row(title: String, subtitle: String, action: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            val texts = column().apply {
                addView(TextView(this@ParentActivity).apply { text = title; setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(this@ParentActivity).apply { text = subtitle; alpha = 0.7f; textSize = 13f })
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@ParentActivity).apply { text = action; setOnClickListener { onClick() } })
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        alpha = 0.75f
        textSize = 14f
    }

    /** Radio ids are the enum ordinal + 1 (0 isn't a usable view id). */
    private fun radio(label: String, id: Int, checked: Boolean) = RadioButton(this).apply {
        text = label
        this.id = id
        isChecked = checked
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun pinField() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        hint = "PIN"
    }

    private fun padded(v: View) = LinearLayout(this).apply {
        setPadding(dp(24), dp(8), dp(24), 0)
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SETUP = "setup"
        const val EXTRA_CURRENT_URL = "currentUrl"
    }
}
