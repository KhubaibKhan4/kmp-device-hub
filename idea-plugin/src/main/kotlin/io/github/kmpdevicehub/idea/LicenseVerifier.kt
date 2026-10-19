package io.github.kmpdevicehub.idea

import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertPathBuilder
import java.security.cert.CertPathValidator
import java.security.cert.CertStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Checks that a license confirmation stamp from the IDE was really issued by JetBrains, as JetBrains' reference
 * implementation does (marketplace-makemecoffee-plugin, Apache-2.0):
 *  - `key:<id>-<license data>-<signature>-<certificate>`: a license key from a JetBrains Account or an activation code;
 *    the data must be signed by a certificate that chains to a JetBrains root, and name the same license id.
 *  - `stamp:<machine>:<time>:<machine>:<algorithm>:<signature>:<certificate>[:<intermediates>]`: a ticket from a
 *    JetBrains Floating License Server; signed, for this machine, and fresh.
 * Anything else (including a missing or made-up stamp) is not a license.
 */
class LicenseVerifier(
    private val roots: List<X509Certificate> = JETBRAINS_ROOTS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun isValid(stamp: String?): Boolean = when {
        stamp == null -> false
        stamp.startsWith(KEY_PREFIX) -> isKeyValid(stamp.removePrefix(KEY_PREFIX))
        stamp.startsWith(STAMP_PREFIX) -> isServerStampValid(stamp.removePrefix(STAMP_PREFIX))
        else -> false
    }

    private fun isKeyValid(key: String): Boolean {
        val parts = key.split("-")
        if (parts.size != 4) return false
        val (licenseId, licenseData, signature, certificate) = parts
        return runCatching {
            val base64 = Base64.getMimeDecoder()
            // No expiry check on the signing certificate: a key can also be a perpetual fallback license.
            val cert = trustedCertificate(base64.decode(certificate), emptyList(), checkValidityNow = false)
            val data = base64.decode(licenseData)
            val sig = Signature.getInstance("SHA1withRSA").apply { initVerify(cert); update(data) }
            sig.verify(base64.decode(signature)) && "\"licenseId\":\"$licenseId\"" in String(data, Charsets.UTF_8)
        }.getOrDefault(false)
    }

    private fun isServerStampValid(stamp: String): Boolean = runCatching {
        val parts = stamp.split(":")
        val base64 = Base64.getMimeDecoder()
        val expectedMachineId = parts[0]
        val timestamp = parts[1].toLong()
        val machineId = parts[2]
        val signature = base64.decode(parts[4])
        val cert = trustedCertificate(base64.decode(parts[5]), parts.drop(6).map { base64.decode(it) }, checkValidityNow = true)
        val sig = Signature.getInstance(parts[3]).apply { initVerify(cert); update("$timestamp:$machineId".toByteArray(Charsets.UTF_8)) }
        sig.verify(signature) && expectedMachineId == machineId && kotlin.math.abs(now() - timestamp) < STAMP_VALIDITY_MS
    }.getOrDefault(false)

    /** The certificate, if it chains to one of [roots]; throws otherwise. */
    private fun trustedCertificate(certBytes: ByteArray, intermediates: List<ByteArray>, checkValidityNow: Boolean): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        val cert = factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        val all = mutableSetOf<Certificate>(cert)
        intermediates.forEach { all += factory.generateCertificate(ByteArrayInputStream(it)) }
        val params = PKIXBuilderParameters(roots.map { TrustAnchor(it, null) }.toSet(), X509CertSelector().apply { certificate = cert }).apply {
            isRevocationEnabled = false
            // Keys are checked at the start of their certificate's validity, so the check doesn't depend on today's date.
            if (!checkValidityNow) date = cert.notBefore else date = java.util.Date(now())
            addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(all)))
        }
        val path = CertPathBuilder.getInstance("PKIX").build(params).certPath
        CertPathValidator.getInstance("PKIX").validate(path, params)
        return cert
    }

    companion object {
        private const val KEY_PREFIX = "key:"
        private const val STAMP_PREFIX = "stamp:"
        private const val STAMP_VALIDITY_MS = 60 * 60 * 1000L

        /** JetBrains' public roots for license keys ("JetProfile CA") and license servers ("License Servers CA"). */
        val JETBRAINS_ROOTS: List<X509Certificate> by lazy {
            val factory = CertificateFactory.getInstance("X.509")
            ROOTS_BASE64.map { factory.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(it))) as X509Certificate }
        }

        private val ROOTS_BASE64 = listOf(
            "MIIFOzCCAyOgAwIBAgIJANJssYOyg3nhMA0GCSqGSIb3DQEBCwUAMBgxFjAUBgNVBAMMDUpldFByb2ZpbGUgQ0EwHhcNMTUxMDAyMTEwMDU2WhcNNDUxMDI0MTEwMDU2WjAYMRYwFAYDVQQDDA1KZXRQcm9maWxlIENBMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA0tQuEA8784NabB1+T2XBhpB+2P1qjewHiSajAV8dfIeWJOYGy+ShXiuedj8rL8VCdU+yH7Ux/6IvTcT3nwM/E/3rjJIgLnbZNerFm15Eez+XpWBlm5fDBJhEGhPc89Y31GpTzW0vCLmhJ44XwvYPntWxYISUrqeR3zoUQrCEp1C6mXNXEpqIGIVbJ6JVa/YI+pwbfuP51o0ZtF2rzvgfPzKtkpYQ7m7KgA8g8ktRXyNrz8boiwg7RRPeqs4uL/RK8d2KLpgLqcAB9WDpcEQzPWegbDrFO1F3z4UVNH6hrMfOLGVAxoiQhNFhZj6RumBXlPS0rmCOCkUkWrDr3l6Z3spUVgoeea+QdX682j6t7JnakaOwjzwY777SrZoi9mFFpLVhfb4haq4IWyKSHR3/0BlWXgcgI6w6LXm+V+ZgLVDON52FLcxnfftaBJz2yclEwBohq38rYEpb+28+JBvHJYqcZRaldHYLjjmb8XXvf2MyFeXrSopYkdzCvzmiEJAewrEbPUaTllogUQmnv7Rv9sZ9jfdJ/cEn8e7GSGjHIbnjV2ZMQ9vTpWjvsT/cqatbxzdBo/iEg5i9yohOC9aBfpIHPXFw+fEj7VLvktxZY6qThYXRRus1WErPgxDzVpNp+4gXovAYOxsZak5oTV74ynv1aQ93HSndGkKUE/qA/JECAwEAAaOBhzCBhDAdBgNVHQ4EFgQUo562SGdCEjZBvW3gubSgUouX8bMwSAYDVR0jBEEwP4AUo562SGdCEjZBvW3gubSgUouX8bOhHKQaMBgxFjAUBgNVBAMMDUpldFByb2ZpbGUgQ0GCCQDSbLGDsoN54TAMBgNVHRMEBTADAQH/MAsGA1UdDwQEAwIBBjANBgkqhkiG9w0BAQsFAAOCAgEAjrPAZ4xC7sNiSSqh69s3KJD3Ti4etaxcrSnD7r9rJYpKBMviCKZRKFbLv+iaF5JK5QWuWdlgA37ol7mLeoF7aIA9b60Ag2OpgRICRG79QY7ouLviF/yRMqm6yno7NYkGLd61e5Huu+BfT459MWG9RVkG/DY0sGfkyTHJS5xrjBV6hjLG0lf3orwqOlqSNRmhvn9sMzwAP3ILLM5VJC5jNF1zAk0jrqKz64vuA8PLJZlLS9TZJIYwdesCGfnN2AETvzf3qxLcGTF038zKOHUMnjZuFW1ba/12fDK5GJ4i5y+nfDWVZVUDYOPUixEZ1cwzmf9Tx3hR8tRjMWQmHixcNC8XEkVfztID5XeHtDeQ+uPkX+jTDXbRb+77BP6n41briXhm57AwUI3TqqJFvoiFyx5JvVWG3ZqlVaeU/U9e0gxn8qyR+ZA3BGbtUSDDs8LDnE67URzK+L+q0F2BC758lSPNB2qsJeQ63bYyzf0du3wB/gb2+xJijAvscU3KgNpkxfGklvJD/oDUIqZQAnNcHe7QEf8iG2WqaMJIyXZlW3me0rn+cgvxHPt6N4EBh5GgNZR4l0eaFEV+fxVsydOQYo1RIyFMXtafFBqQl6DDxujlFeU3FZ+Bcp12t7dlM4E0/sS1XdL47CfGVj4Bp+/VbF862HmkAbd7shs7sDQkHbU=",
            "MIIFTDCCAzSgAwIBAgIJAMCrW9HV+hjZMA0GCSqGSIb3DQEBCwUAMB0xGzAZBgNVBAMMEkxpY2Vuc2UgU2VydmVycyBDQTAgFw0xNjEwMTIxNDMwNTRaGA8yMTE2MTIyNzE0MzA1NFowHTEbMBkGA1UEAwwSTGljZW5zZSBTZXJ2ZXJzIENBMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoT7LvHj3JKK2pgc5f02z+xEiJDcvlBi6fIwrg/504UaMx3xWXAE5CEPelFty+QPRJnTNnSxqKQQmg2s/5tMJpL9lzGwXaV7arrcsEDbzV4el5mIXUnk77Bm/QVv48s63iQqUjVmvjQt9SWG2J7+h6X3ICRvF1sQByeat/cO7tkpz1aXXbvbAws7/3dXLTgAZTAmBXWNEZHVUTcwSg2IziYxL8HRFOH0+GMBhHqa0ySmF1UTnTV4atIXrvjpABsoUvGxw+qOO2qnwe6ENEFWFz1a7pryVOHXgP+4JyPkI1hdAhAqT2kOKbTHvlXDMUaxAPlriOVw+vaIjIVlNHpBGhqTj1aqfJpLjqfDFcuqQSI4O1W5tVPRNFrjr74nDwLDZnOF+oSy4E1/WhL85FfP3IeQAIHdswNMJy+RdkPZCfXzSUhBKRtiM+yjpIn5RBY+8z+9yeGocoxPf7l0or3YF4GUpud202zgyY3sJqEsZksB750M0hx+vMMC9GD5nkzm9BykJS25hZOSsRNhX9InPWYYIi6mFm8QA2Dnv8wxAwt2tDNgqa0v/N8OxHglPcK/VO9kXrUBtwCIfZigO//N3hqzfRNbTv/ZOk9lArqGtcu1hSa78U4fuu7lIHi+u5rgXbB6HMVT3g5GQ1L9xxT1xad76k2EGEi3F9B+tSrvru70CAwEAAaOBjDCBiTAdBgNVHQ4EFgQUpsRiEz+uvh6TsQqurtwXMd4J8VEwTQYDVR0jBEYwRIAUpsRiEz+uvh6TsQqurtwXMd4J8VGhIaQfMB0xGzAZBgNVBAMMEkxpY2Vuc2UgU2VydmVycyBDQYIJAMCrW9HV+hjZMAwGA1UdEwQFMAMBAf8wCwYDVR0PBAQDAgEGMA0GCSqGSIb3DQEBCwUAA4ICAQCJ9+GQWvBS3zsgPB+1PCVcoG6FY87N6nb3ZgNTHrUMNYdo7FDeol2DSB4wh/6rsP9Z4FqVlpGkckB+QHCvqU+drYPe6QWHIb1kE8ftTnwapj/ZaBtF80NWUfYBER/9c6To5moW63O7q6cmKgaGk6zvSt2IhwNdTX0Q5cib9ytE4XROeVwPUn6RdU/+AVqSOspSMc1WQxkPVGRF7HPCoGhdvqebbYhpahiMWfClEuv1I37gJaRtsoNpx3f/jleoC/vDvXjAznfO497YTf/GgSM2LCnVtpPQQ2vQbOfTjaBYO2MpibQlYpbkbjkd5ZcO5U5PGrQpPFrWcylz7eUC3c05UVeygGIthsA/0hMCioYz4UjWTgi9NQLbhVkfmVQ5lCVxTotyBzoubh3FBz+wq2QtiElsBrCMR7UwmIu79UYzmLGt3/gBdHxaImrT9SQ8uqzP5eit54LlGbvGekVdAL5lDFwPcSB1IKauXZvi1DwFGPeemcSAndy+Uoqw5XGRqE6jBxS7XVI7/4BSMDDRBz1ua+JMGZXS8yyYT+7HdsybfsZLvkVmc9zVSDI7/MjVPdk6h0sLn+vuPC1bIi5edoNyPdiG2uPH5eDO6INcisyPpLS4yFKliaO4Jjap7yzLU9pbItoWgCAYa2NpxuxHJ0tB7tlDFnvaRnQukqSG+VqNWg=="
        )
    }
}
