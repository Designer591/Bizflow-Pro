package com.erolitechnologies.bizflow.licensing;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/**
 * SERVER-SIDE ONLY. Lives in the licensing service, never shipped in the desktop installer.
 * The desktop app only ever holds the public key (see LicenceVerifier).
 *
 * Token format: base64url(payload) + "." + base64url(Ed25519 signature over payload bytes).
 */
public final class LicenceSigner {
    private final PrivateKey privateKey;

    public LicenceSigner(PrivateKey privateKey) { this.privateKey = privateKey; }

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available in this JVM", e);
        }
    }

    public String issue(LicenceData data) {
        try {
            byte[] payload = data.canonicalBytes();
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(privateKey);
            s.update(payload);
            byte[] sig = s.sign();
            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            return enc.encodeToString(payload) + "." + enc.encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign licence", e);
        }
    }
}
