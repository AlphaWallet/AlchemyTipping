package tapi.api.crypto;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import tapi.api.crypto.core.ASNEncodable;
import tapi.api.crypto.core.SignatureUtility;
import tapi.api.crypto.core.Validateable;
import tapi.api.crypto.core.Verifiable;

public class SignedNFTAttestation implements ASNEncodable, Verifiable, Validateable {
    private final NFTAttestation att;
    private final byte[] signature;
    private final AsymmetricKeyParameter attestationVerificationKey;

    public SignedNFTAttestation(NFTAttestation att, AsymmetricCipherKeyPair subjectSigningKey) {
        this.att = att;
        this.signature = SignatureUtility.signPersonalMsgWithEthereum(att.getDerEncoding(), subjectSigningKey.getPrivate());
        this.attestationVerificationKey = subjectSigningKey.getPublic();
        if (!verify()) {
            throw new IllegalArgumentException("The signature is not valid");
        }
    }

    public SignedNFTAttestation(NFTAttestation att, AsymmetricKeyParameter subjectPublicKey, byte[] signature) {
        this.att = att;
        this.signature = signature;
        this.attestationVerificationKey = subjectPublicKey;
        if (!verify()) {
            throw new IllegalArgumentException("The signature is not valid");
        }
    }

    public NFTAttestation getUnsignedAttestation() {
        return att;
    }

    public byte[] getSignature() {
        return signature;
    }

    /**
     * Returns the public key of the attestation signer
     */
    public AsymmetricKeyParameter getAttestationVerificationKey() { return attestationVerificationKey; }

    @Override
    public byte[] getDerEncoding() {
        return constructSignedAttestation(this.att, this.signature);
    }

    static byte[] constructSignedAttestation(NFTAttestation unsignedAtt, byte[] signature) {
        try {
            byte[] rawAtt = unsignedAtt.getDerEncoding();
            ASN1EncodableVector res = new ASN1EncodableVector();
            res.add(ASN1Primitive.fromByteArray(rawAtt));
            res.add(SignatureUtility.EC_PUBLIC_KEY_IDENTIFIER);
            res.add(new DERBitString(signature));
            return new DERSequence(res).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean checkValidity() {
        return getUnsignedAttestation().checkValidity();
    }

    @Override
    public boolean verify() {
        return SignatureUtility.verifyPersonalEthereumSignature(att.getDerEncoding(), signature, attestationVerificationKey);
    }
}