package com.homebudget.app.crash

import java.net.URLEncoder

/** Builds a "new GitHub issue" link with the title and body filled in. The user reviews it before submitting. */
object BugReport {
    /** GitHub rejects very long URLs; keep the body well under the limit. */
    const val MAX_BODY = 5000

    fun issueUrl(issuesBase: String, title: String, body: String): String? {
        val base = issuesBase.trim().trimEnd('/')
        if (!base.startsWith("https://") || !base.endsWith("/issues")) return null
        val trimmed = if (body.length > MAX_BODY) body.take(MAX_BODY) + "\n... (truncated)" else body
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "$base/new?labels=bug&title=${enc(title)}&body=${enc(trimmed)}"
    }

    fun body(version: String, report: String?): String = buildString {
        appendLine("**What happened?**")
        appendLine("<!-- Describe what you tapped. Do not include amounts, notes or other personal data. -->")
        appendLine()
        appendLine("**App version:** $version")
        if (report != null) {
            appendLine()
            appendLine("**Crash report**")
            appendLine("```")
            appendLine(report.trim())
            appendLine("```")
        }
    }
}
