import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Firma un APK (v1 + v2) con un keystore JKS: Sign <keystore> <in> <out>. */
public class Sign {
    public static void main(String[] a) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream in = new FileInputStream(a[0])) {
            ks.load(in, "android".toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey("androiddebugkey", "android".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("androiddebugkey");
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("ANDROIDD", key,
                Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(new File(a[1])).setOutputApk(new File(a[2]))
                .setMinSdkVersion(30).setV1SigningEnabled(false).setV2SigningEnabled(true)
                .build().sign();
        System.out.println("firmado: " + a[2]);
    }
}
