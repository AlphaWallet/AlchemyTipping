package tapi.api.crypto;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.text.ParseException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Null;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.Time;
import tapi.api.crypto.core.ASNEncodable;
import tapi.api.crypto.core.Validateable;
import tapi.api.crypto.ethereum.SignMessageType;
import tapi.api.crypto.ethereum.Signable;

public class Attestation implements Signable, ASNEncodable, Validateable {

  public static final ASN1ObjectIdentifier OID_OCTETSTRING = new ASN1ObjectIdentifier("1.3.6.1.4.1.1466.115.121.1.40");

  // Attestation fields
  private ASN1Integer version = new ASN1Integer(
          18); // = 0x10+0x02 where 0x02 means x509 v3 (v1 has version 0) and 0x10 is Attestation v 0
  private ASN1Integer serialNumber;

  private AlgorithmIdentifier signingAlgorithm;
  private X500Name issuer;                              // Optional
  private ASN1GeneralizedTime notValidBefore;           // Optional
  private ASN1GeneralizedTime notValidAfter;            // Optional
  private X500Name subject;  // CN=Ethereum address     // Optional
  private SubjectPublicKeyInfo subjectPublicKeyInfo;    // Optional
  private ASN1Sequence smartcontracts; // ASN1integers  // Optional
  private ASN1Sequence dataObject;
  private ASN1Sequence extensions;

  public Attestation() {
  }

  public Attestation(byte[] derEncoding) throws IOException, IllegalArgumentException {
    ASN1InputStream input = new ASN1InputStream(derEncoding);
    int currentPos = 0;
    ASN1Sequence asn1 = ASN1Sequence.getInstance(input.readObject());
    ASN1TaggedObject taggedVersion = ASN1TaggedObject.getInstance(asn1.getObjectAt(currentPos));
    currentPos++;
    version = ASN1Integer.getInstance(taggedVersion.getObject());

    serialNumber = ASN1Integer.getInstance(asn1.getObjectAt(currentPos));
    currentPos++;

    signingAlgorithm = AlgorithmIdentifier.getInstance(asn1.getObjectAt(currentPos));
    currentPos++;

    ASN1Sequence issuerSeq = ASN1Sequence.getInstance(asn1.getObjectAt(currentPos));
    currentPos++;
    // Issuer is optional in the sense that it can be an empty sequence
    if (issuerSeq.size() == 0) {
      issuer = null;
    } else {
      issuer = X500Name.getInstance(issuerSeq);
    }

    // Figure out if validity is included
    if (asn1.getObjectAt(currentPos) instanceof ASN1Null) {
      notValidBefore = null;
      notValidAfter = null;
    } else {
      ASN1Sequence validity = ASN1Sequence.getInstance(asn1.getObjectAt(currentPos));
      notValidBefore = ASN1GeneralizedTime.getInstance(validity.getObjectAt(0));
      notValidAfter = ASN1GeneralizedTime.getInstance(validity.getObjectAt(1));
    }
    currentPos++;

    ASN1Sequence subjectSeq = ASN1Sequence.getInstance(asn1.getObjectAt(currentPos));
    currentPos++;
    // Subject is optional in the sense that it can be an empty sequence
    if (subjectSeq.size() == 0) {
      subject = null;
    } else {
      subject = X500Name.getInstance(subjectSeq);
    }

    if (asn1.getObjectAt(currentPos) instanceof ASN1Null) {
      subjectPublicKeyInfo = null;
    } else {
      subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(asn1.getObjectAt(currentPos));
    }
    currentPos++;

    // The optional smartcontracts are included
    if (asn1.size() > currentPos && asn1.getObjectAt(currentPos) instanceof ASN1Sequence) {
      smartcontracts = ASN1Sequence.getInstance(asn1.getObjectAt(currentPos));
      currentPos++;
    }

    if (asn1.size() > currentPos) {
      ASN1TaggedObject objects = ASN1TaggedObject.getInstance(asn1.getObjectAt(currentPos));
      currentPos++;
      if (objects.getTagNo() == 3) {
        extensions = ASN1Sequence.getInstance(objects.getObject());
      } else {
        dataObject = ASN1Sequence.getInstance(objects.getObject());
      }
    }
  }

  public int getVersion() {
    return version.getValue().intValueExact();
  }

  public void setVersion(int version) {
    this.version = new ASN1Integer(version);
  }

  public int getSerialNumber() {
    return serialNumber.getValue().intValueExact();
  }

  // TODO change to up-to 20 byte array
  public void setSerialNumber(long serialNumber) {
    this.serialNumber = new ASN1Integer(serialNumber);
  }

  public AlgorithmIdentifier getSigningAlgorithm() {
    return this.signingAlgorithm;
  }

  /**
   * The signingAlgorithm is to be used in the signature section of the attestation
   * as well as appearing in the TBS (To be signed) data
   */
  public void setSigningAlgorithm(AlgorithmIdentifier signingAlgorithm) {
    this.signingAlgorithm = signingAlgorithm;
  }

  public String getIssuer() {
    return issuer.toString();
  }

  /**
   * Constructs a name from a conventionally formatted string, such as "CN=Dave, OU=JavaSoft, O=Sun
   * Microsystems, C=US".
   */
  public void setIssuer(String issuer) {
    this.issuer = new X500Name(issuer);
  }

  public Date getNotValidBefore() {
    try {
      return notValidBefore != null ? notValidBefore.getDate() : null;
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  public void setNotValidBefore(Date notValidBefore) {
    this.notValidBefore = new ASN1GeneralizedTime(notValidBefore);
  }

  public Date getNotValidAfter() {
    try {
      return notValidAfter != null ? notValidAfter.getDate() : null;
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  public void setNotValidAfter(Date notValidAfter) {
    this.notValidAfter = new ASN1GeneralizedTime(notValidAfter);
  }

  public String getSubject() {
    return subject.toString();
  }

  public void setSubject(String subject) {
    this.subject = new X500Name(subject);
  }

  public void setSubject(X500Name subject) {
    this.subject = subject;
  }

  public SubjectPublicKeyInfo getSubjectPublicKeyInfo() {
    return subjectPublicKeyInfo;
  }

  public void setSubjectPublicKeyInfo(SubjectPublicKeyInfo spki) {
    this.subjectPublicKeyInfo = spki;
  }

  public List<Long> getSmartcontracts() {
    List<Long> res = new ArrayList<>();
    Iterator<ASN1Encodable> it = smartcontracts.iterator();
    while (it.hasNext()) {
      res.add(((ASN1Integer) it.next()).getValue().longValueExact());
    }
    return res;
  }

  // TODO change to list of arrays of 20 bytes
  public void setSmartcontracts(List<Long> smartcontracts) {
    ASN1EncodableVector seq = new ASN1EncodableVector();
    for (long current : smartcontracts) {
      seq.add(new ASN1Integer(current));
    }
    this.smartcontracts = new DERSequence(seq);
  }

  public ASN1Sequence getExtensions() {
    return extensions;
  }

  public void setExtensions(ASN1Sequence extensions) {
    if (dataObject != null) {
      throw new IllegalArgumentException(
              "DataObject already set. Only one of DataObject and Extensions is allowed.");
    }
    this.extensions = extensions;
  }

  public ASN1Sequence getDataObject() {
    return dataObject;
  }

  public void setDataObject(ASN1Sequence dataObject) {
    if (extensions != null) {
      throw new IllegalArgumentException(
              "Extensions already set. Only one of DataObject and Extensions is allowed.");
    }
    this.dataObject = dataObject;
  }

  /**
   * Returns true if the attestation obeys X509v3, RFC 5280
   */
  public boolean isValidX509() {
    if (version.getValue().intValueExact() != 0 && version.getValue().intValueExact() != 1
            && version.getValue().intValueExact() != 2) {
      return false;
    }
    if (issuer == null || issuer.getRDNs().length == 0) {
      return false;
    }
    if (notValidBefore == null || notValidAfter == null) {
      return false;
    }
    if (subject == null) {
      return false;
    }
    if (subjectPublicKeyInfo == null) {
      return false;
    }
    if (smartcontracts != null) {
      return false;
    }
    if (dataObject != null) {
      return false;
    }
    if (version == null || subject == null || serialNumber == null || signingAlgorithm == null) {
      return false;
    }
    return true;
  }

  @Override
  public boolean checkValidity() {
    if (version == null || subject == null || serialNumber == null || signingAlgorithm == null) {
      return false;
    }
    if (getNotValidBefore() != null && getNotValidAfter() != null) {
      long currentTime = Clock.systemUTC().millis();
      Date attNotBefore = getNotValidBefore();
      Date attNotAfter = getNotValidAfter();
      if (attNotBefore != null && attNotAfter != null) {
        if (!(currentTime >= attNotBefore.getTime() && currentTime < attNotAfter.getTime())) {
          System.err.println("Attestation is no longer valid");
          return false;
        }
      }
    }
    if (extensions != null && dataObject != null) {
      return false;
    }
    return true;
  }

  @Override
  public byte[] getDerEncoding() throws InvalidObjectException {
    byte[] attEncoded = getPrehash();
    // The method returns null if the encoding is invalid
    if (attEncoded == null) {
      throw new InvalidObjectException("The attestation is not valid");
    }
    return attEncoded;
  }

  /**
   * Construct the DER encoded byte array to be signed. Returns null if the Attestation object is
   * not valid
   */
  @Override
  public byte[] getPrehash() {
    if (!checkValidity()) {
      return null;
    }
    ASN1EncodableVector res = new ASN1EncodableVector();
    res.add(new DERTaggedObject(true, 0, this.version));
    res.add(this.serialNumber);
    res.add(this.signingAlgorithm);
    res.add(this.issuer == null ? new DERSequence() : this.issuer);
    if (this.notValidAfter != null && this.notValidBefore != null) {
      ASN1EncodableVector date = new ASN1EncodableVector();
      date.add(new Time(this.notValidBefore));
      date.add(new Time(this.notValidAfter));
      res.add(new DERSequence(date));
    } else {
      res.add(DERNull.INSTANCE);
    }
    res.add(this.subject == null ? new DERSequence() : this.subject);
    res.add(this.subjectPublicKeyInfo == null ? DERNull.INSTANCE : this.subjectPublicKeyInfo);
    if (this.smartcontracts != null) {
      res.add(this.smartcontracts);
    }
    // The validity check ensure that only one of "extensions" and "dataObject" is set
    if (this.extensions != null) {
      res.add(new DERTaggedObject(true, 3, this.extensions));
    }
    if (this.dataObject != null) {
      res.add(new DERTaggedObject(true, 4, this.dataObject));
    }
    try {
      return new DERSequence(res).getEncoded();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getOrigin() {
    return null;
  }

  @Override
  public CharSequence getUserMessage() {
    return null;
  }

  @Override
  public String getMessage() {
    throw new RuntimeException("Not allowed");
  }

  @Override
  public SignMessageType getMessageType()
  {
    return SignMessageType.ATTESTATION;
  }

  @Override
  public long getCallbackId() {
    // TODO check that dataObject is actually an Extensions
    return 0;
  }
}
