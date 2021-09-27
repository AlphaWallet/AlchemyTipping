package tapi.api.crypto.core;

public interface Validateable {

  /**
   * Returns true of the user-defined, non-cryptographic data within the object is valid.
   */
  public boolean checkValidity();
}
