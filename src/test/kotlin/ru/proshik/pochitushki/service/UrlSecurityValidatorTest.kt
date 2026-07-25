package ru.proshik.pochitushki.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UrlSecurityValidatorTest {

    private val validator = UrlSecurityValidator()

    @Test
    fun `allows public literal IP over http and https`() {
        assertTrue(validator.isAllowed("http://8.8.8.8/page"))
        assertTrue(validator.isAllowed("https://1.1.1.1/"))
    }

    @Test
    fun `rejects loopback`() {
        assertFalse(validator.isAllowed("http://127.0.0.1/"))
        assertFalse(validator.isAllowed("http://localhost/admin"))
        assertFalse(validator.isAllowed("http://[::1]/"))
    }

    @Test
    fun `rejects cloud metadata link-local address`() {
        assertFalse(validator.isAllowed("http://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun `rejects private RFC1918 ranges`() {
        assertFalse(validator.isAllowed("http://10.0.0.5/"))
        assertFalse(validator.isAllowed("http://192.168.1.1/"))
        assertFalse(validator.isAllowed("http://172.16.0.10/"))
    }

    @Test
    fun `rejects non-http schemes`() {
        assertFalse(validator.isAllowed("file:///etc/passwd"))
        assertFalse(validator.isAllowed("ftp://8.8.8.8/"))
        assertFalse(validator.isAllowed("gopher://8.8.8.8/"))
    }

    @Test
    fun `rejects malformed url`() {
        assertFalse(validator.isAllowed("not-a-url"))
        assertFalse(validator.isAllowed(""))
    }

    @Test
    fun `validate throws for blocked url`() {
        assertThrows(SsrfValidationException::class.java) {
            validator.validate("http://169.254.169.254/")
        }
    }

    @Test
    fun `validate passes for public url`() {
        validator.validate("https://8.8.8.8/")
    }

    @Test
    fun `rejects reserved IPv4 ranges the JDK does not classify as private`() {
        assertFalse(validator.isAllowed("http://100.64.0.1/"), "carrier-grade NAT (100.64.0.0/10)")
        assertFalse(validator.isAllowed("http://100.127.255.255/"), "upper end of 100.64.0.0/10")
        assertFalse(validator.isAllowed("http://0.1.2.3/"), "0.0.0.0/8 'this network'")
        assertFalse(validator.isAllowed("http://192.0.0.1/"), "IETF assignments (192.0.0.0/24)")
        assertFalse(validator.isAllowed("http://198.18.0.1/"), "benchmarking (198.18.0.0/15)")
        assertFalse(validator.isAllowed("http://240.0.0.1/"), "reserved (240.0.0.0/4)")
        assertFalse(validator.isAllowed("http://255.255.255.255/"), "broadcast")
    }

    @Test
    fun `rejects IPv6 transition ranges that embed an IPv4 address`() {
        // Would reach the metadata endpoint through a NAT64 gateway.
        assertFalse(validator.isAllowed("http://[64:ff9b::a9fe:a9fe]/"), "NAT64 (64:ff9b::/96)")
        assertFalse(validator.isAllowed("http://[2002:a9fe:a9fe::]/"), "6to4 (2002::/16)")
        assertFalse(validator.isAllowed("http://[2001:0:1234::1]/"), "Teredo (2001::/32)")
        assertFalse(validator.isAllowed("http://[100::1]/"), "discard-only (100::/64)")
    }

    @Test
    fun `still allows ordinary public IPv4 near the reserved boundaries`() {
        assertTrue(validator.isAllowed("http://100.63.255.255/"), "just below 100.64.0.0/10")
        assertTrue(validator.isAllowed("http://100.128.0.1/"), "just above 100.64.0.0/10")
        assertTrue(validator.isAllowed("http://192.0.1.1/"), "just above 192.0.0.0/24")
        assertTrue(validator.isAllowed("http://198.20.0.1/"), "just above 198.18.0.0/15")
        assertTrue(validator.isAllowed("http://223.255.255.254/"), "just below the multicast range")
    }

    @Test
    fun `rejects IPv4-mapped IPv6 forms of blocked addresses`() {
        assertFalse(validator.isAllowed("http://[::ffff:127.0.0.1]/"))
        assertFalse(validator.isAllowed("http://[::ffff:a9fe:a9fe]/"), "169.254.169.254 in mapped form")
    }

    @Test
    fun `userinfo in the authority does not mask the real host`() {
        assertFalse(validator.isAllowed("http://user@169.254.169.254/"))
        assertFalse(validator.isAllowed("http://169.254.169.254@127.0.0.1/"))
    }
}
