# Offline activation-key issuer

This utility uses only the Java 21 standard library and the Ed25519 private key supplied by the product owner.

## Security

- Keep the private key outside Git and outside the application installation directory.
- Store it encrypted with access limited to the product owner; keep a secure backup.
- Never send the private key to customers. Customers receive only the signed activation token.
- The public verification key is embedded in `LicenseService`; changing signing keys requires a controlled application release that updates the public key.
- Tokens are not machine-bound in this version. A valid token can be copied to another machine until expiry.

## Usage

From the repository root, using Java 21:

```sh
java tools/license-issuer/IssueLicense.java /secure/path/mechmotor-license-private.pem MM-CUSTOMER-001 "Customer Name" 2027-12-31 INVENTORY,CSV,REPORTS
```

Arguments:

1. Path to an Ed25519 PKCS#8 PEM private key.
2. License ID (3–64 characters; uppercase letters, digits, dot, underscore and hyphen).
3. Customer name (one line, maximum 100 characters).
4. Expiry date as `YYYY-MM-DD`, or `never` for a perpetual license.
5. Optional comma-separated features. Allowed: `INVENTORY`, `CSV`, `REPORTS`, `SALES`, `PURCHASING`. `INVENTORY` is required.

The tool prints one `MM1` token. Deliver that token to the customer through a secure channel. The customer pastes it into **Activation…** in the desktop application.

The private key is intentionally not present in this directory or any other repository path.
