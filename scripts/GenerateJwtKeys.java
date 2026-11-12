import java.io.FileWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

public class GenerateJwtKeys {
    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "infra/jwt";
        new java.io.File(outDir).mkdirs();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Write Private Key (PKCS#8)
        String privB64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(kp.getPrivate().getEncoded());
        String privPem = "-----BEGIN PRIVATE KEY-----\n" + privB64 + "\n-----END PRIVATE KEY-----\n";
        try (FileWriter fw = new FileWriter(outDir + "/jwt-private.pem")) {
            fw.write(privPem);
        }

        // Write Public Key (X.509 SubjectPublicKeyInfo)
        String pubB64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(kp.getPublic().getEncoded());
        String pubPem = "-----BEGIN PUBLIC KEY-----\n" + pubB64 + "\n-----END PUBLIC KEY-----\n";
        try (FileWriter fw = new FileWriter(outDir + "/jwt-public.pem")) {
            fw.write(pubPem);
        }

        System.out.println("Generated RS256 JWT keys in " + outDir);
    }
}
