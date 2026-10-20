package io.akka.contextlayer.contract;

import io.akka.nexus.governance.contract.attest.TimestampSource;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

/**
 * An RFC 3161 timestamp authority run by whoever holds the signing keys. The library will not sign without a source
 * of trusted time; this one dates a signature with the signer's own clock, so it shows when the signer says it signed
 * and nothing more. An outsider who needs a date they can rely on needs a contract signed with {@code --tsa} and an
 * independent authority.
 */
public final class LocalTimestampAuthority implements TimestampSource {

  private static final String SHA256 = "2.16.840.1.101.3.4.2.1";
  /** A policy id under the private-enterprise arc reserved for examples: this authority makes no public promise. */
  private static final ASN1ObjectIdentifier POLICY = new ASN1ObjectIdentifier("1.3.6.1.4.1.32473.1");

  private final PrivateKey key;
  private final X509Certificate certificate;

  public LocalTimestampAuthority(PrivateKey key, X509Certificate certificate) {
    this.key = key;
    this.certificate = certificate;
  }

  @Override
  public byte[] timestamp(byte[] content) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(content);
      var request = new TimeStampRequestGenerator().generate(new ASN1ObjectIdentifier(SHA256), digest);
      var generator = new TimeStampTokenGenerator(
          new JcaSimpleSignerInfoGeneratorBuilder().build("SHA256withECDSA", key, certificate),
          new JcaDigestCalculatorProviderBuilder().build().get(new AlgorithmIdentifier(new ASN1ObjectIdentifier(SHA256))),
          POLICY);
      generator.addCertificates(new JcaCertStore(List.of(new JcaX509CertificateHolder(certificate))));
      var response = new TimeStampResponseGenerator(generator, Set.of(SHA256))
          .generate(request, BigInteger.valueOf(System.nanoTime()), new Date());
      return response.getTimeStampToken().getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException("could not issue a timestamp", e);
    }
  }
}
