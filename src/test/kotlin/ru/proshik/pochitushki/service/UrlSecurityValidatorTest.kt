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
}
