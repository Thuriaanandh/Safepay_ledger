import java.io.FileInputStream;
import java.io.FileWriter;
import java.security.Key;
import java.security.KeyStore;
import java.util.Base64;

public class ExportKey {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: java ExportKey <p12Path> <password> <alias> <outKeyPath>");
            System.exit(1);
        }
        String p12Path = args[0];
        String password = args[1];
        String alias = args[2];
        String outKeyPath = args[3];

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(p12Path)) {
            ks.load(fis, password.toCharArray());
        }
        Key key = ks.getKey(alias, password.toCharArray());
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
        try (FileWriter fw = new FileWriter(outKeyPath)) {
            fw.write(pem);
        }
        System.out.println("Exported key to " + outKeyPath);
    }
}
