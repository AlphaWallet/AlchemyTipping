package tapi.api.crypto;

import tapi.api.crypto.core.Attestable;

import java.io.IOException;

public interface AttestableObjectDecoder<T extends Attestable> {
  public T decode(byte[] encoding) throws IOException;
}
