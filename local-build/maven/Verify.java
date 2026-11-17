import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
public class Verify {
    public static void main(String[] a) throws Exception {
        ApkVerifier.Result r = new ApkVerifier.Builder(new File(a[0])).setMinCheckedPlatformVersion(30).build().verify();
        System.out.println(a[0] + " verifica=" + r.isVerified() + " v1=" + r.isVerifiedUsingV1Scheme() + " v2=" + r.isVerifiedUsingV2Scheme());
        for (Object e : r.getErrors()) System.out.println("  error: " + e);
        byte[] d = MessageDigest.getInstance("SHA-256").digest(r.getSignerCertificates().get(0).getEncoded());
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02X:", b));
        System.out.println("  SHA256 " + sb.substring(0, sb.length() - 1));
    }
}
