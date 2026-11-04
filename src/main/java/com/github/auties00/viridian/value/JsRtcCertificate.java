package com.github.auties00.viridian.value;

import java.util.Objects;

/// An `RTCCertificate`: a WebRTC certificate and its private key.
///
/// @param privateKey  the private key, PEM-encoded
/// @param certificate the certificate, PEM-encoded
public record JsRtcCertificate(String privateKey, String certificate) implements JsHostObject {
    /// Creates a certificate.
    public JsRtcCertificate {
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(certificate, "certificate");
    }

    @Override
    public String toString() {
        return "RTCCertificate";
    }
}
