package tapi.api.crypto;

import java.math.BigInteger;
import org.bouncycastle.math.ec.ECPoint;
import tapi.api.crypto.core.ASNEncodable;

public interface ProofOfExponent extends ASNEncodable {
  public ECPoint getPoint();
  public BigInteger getChallenge();
  public byte[] getNonce();
}
