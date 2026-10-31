package com.vipin25.bubbletranslate.data

import com.vipin25.bubbletranslate.domain.LinkCheckRepository
import com.vipin25.bubbletranslate.domain.model.LinkCheckResult
import com.vipin25.bubbletranslate.domain.model.RiskSignal
import com.vipin25.bubbletranslate.domain.model.SignalType
import com.vipin25.bubbletranslate.domain.model.Verdict
import com.vipin25.bubbletranslate.domain.model.Weight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

class HeuristicLinkCheckRepository : LinkCheckRepository {

    override suspend fun checkUrl(url: String): LinkCheckResult {
        return withContext(Dispatchers.IO) {
            val signals = listOfNotNull(
                checkHttpOnly(url),
                checkIpHost(url),
                checkSuspiciousTld(url),
                checkExcessSubdomains(url),
                checkBrandKeyword(url),
                checkTyposquat(url),
                checkLongUrl(url),
                checkUrlShortener(url),
                checkPunycode(url)
            )
            LinkCheckResult(url, score(signals), signals)
        }
    }

    private fun score(signals: List<RiskSignal>): Verdict {
        val highCount = signals.count { it.weight == Weight.HIGH }
        return when {
            signals.isEmpty()  -> Verdict.SAFE
            highCount >= 2     -> Verdict.PHISHING
            else               -> Verdict.SUSPICIOUS
        }
    }

    private fun getHostSafe(url: String): String {
        return try { URI.create(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
    }

    private fun checkHttpOnly(url: String): RiskSignal? {
        val scheme = try { URI.create(url).scheme?.lowercase() } catch (e: Exception) { null }
        if (scheme == "http") {
            return RiskSignal(SignalType.HTTP_ONLY, "Unencrypted connection (HTTP)", Weight.MEDIUM)
        }
        return null
    }

    private fun checkIpHost(url: String): RiskSignal? {
        val host = getHostSafe(url)
        if (host.isEmpty()) return null
        val regex = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
        if (regex.matches(host)) {
            return RiskSignal(SignalType.IP_HOST, "IP address used as host", Weight.HIGH)
        }
        return null
    }

    private fun checkSuspiciousTld(url: String): RiskSignal? {
        val host = getHostSafe(url)
        if (host.isEmpty()) return null
        val tld = host.substringAfterLast('.', "")
        val denyList = listOf("xyz", "tk", "ml", "ga", "cf", "gq", "pw", "top", "click", "link")
        if (tld in denyList) {
            return RiskSignal(SignalType.SUSPICIOUS_TLD, "Suspicious top-level domain (.$tld)", Weight.MEDIUM)
        }
        return null
    }

    private fun checkExcessSubdomains(url: String): RiskSignal? {
        val host = getHostSafe(url)
        if (host.isEmpty()) return null
        if (host.split('.').size > 3) {
            return RiskSignal(SignalType.EXCESS_SUBDOMAINS, "Excessive subdomain nesting", Weight.HIGH)
        }
        return null
    }

    private fun checkBrandKeyword(url: String): RiskSignal? {
        val host = getHostSafe(url)
        if (host.isEmpty()) return null
        
        val keywordList = listOf("bank", "secure", "verify", "account", "signin", "login", "update", "confirm")
        for (keyword in keywordList) {
            if (host.contains(keyword)) {
                return RiskSignal(SignalType.BRAND_KEYWORD, "Brand name '$keyword' in untrusted domain", Weight.HIGH)
            }
        }
        
        val brands = mapOf(
            "paypal" to "paypal.com",
            "amazon" to "amazon.com",
            "google" to "google.com",
            "apple" to "apple.com",
            "microsoft" to "microsoft.com",
            "facebook" to "facebook.com",
            "instagram" to "instagram.com",
            "netflix" to "netflix.com"
        )
        
        for ((brand, validDomain) in brands) {
            if (host.contains(brand)) {
                if (host != validDomain && !host.endsWith(".$validDomain")) {
                    return RiskSignal(SignalType.BRAND_KEYWORD, "Brand name '$brand' in untrusted domain", Weight.HIGH)
                }
            }
        }
        return null
    }

    private fun checkTyposquat(url: String): RiskSignal? {
        val host = getHostSafe(url)
        if (host.isEmpty()) return null

        val targets = listOf("paypal.com", "amazon.com", "google.com", "apple.com", 
                             "microsoft.com", "facebook.com", "instagram.com", 
                             "netflix.com", "twitter.com", "linkedin.com")
                             
        val labels = host.split("[.-]".toRegex())
        
        for (target in targets) {
            if (host.endsWith(target)) continue // valid match, not a typosquat
            
            val brand = target.substringBeforeLast('.')
            
            // Check FULL registered domain just in case
            val parts = host.split(".")
            if (parts.size >= 2) {
                val registeredDomain = parts.takeLast(2).joinToString(".")
                if (levenshtein(registeredDomain, target) in 1..2) {
                    return RiskSignal(SignalType.TYPOSQUAT, "Possible typosquat of $brand", Weight.HIGH)
                }
            }
            
            // Check individual labels for close matches to the brand root
            for (label in labels) {
                if (label.length >= 3 && levenshtein(label, brand) in 1..2) {
                    return RiskSignal(SignalType.TYPOSQUAT, "Possible typosquat of $brand", Weight.HIGH)
                }
            }
        }
        return null
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    private fun checkLongUrl(url: String): RiskSignal? {
        if (url.length > 100) {
            return RiskSignal(SignalType.LONG_URL, "Unusually long URL (${url.length} chars)", Weight.MEDIUM)
        }
        return null
    }

    private fun checkUrlShortener(url: String): RiskSignal? {
        val host = getHostSafe(url)
        val list = listOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "buff.ly", "shorturl.at", "rb.gy", "is.gd", "v.gd")
        if (host in list) {
            return RiskSignal(SignalType.URL_SHORTENER, "URL shortener — destination is hidden", Weight.LOW)
        }
        return null
    }

    private fun checkPunycode(url: String): RiskSignal? {
        val host = getHostSafe(url)
        if (host.split('.').any { it.startsWith("xn--") }) {
            return RiskSignal(SignalType.PUNYCODE, "Punycode / homograph domain detected", Weight.HIGH)
        }
        return null
    }
}
