package license

import com.intellij.ui.LicensingFacade
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
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
import java.util.*
import java.util.Base64.getMimeDecoder

object IntellijCheckLicense {
    /**
     * PRODUCT_CODE must be the same specified in plugin.xml inside the <productCode> tag
    </productCode> */
    private const val PRODUCT_CODE = "XXXXXX"
    private const val KEY_PREFIX = "key:"
    private const val STAMP_PREFIX = "stamp:"
    private const val EVAL_PREFIX = "eval:"
    /**
     * Public root certificates needed to verify JetBrains-signed licenses
     */
    private val ROOT_CERTIFICATES = arrayOf(
        "-----BEGIN CERTIFICATE-----\n" +
            "-----END CERTIFICATE-----\n",
        "-----BEGIN CERTIFICATE-----\n" +
            "-----END CERTIFICATE-----"
    )
    private const val SECOND: Long = 1000
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val TIMESTAMP_VALIDITY_PERIOD_MS =
        1 * HOUR // configure period that suits your needs better// licensed via ticket obtained from JetBrains Floating License Server

    // the license is obtained via JetBrainsAccount or entered as an activation code
    val isLicensed: Boolean
        get() {
            val facade = LicensingFacade.getInstance() ?: return false
            val cstamp = facade.getConfirmationStamp(PRODUCT_CODE) ?: return false
            if (cstamp.startsWith(KEY_PREFIX)) { // the license is obtained via JetBrainsAccount or entered as an activation code
                return isKeyValid(cstamp.substring(KEY_PREFIX.length))
            }
            if (cstamp.startsWith(STAMP_PREFIX)) { // licensed via ticket obtained from JetBrains Floating License Server
                return isLicenseServerStampValid(cstamp.substring(STAMP_PREFIX.length))
            }
            return if (cstamp.startsWith(EVAL_PREFIX)) {
                isEvaluationValid(cstamp.substring(EVAL_PREFIX.length))
            } else false
        }

    private fun isEvaluationValid(expirationTime: String): Boolean {
        return try {
            val now = Date()
            val expiration = Date(expirationTime.toLong())
            now.before(expiration)
        } catch (e: NumberFormatException) {
            false
        }
    }

    private fun isKeyValid(key: String): Boolean {
        val licenseParts = key.split("-").toTypedArray()
        if (licenseParts.size != 4) {
            return false // invalid format
        }
        val licenseId = licenseParts[0]
        val licensePartBase64 = licenseParts[1]
        val signatureBase64 = licenseParts[2]
        val certBase64 = licenseParts[3]
        try {
            val sig = Signature.getInstance("SHA1withRSA")
            // the last parameter of 'createCertificate()' set to 'false' switches off certificate expiration checks.
            // This might be the case if the key is at the same time a perpetual fallback license for older IDE versions.
            // Here it is only important that the key was signed with an authentic JetBrains certificate.
            sig.initVerify(
                createCertificate(
                    Base64.getMimeDecoder().decode(certBase64.toByteArray(StandardCharsets.UTF_8)),
                    emptySet(),
                    false
                )
            )
            val licenseBytes = Base64.getMimeDecoder()
                .decode(licensePartBase64.toByteArray(StandardCharsets.UTF_8))
            sig.update(licenseBytes)
            if (!sig.verify(Base64.getMimeDecoder().decode(signatureBase64.toByteArray(StandardCharsets.UTF_8)))) {
                return false
            }
            // Optional additional check: the licenseId corresponds to the licenseId encoded in the signed license data
            // The following is a 'least-effort' code. It would be more accurate to parse json and then find there the value of the attribute "licenseId"
            val licenseData = String(licenseBytes, Charset.forName("UTF-8"))
            return licenseData.contains("\"licenseId\":\"$licenseId\"")
        } catch (ignored: Throwable) {
        }
        return false
    }

    private fun isLicenseServerStampValid(serverStamp: String): Boolean {
        try {
            val parts = serverStamp.split(":".toRegex()).toTypedArray()
            val base64 = getMimeDecoder()
            val expectedMachineId = parts[0]
            val timeStamp = parts[1].toLong()
            val machineId = parts[2]
            val signatureType = parts[3]
            val signatureBytes = base64.decode(parts[4].toByteArray(StandardCharsets.UTF_8))
            val certBytes = base64.decode(parts[5].toByteArray(StandardCharsets.UTF_8))
            val intermediate: MutableCollection<ByteArray> =
                ArrayList()
            for (idx in 6 until parts.size) {
                intermediate.add(base64.decode(parts[idx].toByteArray(StandardCharsets.UTF_8)))
            }
            val sig = Signature.getInstance(signatureType)
            // the last parameter of 'createCertificate()' set to 'true' causes the certificate to be checked for
            // expiration. Expired certificates from a license server cannot be trusted
            sig.initVerify(createCertificate(certBytes, intermediate, true))
            sig.update("$timeStamp:$machineId".toByteArray(StandardCharsets.UTF_8))
            if (sig.verify(signatureBytes)) {
                // machineId must match the machineId from the server reply and
                // server reply should be relatively 'fresh'
                return expectedMachineId == machineId && Math.abs(System.currentTimeMillis() - timeStamp) < TIMESTAMP_VALIDITY_PERIOD_MS
            }
        } catch (ignored: Throwable) { // consider serverStamp invalid
        }
        return false
    }

    @Throws(Exception::class)
    private fun createCertificate(
        certBytes: ByteArray,
        intermediateCertsBytes: Collection<ByteArray>,
        checkValidityAtCurrentDate: Boolean
    ): X509Certificate {
        val x509factory =
            CertificateFactory.getInstance("X.509")
        val cert =
            x509factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        val allCerts: MutableCollection<Certificate?> =
            HashSet()
        allCerts.add(cert)
        for (bytes in intermediateCertsBytes) {
            allCerts.add(x509factory.generateCertificate(ByteArrayInputStream(bytes)))
        }
        try { // Create the selector that specifies the starting certificate
            val selector = X509CertSelector()
            selector.certificate = cert
            // Configure the PKIX certificate builder algorithm parameters
            val trustAchors: MutableSet<TrustAnchor> = HashSet()
            for (rc in ROOT_CERTIFICATES) {
                trustAchors.add(
                    TrustAnchor(
                        x509factory.generateCertificate(ByteArrayInputStream(rc.toByteArray(StandardCharsets.UTF_8))) as X509Certificate,
                        null
                    )
                )
            }
            val pkixParams = PKIXBuilderParameters(trustAchors, selector)
            pkixParams.isRevocationEnabled = false
            if (!checkValidityAtCurrentDate) { // deliberately check validity on the start date of cert validity period, so that we do not depend on
                // the actual moment when the check is performed
                pkixParams.date = cert.notBefore
            }
            pkixParams.addCertStore(
                CertStore.getInstance("Collection", CollectionCertStoreParameters(allCerts))
            )
            // Build and verify the certification chain
            val path = CertPathBuilder.getInstance("PKIX").build(pkixParams).certPath
            if (path != null) {
                CertPathValidator.getInstance("PKIX").validate(path, pkixParams)
                return cert
            }
        } catch (e: Exception) { // debug the reason here
        }
        throw Exception("Certificate used to sign the license is not signed by JetBrains root certificate")
    }
}