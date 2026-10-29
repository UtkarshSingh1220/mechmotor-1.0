package com.mechmotor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class LicenseServiceTest {
    @TempDir Path tempDir;
    private KeyPair keyPair;
    private LicenseService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        keyPair = generator.generateKeyPair();
        service = new LicenseService(clock, tempDir.resolve("license").resolve("activation.key"), keyPair.getPublic());
    }

    @Test
    void activatesValidSignedKeyAndPersistsIt() throws Exception {
        String token = token("MM-TEST-001", "Test Shop", LocalDate.of(2026, 10, 15),
                LocalDate.of(2027, 10, 16), "CSV,INVENTORY,REPORTS");
        LicenseStatus status = service.activate(token);

        assertTrue(status.active());
        assertEquals("MM-TEST-001", status.licenseId());
        assertEquals("Test Shop", status.customer());
        assertTrue(status.features().contains("INVENTORY"));
        assertEquals(token, Files.readString(service.activationFile(), StandardCharsets.UTF_8).strip());
        assertEquals(token, service.status().activationKey());
    }

    @Test
    void serviceBoundaryRejectsMissingOrUnlicensedFeatures() throws Exception {
        assertThrows(IllegalStateException.class, () -> service.requireFeature("INVENTORY"));
        service.activate(token("MM-TEST-GATE", "Test Shop", LocalDate.of(2026, 10, 15),
                LocalDate.of(2027, 10, 16), "INVENTORY,CSV"));
        assertDoesNotThrow(() -> service.requireFeature("inventory"));
        assertDoesNotThrow(() -> service.requireFeature("CSV"));
        assertThrows(IllegalStateException.class, () -> service.requireFeature("SALES"));
        assertThrows(IllegalArgumentException.class, () -> service.requireFeature("UNKNOWN"));
    }

    @Test
    void rejectsTamperedKeyWithoutReplacingCurrentActivation() throws Exception {
        String valid = token("MM-TEST-002", "Test Shop", LocalDate.of(2026, 10, 15),
                LocalDate.of(2027, 10, 16), "INVENTORY");
        service.activate(valid);
        String[] parts = valid.split("\\.", -1);
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? "B" : "A") + parts[2].substring(1);
        String tampered = String.join(".", parts);

        assertThrows(IllegalArgumentException.class, () -> service.activate(tampered));
        assertEquals(valid, service.status().activationKey());
        assertTrue(service.status().active());
    }

    @Test
    void rejectsExpiredAndFutureDatedKeys() throws Exception {
        String expired = token("MM-TEST-003", "Test Shop", LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 10, 15), "INVENTORY");
        String future = token("MM-TEST-004", "Test Shop", LocalDate.of(2026, 10, 20),
                LocalDate.of(2027, 10, 16), "INVENTORY");
        assertThrows(IllegalArgumentException.class, () -> service.activate(expired));
        assertThrows(IllegalArgumentException.class, () -> service.activate(future));
        assertFalse(service.status().active());
    }

    @Test
    void requiresInventoryFeatureAndReportsMissingActivation() throws Exception {
        assertFalse(service.status().active());
        assertTrue(service.status().message().contains("No activation key"));
        String reportsOnly = token("MM-TEST-005", "Test Shop", LocalDate.of(2026, 10, 15),
                LocalDate.of(2027, 10, 16), "REPORTS");
        assertThrows(IllegalArgumentException.class, () -> service.activate(reportsOnly));
    }

    @Test
    void embeddedOwnerVerificationKeyIsValidEd25519PublicKey() throws Exception {
        var field = LicenseService.class.getDeclaredField("OWNER_PUBLIC_KEY_DER_BASE64");
        field.setAccessible(true);
        byte[] der = Base64.getDecoder().decode((String)field.get(null));
        assertEquals("Ed25519",
                java.security.KeyFactory.getInstance("Ed25519")
                        .generatePublic(new java.security.spec.X509EncodedKeySpec(der)).getAlgorithm());
    }

    private String token(String id, String customer, LocalDate issued, LocalDate expiry, String features) throws Exception {
        String payload = String.join("\n", "MECHMOTOR-LICENSE-V1", id, customer,
                Long.toString(issued.toEpochDay()), Long.toString(expiry.toEpochDay()), features);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        signer.update(bytes);
        return "MM1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }
}
