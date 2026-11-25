package com.homebudget.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class CrashReportTest {
    private fun report(e: Throwable) = CrashReporter.report(e, "0.1.2", 3, "2026-11-24T10:00:00Z", 36, "Google Pixel")

    @Test
    fun reportHasVersionDeviceAndFrames() {
        val r = report(IllegalStateException("x"))
        assertTrue(r.contains("App: 0.1.2 (3)"))
        assertTrue(r.contains("Android SDK: 36"))
        assertTrue(r.contains("Device: Google Pixel"))
        assertTrue(r.contains("java.lang.IllegalStateException"))
        assertTrue(r.contains("    at "))
    }

    @Test
    fun messagesThatMayHoldUserDataAreDropped() {
        val r = report(IllegalArgumentException("Payee Sharma paid 4500.00"))
        assertFalse(r.contains("Sharma"))
        assertFalse(r.contains("4500"))
    }

    @Test
    fun nestedCauseMessagesAreAlsoDropped() {
        val r = report(RuntimeException("outer note", NumberFormatException("For input string: \"123.45\"")))
        assertTrue(r.contains("Caused by: java.lang.NumberFormatException"))
        assertFalse(r.contains("123.45"))
        assertFalse(r.contains("outer note"))
    }

    @Test
    fun nullPointerMessagesDescribeCodeAndAreKept() {
        val r = report(NullPointerException("Attempt to read from field 'a' on a null object reference"))
        assertTrue(r.contains("on a null object reference"))
    }

    @Test
    fun longStackTracesAreCapped() {
        val e = RuntimeException()
        e.stackTrace = Array(100) { StackTraceElement("C", "m$it", "F.kt", it) }
        val r = report(e)
        assertTrue(r.contains("... 60 more"))
    }

    @Test
    fun selfReferencingCauseDoesNotLoop() {
        val a = RuntimeException()
        val b = RuntimeException(a)
        a.initCause(b)
        assertTrue(report(a).isNotEmpty())
    }

    @Test
    fun issueUrlNeedsHttpsIssuesBase() {
        assertNull(BugReport.issueUrl("", "t", "b"))
        assertNull(BugReport.issueUrl("http://github.com/a/b/issues", "t", "b"))
        assertNull(BugReport.issueUrl("https://github.com/a/b", "t", "b"))
    }

    @Test
    fun issueUrlEncodesTitleAndBody() {
        val url = BugReport.issueUrl("https://github.com/PintuPx/talenta/issues/", "Crash in 0.1.2", "line 1\n& more")!!
        assertTrue(url.startsWith("https://github.com/PintuPx/talenta/issues/new?labels=bug&title="))
        assertFalse(url.contains(" "))
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertEquals("line 1\n& more", body)
    }

    @Test
    fun issueBodyIsTruncated() {
        val url = BugReport.issueUrl("https://github.com/a/b/issues", "t", "x".repeat(20_000))!!
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(body.length < BugReport.MAX_BODY + 50)
        assertTrue(body.endsWith("(truncated)"))
    }

    @Test
    fun bugBodyContainsVersionAndReport() {
        val b = BugReport.body("0.1.2", "Talenta crash report")
        assertTrue(b.contains("**App version:** 0.1.2"))
        assertTrue(b.contains("Talenta crash report"))
        assertFalse(BugReport.body("0.1.2", null).contains("Crash report"))
    }
}
