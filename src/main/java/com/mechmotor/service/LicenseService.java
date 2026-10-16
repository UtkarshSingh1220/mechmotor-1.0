package com.mechmotor.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Verifies signed Ed25519 activation tokens. No signing secret is bundled with the application. */
@Service
public class LicenseService {
    private static final String TOKEN_PREFIX = "MM1";
    private static final String PAYLOAD_VERSION = "MECHMOTOR-LICENSE-V1";
    private static final String PUBLIC_KEY_DER_BASE64 = "MCowBQYDK2VwAyEAn+/Vh3xLSucGnJbvryRM2U12QJnOP6Dg4CbibrAIKtc=";
    private static final Pattern LICENSE_ID = Pattern.compile("[A-Z0-9][A-Z0-9._-]{2,63}");
    private static final Set<String> ALLOWED_FEATURES =
            Set.of("INVENTORY", "CSV", "REPORTS", "SALES", "PURCHASING");

    private final Clock clock;
    private final Path activationFile;
    private final PublicKey verificationKey;

    public LicenseService() {
        this(Clock.systemUTC(), defaultActivationFile(), loadEmbeddedPublicKey());
    }

    public LicenseService(Clock clock, Path activationFile, PublicKey verificationKey) {
        this.clock = clock;
        this.activationFile = activationFile.toAbsolutePath().normalize();
        this.verificationKey = verificationKey;
    }

    public LicenseStatus status() {
        if (!Files.isRegularFile(activationFile)) {
            return new LicenseStatus(false, "No activation key installed.", null, null, null,
                    Set.of(), "", activationFile);
        }
        try {
            String token = Files.readString(activationFile, StandardCharsets.UTF_8).strip();
            return verifyToken(token, activationFile);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "Activation key is invalid." : ex.getMessage();
            try {
                String raw = Files.readString(activationFile, StandardCharsets.UTF_8).strip();
                return new LicenseStatus(false, message, null, null, null, Set.of(), raw, activationFile);
            } catch (IOException ignored) {
                return new LicenseStatus(false, message, null, null, null, Set.of(), "", activationFile);
            }
        }
    }

    public LicenseStatus activate(String token) throws IOException {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Paste an activation key.");
        String normalized = token.strip();
        // Verify before touching the currently installed key.
        verifyToken(normalized, activationFile);
        Path parent = activationFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".activation-", ".tmp");
        try {
            Files.writeString(temporary, normalized + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, activationFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, activationFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return status();
    }


    /** Enforce signed feature access at the service boundary, not only in UI controls. */
    public void requireFeature(String rawFeature) {
        String feature = rawFeature == null ? "" : rawFeature.strip().toUpperCase(Locale.ROOT);
        if (!ALLOWED_FEATURES.contains(feature)) {
            throw new IllegalArgumentException("Unknown MechMotor feature gate: " + feature);
        }
        LicenseStatus current = status();
        if (!current.active()) {
            throw new IllegalStateException("A valid activation key is required: " + current.message());
        }
        if (!current.features().contains(feature)) {
            throw new IllegalStateException("The installed activation key does not include " + feature + ".");
        }
    }

    public Path activationFile() { return activationFile; }

    private LicenseStatus verifyToken(String token, Path path) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !TOKEN_PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("Activation key format is invalid.");
        }
        byte[] payloadBytes;
        byte[] signatureBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            signatureBytes = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Activation key encoding is invalid.", ex);
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(verificationKey);
            verifier.update(payloadBytes);
            boolean valid;
            try {
                valid = verifier.verify(signatureBytes);
            } catch (java.security.SignatureException ex) {
                throw new IllegalArgumentException("Activation key signature is not valid.", ex);
            }
            if (!valid) throw new IllegalArgumentException("Activation key signature is not valid.");
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not verify activation signature.", ex);
        }

        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] fields = payload.split("\\n", -1);
        if (fields.length != 6 || !PAYLOAD_VERSION.equals(fields[0])) {
            throw new IllegalArgumentException("Activation key payload is invalid.");
        }
        String licenseId = fields[1];
        String customer = fields[2].strip();
        if (!LICENSE_ID.matcher(licenseId).matches()) throw new IllegalArgumentException("Activation key license ID is invalid.");
        if (customer.isEmpty() || customer.length() > 100 || customer.indexOf(13) >= 0 || customer.indexOf(10) >= 0) {
            throw new IllegalArgumentException("Activation key customer field is invalid.");
        }
        long issuedDay;
        long expiryDay;
        try {
            issuedDay = Long.parseLong(fields[3]);
            expiryDay = Long.parseLong(fields[4]);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Activation key dates are invalid.", ex);
        }
        LocalDate today = LocalDate.now(clock);
        if (issuedDay > today.plusDays(1).toEpochDay()) {
            throw new IllegalArgumentException("Activation key issue date is in the future.");
        }
        if (expiryDay < issuedDay || expiryDay > LocalDate.MAX.toEpochDay()) {
            throw new IllegalArgumentException("Activation key expiry date is invalid.");
        }
        LocalDate expiresOn = LocalDate.ofEpochDay(expiryDay);
        if (expiresOn.isBefore(today)) throw new IllegalArgumentException("Activation key expired on " + expiresOn + ".");

        Set<String> features = new LinkedHashSet<>();
        Arrays.stream(fields[5].split(",", -1)).map(String::strip).filter(s -> !s.isEmpty()).forEach(features::add);
        if (features.isEmpty() || !ALLOWED_FEATURES.containsAll(features)) {
            throw new IllegalArgumentException("Activation key contains unknown or missing features.");
        }
        if (!features.contains("INVENTORY")) {
            throw new IllegalArgumentException("Activation key does not include the INVENTORY feature.");
        }
        return new LicenseStatus(true, "Activation is valid.", licenseId, customer, expiresOn,
                Set.copyOf(features), token, path);
    }

    private static PublicKey loadEmbeddedPublicKey() {
        try {
            byte[] der = Base64.getDecoder().decode(PUBLIC_KEY_DER_BASE64);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("Embedded activation verification key is invalid.", ex);
        }
    }

    private static Path defaultActivationFile() {
        String override = System.getProperty("mechmotor.licenseFile");
        if (override != null && !override.isBlank()) return Path.of(override);
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".mechmotor")
                : Path.of(localAppData, "MechMotor");
        return base.resolve("license").resolve("activation.key");
    }
}
