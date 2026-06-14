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
 * This blocks cloud metadata (169.254.169.254), loopback, link-local and RFC1918 ranges, as well
 * as `file:`/`ftp:`/`gopher:` schemes.
 *
 * Note: this validates the host's currently-resolved addresses. It does not by itself defeat
 * DNS-rebinding (a host re-resolving to a private IP between validation and connection) — callers
 * should additionally disable redirect-following. Full protection requires pinning the validated
 * IP for the actual connection.
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
            isUniqueLocalIpv6(addr)

    // IPv6 Unique Local Addresses (fc00::/7) are not covered by isSiteLocalAddress.
    private fun isUniqueLocalIpv6(addr: InetAddress): Boolean {
        val bytes = addr.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }
}
