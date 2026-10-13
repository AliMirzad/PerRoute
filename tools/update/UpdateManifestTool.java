import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

public final class UpdateManifestTool {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("generate-key")) {
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Path privateFile = Path.of(args[1]).toAbsolutePath().normalize();
            Files.createDirectories(privateFile.getParent());
            Files.write(privateFile, pair.getPrivate().getEncoded());
            System.out.println(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            return;
        }
        if (args.length == 6 && args[0].equals("sign")) {
            String version = args[1];
            Path installer = Path.of(args[2]).toAbsolutePath().normalize();
            String url = args[3];
            Path privateFile = Path.of(args[4]).toAbsolutePath().normalize();
            Path manifest = Path.of(args[5]).toAbsolutePath().normalize();
            long size = Files.size(installer);
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(installer)));
            String canonical = version + "\n" + url + "\n" + sha256 + "\n" + size;
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile)));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signer.sign());
            String json = "{\n" +
                    "  \"version\": \"" + escape(version) + "\",\n" +
                    "  \"url\": \"" + escape(url) + "\",\n" +
                    "  \"sha256\": \"" + sha256 + "\",\n" +
                    "  \"size\": " + size + ",\n" +
                    "  \"signature\": \"" + signature + "\"\n" +
                    "}\n";
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, json, StandardCharsets.UTF_8);
            System.out.println(manifest);
            return;
        }
        System.err.println("Usage:\n  generate-key <private.pk8>\n  sign <version> <installer> <https-url> <private.pk8> <manifest.json>");
        System.exit(2);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
