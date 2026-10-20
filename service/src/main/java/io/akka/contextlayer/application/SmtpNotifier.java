package io.akka.contextlayer.application;

import io.akka.contextlayer.governance.PersonalDataMasker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocketFactory;

/**
 * Delivers a notification as an email through the one mail server the deployment is configured with. A route of format
 * {@code email} has an address of the form {@code mailto:a@example.com,b@example.com}. The server's host must be on
 * the egress list. What is sent has personal data masked and carries ids that point to the console, never records.
 *
 * <p>It is a small client for the part of SMTP a notification needs: greeting, optional STARTTLS or TLS from the start,
 * optional PLAIN sign-in, one message to each recipient. A refusal by the server is returned as the server's own reply
 * text, which names no secret.
 */
public class SmtpNotifier implements Notifier {

  public enum Security { NONE, STARTTLS, SSL }

  /** @param host blank when email is not set up; then every delivery fails and says so */
  public record Settings(String host, int port, Security security, String user, String passwordRef, String from) {
    public boolean configured() {
      return host != null && !host.isBlank() && from != null && !from.isBlank();
    }
  }

  public static final int MAX_RECIPIENTS = 10;
  private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH);

  private final Settings settings;
  private final SecretProvider secrets;
  private final EgressPolicy egress;
  private final int timeoutMillis;

  public SmtpNotifier(Settings settings, SecretProvider secrets, EgressPolicy egress, java.time.Duration timeout) {
    this.settings = settings;
    this.secrets = secrets;
    this.egress = egress;
    this.timeoutMillis = (int) timeout.toMillis();
  }

  /** The addresses in a route's {@code mailto:} address, or empty when it holds none that is valid or holds more than allowed. */
  public static List<String> recipients(String url) {
    if (url == null || !url.regionMatches(true, 0, "mailto:", 0, 7)) return List.of();
    var out = new ArrayList<String>();
    for (var part : url.substring(7).split(",")) {
      var address = part.strip();
      if (!ADDRESS.matcher(address).matches()) return List.of();
      out.add(address);
    }
    return out.size() > MAX_RECIPIENTS ? List.of() : List.copyOf(out);
  }

  @Override
  public Result send(Route route, Message message, String idempotencyKey) {
    if (!settings.configured()) return Result.failed("email is not set up for this deployment");
    var to = recipients(route.url());
    if (to.isEmpty()) return Result.failed("the route's address is not one to " + MAX_RECIPIENTS + " valid email addresses");
    if (!egress.allows(settings.host())) return Result.failed("the mail server is not on the " + SourceCaller.EGRESS_REFUSAL);
    String password = null;
    if (settings.passwordRef() != null && !settings.passwordRef().isBlank()) {
      password = secrets.resolve(settings.passwordRef()).orElse(null);
      if (password == null) return Result.failed("the mail server's password is not available");
    }
    try {
      deliver(to, mime(to, message, idempotencyKey), password);
      return Result.ok("delivered to " + to.size() + " address(es)");
    } catch (SmtpRefused e) {
      return Result.failed("the mail server said: " + e.getMessage());
    } catch (IOException e) {
      return Result.failed("could not reach the mail server (" + e.getClass().getSimpleName() + ")");
    }
  }

  // ---- the message

  String mime(List<String> to, Message m, String idempotencyKey) {
    var title = PersonalDataMasker.mask(m.title()).text();
    var body = new StringBuilder(PersonalDataMasker.mask(m.summary()).text()).append("\r\n");
    if (!m.reference().isEmpty()) {
      body.append("\r\nWhere to look:\r\n");
      m.reference().forEach((k, v) -> body.append("  ").append(k).append(": ").append(v).append("\r\n"));
    }
    if (m.dueAt() != null) body.append("\r\nDue: ").append(m.dueAt()).append("\r\n");
    var encoded = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(body.toString().getBytes(StandardCharsets.UTF_8));
    return "From: " + settings.from() + "\r\n"
        + "To: " + String.join(", ", to) + "\r\n"
        + "Subject: " + subject(title) + "\r\n"
        + "Date: " + DATE.format(ZonedDateTime.now(ZoneOffset.UTC)) + "\r\n"
        + "Message-ID: <" + idempotencyKey.replaceAll("[^A-Za-z0-9._-]", "_") + "@contextlayer>\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n"
        + "Content-Transfer-Encoding: base64\r\n\r\n"
        + encoded + "\r\n";
  }

  /** A subject with anything outside ASCII, or any line break, written as an RFC 2047 encoded word. */
  static String subject(String title) {
    var single = title.replaceAll("[\\r\\n]+", " ").strip();
    if (single.chars().allMatch(c -> c >= 32 && c < 127)) return single;
    return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(single.getBytes(StandardCharsets.UTF_8)) + "?=";
  }

  // ---- the conversation

  /** The server refused something; the message is its reply. */
  static final class SmtpRefused extends IOException {
    SmtpRefused(String reply) {
      super(reply);
    }
  }

  private void deliver(List<String> to, String message, String password) throws IOException {
    try (Socket plain = new Socket()) {
      plain.connect(new InetSocketAddress(settings.host(), settings.port()), timeoutMillis);
      plain.setSoTimeout(timeoutMillis);
      Socket socket = settings.security() == Security.SSL ? tls(plain) : plain;
      var session = new Session(socket);
      session.expect(220);
      session.command("EHLO contextlayer", 250);
      if (settings.security() == Security.STARTTLS) {
        session.command("STARTTLS", 220);
        socket = tls(plain);
        session = new Session(socket);
        session.command("EHLO contextlayer", 250);
      }
      if (settings.user() != null && !settings.user().isBlank() && password != null) {
        var token = Base64.getEncoder().encodeToString(("\0" + settings.user() + "\0" + password).getBytes(StandardCharsets.UTF_8));
        session.command("AUTH PLAIN " + token, 235);
      }
      session.command("MAIL FROM:<" + bare(settings.from()) + ">", 250);
      for (var address : to) session.command("RCPT TO:<" + address + ">", 250, 251);
      session.command("DATA", 354);
      session.data(message);
      session.expect(250);
      session.command("QUIT", 221);
    }
  }

  private SSLSocketFactory factory() {
    return (SSLSocketFactory) SSLSocketFactory.getDefault();
  }

  private Socket tls(Socket over) throws IOException {
    var secured = factory().createSocket(over, settings.host(), settings.port(), true);
    secured.setSoTimeout(timeoutMillis);
    ((javax.net.ssl.SSLSocket) secured).startHandshake();
    return secured;
  }

  /** The address inside {@code Name <address>}, or the text as it is. */
  static String bare(String from) {
    int open = from.indexOf('<');
    int close = from.indexOf('>');
    return open >= 0 && close > open ? from.substring(open + 1, close).strip() : from.strip();
  }

  private static final class Session {
    private final BufferedReader in;
    private final OutputStream out;

    Session(Socket socket) throws IOException {
      this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      this.out = socket.getOutputStream();
    }

    void command(String line, int... accepted) throws IOException {
      out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
      expect(accepted);
    }

    /** Sends the message with each line that starts with a dot doubled, then the lone dot that ends it. */
    void data(String message) throws IOException {
      for (var line : message.split("\r\n", -1)) {
        out.write(((line.startsWith(".") ? "." : "") + line + "\r\n").getBytes(StandardCharsets.UTF_8));
      }
      out.write(".\r\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
    }

    void expect(int... accepted) throws IOException {
      String line;
      int code;
      do {
        line = in.readLine();
        if (line == null) throw new IOException("the mail server closed the connection");
        if (line.length() < 3) throw new IOException("the mail server sent something that is not a reply");
        code = Integer.parseInt(line.substring(0, 3));
      } while (line.length() > 3 && line.charAt(3) == '-');
      for (var ok : accepted) if (code == ok) return;
      throw new SmtpRefused(line.strip());
    }
  }
}
