package tapi.api.crypto.ethereum;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.web3j.utils.Numeric;
import tapi.api.crypto.core.ASNEncodable;

import java.io.IOException;
import java.math.BigInteger;

public class ERC721Token implements ASNEncodable {
    public String address;
    public BigInteger tokenId;

    public ERC721Token(String address, String tokenId)
    {
        this.address = address;
        try
        {
            this.tokenId = new BigInteger(tokenId);
        }
        catch (Exception e)
        {
            this.tokenId = BigInteger.ZERO;
        }
    }

    public ERC721Token(String address, BigInteger tokenId)
    {
        this.address = address;
        this.tokenId = tokenId;
    }

    public ASN1EncodableVector getTokenVector()
    {
        ASN1EncodableVector data = new ASN1EncodableVector();
        data.add(new DEROctetString(Numeric.hexStringToByteArray(address)));
        data.add(new DEROctetString(tokenId.toByteArray()));
        return data;
    }

    @Override
    public byte[] getDerEncoding()
    {
        ASN1EncodableVector data = getTokenVector();
        try {
            return new DERSequence(data).getEncoded();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
