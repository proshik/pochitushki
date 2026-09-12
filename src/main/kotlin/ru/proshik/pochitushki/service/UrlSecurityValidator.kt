package ru.proshik.pochitushki.service

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Thrown when a URL targets a disallowed scheme or a non-public network address.
 */
class SsrfValidationException(message: String) : RuntimeException(message)

/**
 * SSRF guard for outbound fetches of user-supplied URLs (link titles, OG images, PDF rendering).
 *
 * Rejects everything that is not an http(s) URL resolving exclusively to public IP addresses.
 * This blocks cloud metadata (169.254.169.254), loopback, link-local, RFC1918 and IPv6
 * unique-local ranges, `file:`/`ftp:`/`gopher:` schemes, and the reserved ranges the JDK's own
 * predicates miss (see [isReservedRange]). Every address the host resolves to is checked, not
 * just the first.
 *
 * Two limitations to keep in mind when adding callers:
 *
 * 1. Only the URL passed in is vetted. A public URL that 3xx-redirects to a private address
 *    bypasses the guard entirely, so callers **must** disable redirect-following on the request
 *    they make — `HttpURLConnection` and Jsoup both follow redirects by default. This also means
 *    the guard cannot protect a component that fetches sub-resources on its own: a headless
 *    browser loads whatever the page references, so [PlaywrightPdfGenerator] is not covered by
 *    validating its entry URL.
 * 2. It validates the host's currently-resolved addresses, so it does not defeat DNS-rebinding
 *    (a host re-resolving to a private IP between validation and connection). Full protection
 *    requires pinning the validated IP for the actual connection.
 */
@Component
class UrlSecurityValidator(
    // Hosts exempt from the IP checks (e.g. "localhost" in tests). Empty in production.
    @Value("\${app.security.ssrf.allowed-hosts:}") allowedHosts: List<String> = emptyList(),
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val allowedHosts: Set<String> = allowedHosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun isAllowed(rawUrl: String): Boolean = try {
        validate(rawUrl)
        true
    } catch (e: SsrfValidationException) {
        false
    }

    fun validate(rawUrl: String) {
        val uri = try {
            URI(rawUrl)
        } catch (e: Exception) {
            throw SsrfValidationException("Malformed URL")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw SsrfValidationException("Unsupported scheme: $scheme")
        }

        val host = uri.host ?: throw SsrfValidationException("Missing host")

        if (host.lowercase() in allowedHosts) {
            return
        }

        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            throw SsrfValidationException("Cannot resolve host: $host")
        }

        if (addresses.isEmpty()) {
            throw SsrfValidationException("Cannot resolve host: $host")
        }

        addresses.forEach { addr ->
            if (isBlocked(addr)) {
                logger.warn("Blocked SSRF attempt: host={} resolved to non-public address {}", host, addr.hostAddress)
                throw SsrfValidationException("Host resolves to a non-public address: ${addr.hostAddress}")
            }
        }
    }

    private fun isBlocked(addr: InetAddress): Boolean =
        addr.isLoopbackAddress ||
            addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress ||   // covers 169.254.0.0/16 (incl. 169.254.169.254) and fe80::/10
            addr.isSiteLocalAddress ||   // covers 10/8, 172.16/12, 192.168/16
            addr.isMulticastAddress ||
            isReservedRange(addr)

    /**
     * Ranges the JDK's own predicates miss.
     *
     * IPv4 is a blocklist: Java's checks cover loopback, link-local, RFC1918 and multicast, and
     * what is listed below is either reachable-but-not-public (carrier NAT, "this network") or a
     * transition mechanism that embeds an IPv4 address and so can be pointed at an internal host.
     *
     * IPv6 is the other way round — an allowlist of global unicast — because the JDK predicates
     * do not cover unique-local fc00::/7 at all. See the comment in the 16-byte branch.
     */
    private fun isReservedRange(addr: InetAddress): Boolean {
        val b = addr.address.map { it.toInt() and 0xff }

        if (b.size == 4) {
            return when {
                b[0] == 0 -> true                                  // 0.0.0.0/8 "this network"
                b[0] == 100 && b[1] in 64..127 -> true             // 100.64.0.0/10 carrier-grade NAT
                b[0] == 192 && b[1] == 0 && b[2] == 0 -> true      // 192.0.0.0/24 IETF assignments
                b[0] == 198 && (b[1] == 18 || b[1] == 19) -> true  // 198.18.0.0/15 benchmarking
                b[0] >= 240 -> true                                // 240.0.0.0/4 reserved + broadcast
                else -> false
            }
        }

        if (b.size == 16) {
            // IPv6 is an allowlist, not a blocklist. Java's own predicates are thinner here than
            // they look — isSiteLocalAddress only knows the deprecated fec0::/10 and says false
            // for fc00::/7 (unique-local), which is what a Docker or cloud internal network
            // actually uses. Enumerating what to block would keep missing ranges, so only
            // global unicast 2000::/3 is allowed through and everything else (fc00::/7,
            // 64:ff9b::/96, 100::/64, ::/128, ::1, IPv4-compatible ::a.b.c.d) is refused.
            if (b[0] and 0xe0 != 0x20) return true

            val first32 = listOf(b[0], b[1], b[2], b[3])
            return when {
                // 2002::/16 — 6to4, embeds an IPv4 address in bytes 2..5
                b[0] == 0x20 && b[1] == 0x02 -> true
                // 2001::/32 — Teredo tunnelling, also embeds IPv4
                first32 == listOf(0x20, 0x01, 0x00, 0x00) -> true
                // 2001:db8::/32 — documentation range, never legitimately routed
                first32 == listOf(0x20, 0x01, 0x0d, 0xb8) -> true
                else -> false
            }
        }

        return false
    }
}
