import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.LinkedHashSet;

/** Verifies APK signatures and pins every platform-specific signer to the shared release certificate. */
public class VerifyApkSigning {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Usage: VerifyApkSigning.java <certificate-sha256> <apk>...");
        }
        for (int i = 1; i < args.length; i++) {
            var apk = new File(args[i]);
            var result = new ApkVerifier.Builder(apk).build().verify();
            if (!result.isVerified() || result.getSignerCertificates().size() != 1) {
                throw failure("Invalid APK signature or signer count: " + apk
                        + "; " + result.getErrors());
            }
            var certificates = new LinkedHashSet<X509Certificate>(result.getSignerCertificates());
            // v3.1 can expose different signing certificates for different Android versions.
            for (var signer : result.getV3SchemeSigners()) certificates.add(signer.getCertificate());
            for (var signer : result.getV31SchemeSigners()) certificates.add(signer.getCertificate());
            for (var certificate : certificates) {
                var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(certificate.getEncoded()));
                if (!actual.equals(args[0])) {
                    throw failure("APK certificate mismatch: expected " + args[0]
                            + ", got " + actual + " (" + apk.getName() + ")");
                }
                System.out.println("::notice title=Verified APK certificate::" + apk.getName()
                        + " SHA-256 " + actual + " " + certificate.getSubjectX500Principal());
            }
        }
    }

    private static IllegalStateException failure(String message) {
        System.out.println("::error::" + message.replace("%", "%25")
                .replace("\r", "%0D").replace("\n", "%0A"));
        return new IllegalStateException(message);
    }
}
