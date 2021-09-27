package tapi.api.crypto.core;

public interface Attestable extends ASNEncodable, Verifiable, Validateable {
  public byte[] getCommitment();
}
