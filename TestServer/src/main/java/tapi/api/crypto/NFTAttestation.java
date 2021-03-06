package tapi.api.crypto;

import org.bouncycastle.asn1.*;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import tapi.api.crypto.core.ASNEncodable;
import tapi.api.crypto.core.SignatureUtility;
import tapi.api.crypto.core.Validateable;
import tapi.api.crypto.ethereum.ERC721Token;

import java.io.IOException;

public class NFTAttestation implements ASNEncodable, Validateable {
    private final SignedIdentifierAttestation att;
    private final DERSequence tokens;

    public NFTAttestation(SignedIdentifierAttestation att, ERC721Token[] nftTokens) {
        this.att = att;
        ASN1EncodableVector asn1 = new ASN1EncodableVector();
        for (ERC721Token nftToken : nftTokens)
        {
            asn1.add(new DERSequence(nftToken.getTokenVector()));
        }

        this.tokens = new DERSequence(asn1);
    }

    public NFTAttestation(byte[] derEncoding, AsymmetricKeyParameter signingPublicKey) throws IOException {
        ASN1InputStream input = new ASN1InputStream(derEncoding);
        ASN1Sequence asn1 = ASN1Sequence.getInstance(input.readObject());

        ASN1Sequence attestationEnc = ASN1Sequence.getInstance(asn1.getObjectAt(0)); //root attestation, should be signed att
        this.att = new SignedIdentifierAttestation(attestationEnc.getEncoded(), signingPublicKey);

        ASN1Sequence tokensEnc = ASN1Sequence.getInstance(asn1.getObjectAt(1));
        this.tokens = DERSequence.convert(tokensEnc);
    }

    @Override
    public byte[] getDerEncoding() {
        try {
            ASN1EncodableVector res = new ASN1EncodableVector();
            res.add(ASN1Primitive.fromByteArray(att.getDerEncoding()));
            res.add(tokens);
            return new DERSequence(res).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] getPreHash() {
        return SignatureUtility.convertToPersonalEthMessage(getDerEncoding());
    }

    public boolean checkValidity() {
        return att.checkValidity();
    }

    public boolean verify() {
        return att.verify();
    }
}
