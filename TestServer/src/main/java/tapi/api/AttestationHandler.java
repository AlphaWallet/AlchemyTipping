package tapi.api;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import tapi.api.crypto.*;
import tapi.api.crypto.core.SignatureUtility;

import java.io.IOException;
import java.security.SecureRandom;

import static tapi.api.APIController.TWITTER_FAKE_UID;
import static tapi.api.APIController.TWITTER_URL;

public class AttestationHandler {
    private static AsymmetricCipherKeyPair attestorKeys;
    private static SecureRandom rand;

    public static void setupKeys()
    {
        try {
            rand = SecureRandom.getInstance("SHA1PRNG");
            rand.setSeed("seed".getBytes());
            AsymmetricCipherKeyPair dud = SignatureUtility.constructECKeys(rand);
            attestorKeys = SignatureUtility.constructECKeys(rand);

            System.out.println("Attestor Address: " + SignatureUtility.addressFromKey(attestorKeys.getPublic()));

        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static SignedIdentifierAttestation createPublicAttestation(AsymmetricKeyParameter subjectPublicKey, String identifier)
    {
        IdentifierAttestation att = new IdentifierAttestation(TWITTER_FAKE_UID, TWITTER_URL + identifier, subjectPublicKey); //  makePublicIdAttestation(subjectPublicKey, "TW", identifier);
        SignedIdentifierAttestation signedAttestation = new SignedIdentifierAttestation(att, attestorKeys);
        return signedAttestation;
    }

    public static NFTAttestation createNFTAttestation(SignedIdentifierAttestation att, ERC721Token[] attestingTokens)
    {
        tapi.api.crypto.ethereum.ERC721Token[] tokens = new tapi.api.crypto.ethereum.ERC721Token[attestingTokens.length];
        for (int index = 0; index < attestingTokens.length; index++)
        {
            tokens[index] = new tapi.api.crypto.ethereum.ERC721Token(attestingTokens[index].address.getValue(),
                    attestingTokens[index].tokenId.getValue());
        }
        return new NFTAttestation(att, tokens);
    }

    public static NFTAttestation createNFTAttestation(byte[] derEncodingNFTAttestation) throws IOException
    {
        return new NFTAttestation(derEncodingNFTAttestation, attestorKeys.getPublic());
    }

    public static Attestation makePublicIdAttestation(AsymmetricKeyParameter key, String type, String identifier) {
        IdentifierAttestation att = new IdentifierAttestation(type, identifier, key);
        att.setIssuer("CN=ALPHAWALLET");
        att.setSerialNumber(1);
        return att;
    }
}
