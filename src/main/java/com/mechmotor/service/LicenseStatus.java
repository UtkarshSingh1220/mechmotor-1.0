package com.mechmotor.service;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;

public record LicenseStatus(
        boolean active,
        String message,
        String licenseId,
        String customer,
        LocalDate expiresOn,
        Set<String> features,
        String activationKey,
        Path keyFile) { }
