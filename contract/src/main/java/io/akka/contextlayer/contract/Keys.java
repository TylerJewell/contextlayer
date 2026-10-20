package io.akka.contextlayer.contract;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;

/**
 * The two keys a contract is signed with, kept apart as the library requires: the publisher key signs the contract as
 * published, and the risk-team key, with its certificate, signs off the controls. Private keys stay in a directory
 * outside the repository; the public key and the certificate are the trust a verifier is given.
 */
public final class Keys {

  static final String PUBLISHER_KEY = "publisher.key.pem";
  static final String PUBLISHER_PUBLIC = "publisher.pub.pem";
  static final String RISK_KEY = "risk-team.key.pem";
  static final String RISK_CERT = "risk-team.cert.pem";
  static final String TSA_KEY = "tsa.key.pem";
  static final String TSA_CERT = "tsa.cert.pem";

  private Keys() {}

  /**
   * Writes new keys: the private ones to {@code privateDir}, the public key and certificates to {@code trustDir}. The
   * timestamp authority's pair is for {@link LocalTimestampAuthority}.
   */
  public static void generate(Path privateDir, Path trustDir, String riskTeamName) throws IOException {
    Files.createDirectories(privateDir);
    Files.createDirectories(trustDir);
    var publisher = newKeyPair();
    var risk = newKeyPair();
    var tsa = newKeyPair();
    write(privateDir.resolve(PUBLISHER_KEY), pkcs8(publisher.getPrivate()));
    write(trustDir.resolve(PUBLISHER_PUBLIC), publisher.getPublic());
    write(privateDir.resolve(RISK_KEY), pkcs8(risk.getPrivate()));
    write(trustDir.resolve(RISK_CERT), selfSigned(risk, "CN=" + riskTeamName + ",OU=risk-team,O=contextlayer", false));
    write(privateDir.resolve(TSA_KEY), pkcs8(tsa.getPrivate()));
    write(trustDir.resolve(TSA_CERT), selfSigned(tsa, "CN=local timestamp authority,O=contextlayer", true));
  }

  public static PrivateKey privateKey(Path file) throws IOException {
    try (var parser = new PEMParser(Files.newBufferedReader(file))) {
      return new JcaPEMKeyConverter().getPrivateKey(PrivateKeyInfo.getInstance(parser.readObject()));
    }
  }

  public static PublicKey publicKey(Path file) throws IOException {
    try (var parser = new PEMParser(Files.newBufferedReader(file))) {
      return new JcaPEMKeyConverter().getPublicKey(SubjectPublicKeyInfo.getInstance(parser.readObject()));
    }
  }

  public static X509Certificate certificate(Path file) throws IOException {
    try (var parser = new PEMParser(Files.newBufferedReader(file))) {
      return new JcaX509CertificateConverter().getCertificate((X509CertificateHolder) parser.readObject());
    } catch (java.security.cert.CertificateException e) {
      throw new IOException("not a certificate: " + file, e);
    }
  }

  static KeyPair newKeyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** @param timestamping RFC 3161 requires a timestamp authority's certificate to say so, as a critical extension */
  static X509Certificate selfSigned(KeyPair pair, String subject, boolean timestamping) {
    try {
      var name = new X500Name(subject);
      var now = Instant.now();
      var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()), Date.from(now),
          Date.from(now.plus(Duration.ofDays(3 * 365))), name, pair.getPublic());
      if (timestamping) {
        builder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
      }
      var holder = builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate()));
      return new JcaX509CertificateConverter().getCertificate(holder);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** PKCS#8 ("PRIVATE KEY"), so the file reads back as a key on its own and any tool can open it. */
  private static Object pkcs8(PrivateKey key) {
    try {
      return new JcaPKCS8Generator(key, null);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void write(Path file, Object value) {
    var out = new StringWriter();
    try (var writer = new JcaPEMWriter(out)) {
      writer.writeObject(value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      Files.writeString(file, out.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
