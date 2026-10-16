import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.TreeSet;

/**
 * Offline MechMotor activation-key issuer.
 * Keep the Ed25519 private key outside the application repository and restrict access to it.
 */
public final class IssueLicense {
    private static final Set<String> ALLOWED = Set.of("INVENTORY", "CSV", "REPORTS", "SALES", "PURCHASING");

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) {
            System.err.println("Usage: java IssueLicense.java <private-key.pem> <license-id> <customer> <yyyy-MM-dd|never> [FEATURE,FEATURE]");
            System.exit(2);
        }
        String licenseId = args[1].strip().toUpperCase(java.util.Locale.ROOT);
        if (!licenseId.matches("[A-Z0-9][A-Z0-9._-]{2,63}")) throw new IllegalArgumentException("Invalid license ID.");
        String customer = args[2].strip();
        if (customer.isEmpty() || customer.length() > 100 || customer.contains("\n") || customer.contains("\r")) {
            throw new IllegalArgumentException("Customer must be 1-100 characters on one line.");
        }
        LocalDate issued = LocalDate.now(Clock.systemUTC());
        LocalDate expires = args[3].equalsIgnoreCase("never") ? LocalDate.MAX : LocalDate.parse(args[3]);
        if (expires.isBefore(issued)) throw new IllegalArgumentException("Expiry date cannot be in the past.");

        TreeSet<String> features = new TreeSet<>();
        String featureArgument = args.length == 5 ? args[4] : "INVENTORY,CSV,REPORTS,SALES,PURCHASING";
        Arrays.stream(featureArgument.split(",")).map(String::strip).map(s -> s.toUpperCase(java.util.Locale.ROOT))
                .filter(s -> !s.isEmpty()).forEach(features::add);
        if (!features.contains("INVENTORY") || !ALLOWED.containsAll(features)) {
            throw new IllegalArgumentException("Features must be from " + ALLOWED + " and include INVENTORY.");
        }

        String payload = String.join("\n", "MECHMOTOR-LICENSE-V1", licenseId, customer,
                Long.toString(issued.toEpochDay()), Long.toString(expires.toEpochDay()), String.join(",", features));
        PrivateKey privateKey = readPrivateKey(Path.of(args[0]));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        System.out.println("MM1." + encodedPayload + "." + encodedSignature);
    }

    private static PrivateKey readPrivateKey(Path path) throws Exception {
        String pem = Files.readString(path, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
