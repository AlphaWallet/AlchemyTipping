package tapi.api;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.math.ec.ECMultiplier;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import tapi.api.crypto.*;
import tapi.api.crypto.core.SignatureUtility;

import java.io.IOException;
import java.security.SecureRandom;

import static tapi.api.crypto.core.SignatureUtility.ECDSA_DOMAIN;

public class AttestationHandler {
    private static AsymmetricCipherKeyPair attestorKeys;
    private static SecureRandom rand;

    public static void setupKeys(String keyHex)
    {
        try {
            rand = SecureRandom.getInstance("SHA1PRNG");
            rand.setSeed("seed".getBytes());
            AsymmetricCipherKeyPair dud = SignatureUtility.constructECKeys(rand);
            //attestorKeys = SignatureUtility.constructECKeys(rand);

            ECKeyPair attestationKeyPair = ECKeyPair.create(Numeric.toBigInt(keyHex));

            ECKeyPairGenerator generator = new ECKeyPairGenerator();
            ECDomainParameters params = ECDSA_DOMAIN;
            ECPrivateKeyParameters privKey = new ECPrivateKeyParameters(attestationKeyPair.getPrivateKey(), ECDSA_DOMAIN);

            ECPoint Q = createBasePointMultiplier().multiply(params.getG(), attestationKeyPair.getPrivateKey());
            ECPublicKeyParameters publicKey = new ECPublicKeyParameters(Q, ECDSA_DOMAIN);

            //ECKeyGenerationParameters keygenParams = new ECKeyGenerationParameters(ECDSA_DOMAIN, random);

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
