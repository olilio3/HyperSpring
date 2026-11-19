import com.android.apksig.ApkSigner;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * Firma un APK (esquema v2) con un keystore: {@code Sign <keystore> <in> <out>}.
 *
 * <ul>
 * <li>Con la clave de depuración ({@code debug.keystore} de uber-apk-signer) no pide nada: alias
 * {@code androiddebugkey}, contraseña {@code android}.</li>
 * <li>Con la clave privada de las releases: alias en {@code KS_ALIAS} (por defecto
 * {@code hyperspring}, como en build.sh). La contraseña se escribe al firmar, sin que se vea en
 * pantalla; o se pasa en {@code KS_PASS} (no recomendado: queda en el historial). Si la de la
 * clave es distinta de la del almacén, en {@code KEY_PASS}.</li>
 * </ul>
 *
 * Lee almacenes JKS y PKCS12 (los .jks modernos de keytool son PKCS12 por dentro).
 */
public class Sign {
    public static void main(String[] a) throws Exception {
        boolean debug = new File(a[0]).getName().equals("debug.keystore");
        String alias = debug ? "androiddebugkey" : env("KS_ALIAS", "hyperspring");
        char[] storePass = debug ? "android".toCharArray() : password("KS_PASS", "Contraseña del keystore: ");
        String kp = System.getenv("KEY_PASS");
        char[] keyPass = debug ? storePass : (kp != null ? kp.toCharArray() : storePass);

        KeyStore ks = load(a[0], storePass);
        PrivateKey key = (PrivateKey) ks.getKey(alias, keyPass);
        if (key == null) throw new IllegalArgumentException("no hay ninguna clave con el alias «" + alias + "» (cámbialo con KS_ALIAS)");
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("HYPERSPR", key,
                Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(new File(a[1])).setOutputApk(new File(a[2]))
                .setMinSdkVersion(30).setV1SigningEnabled(false).setV2SigningEnabled(true)
                .build().sign();
        System.out.println("firmado: " + a[2]);
    }

    /** Prueba JKS y PKCS12: según con qué versión de keytool se creó, es uno u otro. */
    private static KeyStore load(String path, char[] pass) throws Exception {
        Exception first = null;
        for (String type : new String[]{"PKCS12", "JKS"}) {
            try (FileInputStream in = new FileInputStream(path)) {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(in, pass);
                return ks;
            } catch (Exception e) {
                if (first == null) first = e;
            }
        }
        throw first;
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? def : v;
    }

    /** La contraseña: de la variable si está; si no, se pregunta sin enseñarla. */
    private static char[] password(String var, String prompt) {
        String v = System.getenv(var);
        if (v != null) return v.toCharArray();
        Console c = System.console();
        if (c == null) throw new IllegalStateException("no hay terminal para pedir la contraseña: pásala en " + var);
        return c.readPassword(prompt);
    }
}
