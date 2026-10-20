package io.akka.contextlayer.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.akka.contextlayer.application.Notifier.Message;
import io.akka.contextlayer.application.Notifier.Route;
import io.akka.contextlayer.application.SmtpNotifier.Security;
import io.akka.contextlayer.application.SmtpNotifier.Settings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmtpNotifierTest {

  /** A mail server that records what it is sent and answers as scripted. */
  private static final class FakeServer implements AutoCloseable {
    final ServerSocket socket;
    final List<String> commands = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    volatile String refuseRecipient = "";
    volatile boolean requireAuth;

    FakeServer() throws IOException {
      socket = new ServerSocket(0);
      var thread = new Thread(this::serve, "fake-smtp");
      thread.setDaemon(true);
      thread.start();
    }

    int port() {
      return socket.getLocalPort();
    }

    private void serve() {
      while (!socket.isClosed()) {
        try (var client = socket.accept()) {
          var in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
          var out = client.getOutputStream();
          reply(out, "220 fake ready");
          String line;
          boolean authed = false;
          while ((line = in.readLine()) != null) {
            commands.add(line);
            if (line.startsWith("EHLO")) {
              reply(out, "250-fake", "250 AUTH PLAIN");
            } else if (line.startsWith("AUTH PLAIN")) {
              authed = true;
              reply(out, "235 ok");
            } else if (line.startsWith("MAIL FROM")) {
              reply(out, requireAuth && !authed ? "530 authentication required" : "250 ok");
            } else if (line.startsWith("RCPT TO")) {
              reply(out, !refuseRecipient.isEmpty() && line.contains(refuseRecipient) ? "550 no such mailbox" : "250 ok");
            } else if (line.equals("DATA")) {
              reply(out, "354 go");
              var data = new StringBuilder();
              while (!(line = in.readLine()).equals(".")) data.append(line.startsWith("..") ? line.substring(1) : line).append("\n");
              bodies.add(data.toString());
              reply(out, "250 queued");
            } else if (line.equals("QUIT")) {
              reply(out, "221 bye");
              break;
            } else {
              reply(out, "500 what");
            }
          }
        } catch (IOException e) {
          // the test closed the server
        }
      }
    }

    private static void reply(java.io.OutputStream out, String... lines) throws IOException {
      for (var l : lines) out.write((l + "\r\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }

  private FakeServer server;

  @BeforeEach
  void start() throws IOException {
    server = new FakeServer();
  }

  @AfterEach
  void stop() throws IOException {
    server.close();
  }

  private SmtpNotifier notifier(String user, String passwordRef, Optional<String> password, List<String> egress) {
    var settings = new Settings("localhost", server.port(), Security.NONE, user, passwordRef, "Context Layer <context@example.com>");
    return new SmtpNotifier(settings, ref -> password, new EgressPolicy(egress), Duration.ofSeconds(3));
  }

  private static Message message(String title, String summary) {
    return new Message("case.opened", "acme", "Claims Team B", title, summary, Map.of("case", "acme:stalled:r1", "console", "/cases/acme:stalled:r1"),
        "2026-10-20T09:00:00Z", "2026-10-19T09:00:00Z");
  }

  private static String decodedBody(String data) {
    var encoded = data.substring(data.indexOf("\n\n") + 2).replace("\n", "");
    return new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
  }

  @Test
  void aNotificationIsSentToEachAddressWithItsReferencesAndNoRecords() {
    var result = notifier("", "", Optional.empty(), List.of("localhost"))
        .send(new Route("mailto:a@example.com,b@example.com", "email", ""), message("A case needs you", "A new case was opened."), "key-1");

    assertThat(result.delivered()).as(result.detail()).isTrue();
    assertThat(server.commands).contains("MAIL FROM:<context@example.com>", "RCPT TO:<a@example.com>", "RCPT TO:<b@example.com>", "DATA", "QUIT");
    var data = server.bodies.get(0);
    assertThat(data).contains("From: Context Layer <context@example.com>").contains("To: a@example.com, b@example.com")
        .contains("Subject: A case needs you").contains("Message-ID: <key-1@contextlayer>").contains("Content-Type: text/plain; charset=UTF-8");
    assertThat(decodedBody(data)).contains("A new case was opened.").contains("case: acme:stalled:r1").contains("Due: 2026-10-20T09:00:00Z");
  }

  @Test
  void personalDataInTheMessageIsMaskedAndASubjectOutsideAsciiIsEncoded() {
    var result = notifier("", "", Optional.empty(), List.of("localhost"))
        .send(new Route("mailto:a@example.com", "email", ""), message("Réclamation de pat@example.com", "Write to pat@example.com"), "key-2");

    assertThat(result.delivered()).isTrue();
    var data = server.bodies.get(0);
    assertThat(data).contains("Subject: =?UTF-8?B?").doesNotContain("pat@example.com");
    assertThat(decodedBody(data)).doesNotContain("pat@example.com");
  }

  @Test
  void aBodyLineThatStartsWithADotDoesNotEndTheMessage() {
    var result = notifier("", "", Optional.empty(), List.of("localhost"))
        .send(new Route("mailto:a@example.com", "email", ""), message("Title", ".\nQUIT\n.hidden"), "key-3");

    assertThat(result.delivered()).isTrue();
    assertThat(decodedBody(server.bodies.get(0))).contains(".\nQUIT\n.hidden");
  }

  @Test
  void aServerThatNeedsSigningInGetsThePasswordFromTheSecretStoreAndNeverSeesItInTheResult() {
    server.requireAuth = true;
    var password = "s3cret-pw";

    var result = notifier("mailer", "vault://mail/password", Optional.of(password), List.of("localhost"))
        .send(new Route("mailto:a@example.com", "email", ""), message("T", "S"), "key-4");

    assertThat(result.delivered()).isTrue();
    var auth = server.commands.stream().filter(c -> c.startsWith("AUTH PLAIN ")).findFirst().orElseThrow();
    assertThat(new String(Base64.getDecoder().decode(auth.substring("AUTH PLAIN ".length())), StandardCharsets.UTF_8)).isEqualTo("\0mailer\0" + password);
    assertThat(result.detail()).doesNotContain(password);
  }

  @Test
  void aPasswordThatCannotBeResolvedStopsBeforeAnythingIsSent() {
    var result = notifier("mailer", "vault://mail/password", Optional.empty(), List.of("localhost"))
        .send(new Route("mailto:a@example.com", "email", ""), message("T", "S"), "key-5");

    assertThat(result.delivered()).isFalse();
    assertThat(result.detail()).contains("password is not available");
    assertThat(server.commands).isEmpty();
  }

  @Test
  void aRecipientTheServerRefusesIsReportedWithTheServersOwnWords() {
    server.refuseRecipient = "bad@example.com";

    var result = notifier("", "", Optional.empty(), List.of("localhost"))
        .send(new Route("mailto:a@example.com,bad@example.com", "email", ""), message("T", "S"), "key-6");

    assertThat(result.delivered()).isFalse();
    assertThat(result.detail()).contains("the mail server said: 550 no such mailbox");
    assertThat(server.bodies).isEmpty();
  }

  @Test
  void aServerThatIsNotOnTheEgressListIsNeverCalled() {
    var result = notifier("", "", Optional.empty(), List.of("mail.example.com"))
        .send(new Route("mailto:a@example.com", "email", ""), message("T", "S"), "key-7");

    assertThat(result.delivered()).isFalse();
    assertThat(result.detail()).contains("not on the");
    assertThat(server.commands).isEmpty();
  }

  @Test
  void withNoMailServerSetUpEveryDeliveryFailsAndSaysSo() {
    var notifier = new SmtpNotifier(new Settings("", 587, Security.STARTTLS, "", "", ""), ref -> Optional.empty(),
        new EgressPolicy(List.of("localhost")), Duration.ofSeconds(1));

    var result = notifier.send(new Route("mailto:a@example.com", "email", ""), message("T", "S"), "key-8");

    assertThat(result.delivered()).isFalse();
    assertThat(result.detail()).contains("email is not set up");
  }

  @Test
  void aServerThatCannotBeReachedIsReportedWithoutAnException() throws IOException {
    int closed = server.port();
    server.close();
    var settings = new Settings("localhost", closed, Security.NONE, "", "", "context@example.com");

    var result = new SmtpNotifier(settings, ref -> Optional.empty(), new EgressPolicy(List.of("localhost")), Duration.ofSeconds(1))
        .send(new Route("mailto:a@example.com", "email", ""), message("T", "S"), "key-9");

    assertThat(result.delivered()).isFalse();
    assertThat(result.detail()).contains("could not reach the mail server");
  }

  @Test
  void onlyWellFormedAddressesInANumberThatIsAllowedMakeARoute() {
    assertThat(SmtpNotifier.recipients("mailto:a@example.com, b@example.org")).containsExactly("a@example.com", "b@example.org");
    assertThat(SmtpNotifier.recipients("mailto:a@example.com,not-an-address")).isEmpty();
    assertThat(SmtpNotifier.recipients("https://example.com")).isEmpty();
    assertThat(SmtpNotifier.recipients("mailto:a@example.com\r\nBcc: x@example.com")).isEmpty();
    var many = new ArrayList<String>();
    for (int i = 0; i < 11; i++) many.add("u" + i + "@example.com");
    assertThat(SmtpNotifier.recipients("mailto:" + String.join(",", many))).isEmpty();
  }

  @Test
  void theSenderIsTheAddressInsideANameAndAngleBrackets() {
    assertThat(SmtpNotifier.bare("Context Layer <context@example.com>")).isEqualTo("context@example.com");
    assertThat(SmtpNotifier.bare("context@example.com")).isEqualTo("context@example.com");
  }
}
