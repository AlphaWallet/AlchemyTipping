package tapi.api;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.math.ec.ECMultiplier;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.web3j.crypto.ECKeyPair;
import org.web3j.utils.Numeric;
import tapi.api.crypto.IdentifierAttestation;
import tapi.api.crypto.SignedIdentifierAttestation;
import tapi.api.crypto.core.SignatureUtility;

import java.io.IOException;

import static tapi.api.crypto.core.SignatureUtility.ECDSA_DOMAIN;

public class AttestationHandler {
    private static AsymmetricCipherKeyPair attestorKeys;

    //Generate Attestation signing AsymmetricCipherKeyPair from Ethereum private key in the keys.secret file
    public static void setupKeys(String keyHex)
    {
        try {
            ECKeyPair attestationKeyPair = ECKeyPair.create(Numeric.toBigInt(keyHex));
            ECPrivateKeyParameters privKey = new ECPrivateKeyParameters(attestationKeyPair.getPrivateKey(), ECDSA_DOMAIN);

            ECPoint Q = createBasePointMultiplier().multiply(ECDSA_DOMAIN.getG(), attestationKeyPair.getPrivateKey());
            ECPublicKeyParameters publicKey = new ECPublicKeyParameters(Q, ECDSA_DOMAIN);

            attestorKeys = new AsymmetricCipherKeyPair(
                    publicKey,
                    privKey);

            System.out.println("Attestor Address: " + SignatureUtility.addressFromKey(attestorKeys.getPublic()));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static SignedIdentifierAttestation restoreSignedAttestation(byte[] signedAttestationBytes) throws IOException
    {
        return new SignedIdentifierAttestation(signedAttestationBytes, attestorKeys.getPublic());
    }

    public static SignedIdentifierAttestation createPublicAttestation(AsymmetricKeyParameter subjectPublicKey, String id, String identifier)
    {
        IdentifierAttestation att = new IdentifierAttestation(id, identifier, subjectPublicKey); //  makePublicIdAttestation(subjectPublicKey, "TW", identifier);
        SignedIdentifierAttestation signedAttestation = new SignedIdentifierAttestation(att, attestorKeys);
        return signedAttestation;
    }

    protected static ECMultiplier createBasePointMultiplier()
    {
        return new FixedPointCombMultiplier();
    }
}
