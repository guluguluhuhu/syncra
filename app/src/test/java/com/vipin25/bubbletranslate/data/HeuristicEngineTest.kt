package com.vipin25.bubbletranslate.data

import com.vipin25.bubbletranslate.domain.model.SignalType
import com.vipin25.bubbletranslate.domain.model.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicEngineTest {

    private val engine = HeuristicLinkCheckRepository()

    @Test
    fun given_httpScheme_when_checked_then_firesHttpOnlySignal() = runBlocking {
        val result = engine.checkUrl("http://example.com")
        assertTrue(result.signals.any { it.type == SignalType.HTTP_ONLY })
    }

    @Test
    fun given_httpsScheme_when_checked_then_noHttpOnlySignal() = runBlocking {
        val result = engine.checkUrl("https://example.com")
        assertTrue(result.signals.none { it.type == SignalType.HTTP_ONLY })
    }

    @Test
    fun given_ipv4Host_when_checked_then_firesIpHostSignal() = runBlocking {
        val result = engine.checkUrl("https://192.168.1.1/path")
        assertTrue(result.signals.any { it.type == SignalType.IP_HOST })
    }

    @Test
    fun given_normalDomain_when_checked_then_noIpHostSignal() = runBlocking {
        val result = engine.checkUrl("https://example.com/path")
        assertTrue(result.signals.none { it.type == SignalType.IP_HOST })
    }

    @Test
    fun given_xyzTld_when_checked_then_firesSuspiciousTldSignal() = runBlocking {
        val result = engine.checkUrl("https://example.xyz")
        assertTrue(result.signals.any { it.type == SignalType.SUSPICIOUS_TLD })
    }

    @Test
    fun given_comTld_when_checked_then_noSuspiciousTldSignal() = runBlocking {
        val result = engine.checkUrl("https://example.com")
        assertTrue(result.signals.none { it.type == SignalType.SUSPICIOUS_TLD })
    }

    @Test
    fun given_fourLevelSubdomain_when_checked_then_firesExcessSignal() = runBlocking {
        val result = engine.checkUrl("https://a.b.c.d.evil.com")
        assertTrue(result.signals.any { it.type == SignalType.EXCESS_SUBDOMAINS })
    }

    @Test
    fun given_threeLevelSubdomain_when_checked_then_noExcessSignal() = runBlocking {
        val result = engine.checkUrl("https://a.b.com")
        assertTrue(result.signals.none { it.type == SignalType.EXCESS_SUBDOMAINS })
    }

    @Test
    fun given_paypalInNonPaypalDomain_when_checked_then_firesBrandSignal() = runBlocking {
        val result = engine.checkUrl("https://paypal-secure.com")
        assertTrue(result.signals.any { it.type == SignalType.BRAND_KEYWORD })
    }

    @Test
    fun given_paypalDotCom_when_checked_then_noBrandSignal() = runBlocking {
        val result = engine.checkUrl("https://paypal.com")
        assertTrue(result.signals.none { it.type == SignalType.BRAND_KEYWORD })
    }

    @Test
    fun given_paypa1DotCom_when_checked_then_firesTyposquatSignal() = runBlocking {
        val result = engine.checkUrl("https://paypa1.com")
        assertTrue(result.signals.any { it.type == SignalType.TYPOSQUAT })
    }

    @Test
    fun given_googleDotCom_when_checked_then_noTyposquatSignal() = runBlocking {
        val result = engine.checkUrl("https://google.com")
        assertTrue(result.signals.none { it.type == SignalType.TYPOSQUAT })
    }

    @Test
    fun given_urlOver100Chars_when_checked_then_firesLongUrlSignal() = runBlocking {
        val longUrl = "https://example.com/" + "a".repeat(100)
        val result = engine.checkUrl(longUrl)
        assertTrue(result.signals.any { it.type == SignalType.LONG_URL })
    }

    @Test
    fun given_shortUrl_when_checked_then_noLongUrlSignal() = runBlocking {
        val result = engine.checkUrl("https://example.com/short")
        assertTrue(result.signals.none { it.type == SignalType.LONG_URL })
    }

    @Test
    fun given_bitlyHost_when_checked_then_firesShortenerSignal() = runBlocking {
        val result = engine.checkUrl("https://bit.ly/foo")
        assertTrue(result.signals.any { it.type == SignalType.URL_SHORTENER })
    }

    @Test
    fun given_githubHost_when_checked_then_noShortenerSignal() = runBlocking {
        val result = engine.checkUrl("https://github.com/foo")
        assertTrue(result.signals.none { it.type == SignalType.URL_SHORTENER })
    }

    @Test
    fun given_punycodeLabel_when_checked_then_firesPunycodeSignal() = runBlocking {
        val result = engine.checkUrl("https://xn--pypl-ppaj.com")
        assertTrue(result.signals.any { it.type == SignalType.PUNYCODE })
    }

    @Test
    fun given_asciiDomain_when_checked_then_noPunycodeSignal() = runBlocking {
        val result = engine.checkUrl("https://example.com")
        assertTrue(result.signals.none { it.type == SignalType.PUNYCODE })
    }

    @Test
    fun given_noSignals_when_scored_then_verdictIsSafe() = runBlocking {
        val result = engine.checkUrl("https://github.com")
        assertEquals(Verdict.SAFE, result.verdict)
    }

    @Test
    fun given_oneHighSignal_when_scored_then_verdictIsSuspicious() = runBlocking {
        val result = engine.checkUrl("https://login.example.com/path")
        assertEquals(Verdict.SUSPICIOUS, result.verdict)
    }

    @Test
    fun given_twoHighSignals_when_scored_then_verdictIsPhishing() = runBlocking {
        val result = engine.checkUrl("https://xn--pypl-a.b.c.d.com")
        assertEquals(Verdict.PHISHING, result.verdict)
    }
}
