package io.github.kmpdevicehub.idea

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Exercises the real signature and chain checks with a throwaway CA standing in for JetBrains'. */
class LicenseVerifierTest {
    companion object {
        lateinit var ca: X509Certificate
        lateinit var otherCa: X509Certificate
        lateinit var leafKey: PrivateKey
        lateinit var leafCert: X509Certificate

        private fun keytool(dir: File, vararg args: String) {
            val tool = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(listOf(tool) + args).directory(dir).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "keytool ${args.toList()}: $out" }
        }

        @BeforeClass @JvmStatic
        fun makeCertificates() {
            val dir = Files.createTempDirectory("lic").toFile()
            val ks = arrayOf("-keystore", "ks.p12", "-storetype", "PKCS12", "-storepass", "secret", "-keypass", "secret")
            keytool(dir, "-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Test License CA", "-ext", "bc:c", "-validity", "3650", *ks)
            keytool(dir, "-genkeypair", "-alias", "other", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Untrusted CA", "-ext", "bc:c", "-validity", "3650", *ks)
            keytool(dir, "-genkeypair", "-alias", "leaf", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Test License Signer", "-validity", "3650", *ks)
            keytool(dir, "-certreq", "-alias", "leaf", "-file", "leaf.csr", *ks)
            keytool(dir, "-gencert", "-alias", "ca", "-infile", "leaf.csr", "-outfile", "leaf.cer", "-validity", "3650", *ks)
            keytool(dir, "-importcert", "-alias", "leaf", "-file", "leaf.cer", "-noprompt", *ks)
            val store = KeyStore.getInstance("PKCS12").apply { File(dir, "ks.p12").inputStream().use { load(it, "secret".toCharArray()) } }
            ca = store.getCertificate("ca") as X509Certificate
            otherCa = store.getCertificate("other") as X509Certificate
            leafKey = store.getKey("leaf", "secret".toCharArray()) as PrivateKey
            leafCert = store.getCertificate("leaf") as X509Certificate
        }

        private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

        private fun sign(algorithm: String, data: ByteArray) = Signature.getInstance(algorithm).apply { initSign(leafKey); update(data) }.sign()
    }

    private fun licenseKey(id: String, claimedId: String = id, tamper: Boolean = false): String {
        val data = """{"licenseId":"$claimedId","products":[{"code":"PKMPDEVICEHUB"}]}""".toByteArray()
        val signature = sign("SHA1withRSA", data)
        val signed = if (tamper) """{"licenseId":"$claimedId","products":[{"code":"PEVERYTHING"}]}""".toByteArray() else data
        return "key:$id-${b64(signed)}-${b64(signature)}-${b64(leafCert.encoded)}"
    }

    @Test fun `accepts a key signed under the trusted root`() {
        assertTrue(LicenseVerifier(listOf(ca)).isValid(licenseKey("ABC123")))
    }

    @Test fun `rejects forged, tampered, mismatched and made-up stamps`() {
        val verifier = LicenseVerifier(listOf(ca))
        assertFalse("untrusted root", LicenseVerifier(listOf(otherCa)).isValid(licenseKey("ABC123")))
        assertFalse("tampered data", verifier.isValid(licenseKey("ABC123", tamper = true)))
        assertFalse("license id mismatch", verifier.isValid(licenseKey("ABC123", claimedId = "OTHER")))
        assertFalse(verifier.isValid("eval:20261231"))
        assertFalse(verifier.isValid("key:not-a-real-key"))
        assertFalse(verifier.isValid("anything"))
        assertFalse(verifier.isValid(null))
        assertFalse("real JetBrains roots don't trust the test CA", LicenseVerifier().isValid(licenseKey("ABC123")))
    }

    @Test fun `license server stamps must be signed, for this machine, and fresh`() {
        val time = System.currentTimeMillis()
        fun stamp(expected: String, machine: String) =
            "stamp:$expected:$time:$machine:SHA256withRSA:${b64(sign("SHA256withRSA", "$time:$machine".toByteArray()))}:${b64(leafCert.encoded)}"
        assertTrue(LicenseVerifier(listOf(ca)) { time + 60_000 }.isValid(stamp("m1", "m1")))
        assertFalse("stale", LicenseVerifier(listOf(ca)) { time + 2 * 60 * 60 * 1000 }.isValid(stamp("m1", "m1")))
        assertFalse("other machine", LicenseVerifier(listOf(ca)) { time }.isValid(stamp("m1", "m2")))
    }

    @Test fun `ships JetBrains' two public roots`() {
        assertEquals(listOf("CN=JetProfile CA", "CN=License Servers CA"), LicenseVerifier.JETBRAINS_ROOTS.map { it.subjectX500Principal.name })
    }
}
