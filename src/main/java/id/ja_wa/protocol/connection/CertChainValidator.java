package id.ja_wa.protocol.connection;


import com.google.protobuf.InvalidProtocolBufferException;
import id.ja_wa.domain.model.WaConstants;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.crypto.Curve25519;

public final class CertChainValidator {

    private CertChainValidator() {}

    public static void validate(byte[] expectedServerStatic, byte[] certChainBytes) {
        final Wa.CertChain chain;
        try {
            chain = Wa.CertChain.parseFrom(certChainBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new CertChainException("CertChain proto malformed", e);
        }

        Wa.CertChain.NoiseCertificate leaf = chain.getLeaf();
        Wa.CertChain.NoiseCertificate intermediate = chain.getIntermediate();

        if (!leaf.hasDetails() || !leaf.hasSignature()) {
            throw new CertChainException("invalid noise leaf certificate");
        }
        if (!intermediate.hasDetails() || !intermediate.hasSignature()) {
            throw new CertChainException("invalid noise intermediate certificate");
        }

        final Wa.CertChain.NoiseCertificate.Details intermediateDetails;
        try {
            intermediateDetails = Wa.CertChain.NoiseCertificate.Details.parseFrom(intermediate.getDetails());
        } catch (InvalidProtocolBufferException e) {
            throw new CertChainException("intermediate Details proto malformed", e);
        }

        byte[] intermediateKey = intermediateDetails.getKey().toByteArray();
        if (!Curve25519.verify(intermediateKey, leaf.getDetails().toByteArray(), leaf.getSignature().toByteArray())) {
            throw new CertChainException("noise certificate signature invalid");
        }

        if (!Curve25519.verify(WaConstants.WA_CERT_PUBLIC_KEY,
                intermediate.getDetails().toByteArray(),
                intermediate.getSignature().toByteArray())) {
            throw new CertChainException("noise intermediate certificate signature invalid");
        }

        if (intermediateDetails.getIssuerSerial() != WaConstants.WA_CERT_SERIAL) {
            throw new CertChainException("certification match failed (issuerSerial="
                + intermediateDetails.getIssuerSerial() + ")");
        }

    }

    public static final class CertChainException extends RuntimeException {
        public CertChainException(String message) { super(message); }
        public CertChainException(String message, Throwable cause) { super(message, cause); }
    }
}
