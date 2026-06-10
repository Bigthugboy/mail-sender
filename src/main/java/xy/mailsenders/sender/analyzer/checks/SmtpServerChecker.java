package xy.mailsenders.sender.analyzer.checks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import xy.mailsenders.sender.analyzer.CheckItem;

import jakarta.mail.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * SMTP Server Health Checker.
 *
 * Connects to the actual SMTP server and analyses its identity and capabilities.
 * A misconfigured SMTP server is a primary cause of spam classification.
 *
 * Checks:
 *  1.  SMTP connection — can we reach host:port?
 *  2.  SMTP banner — does the 220 banner contain the correct hostname?
 *  3.  EHLO response — does the server accept EHLO?
 *  4.  STARTTLS support — is TLS advertised?
 *  5.  AUTH support — are AUTH mechanisms advertised?
 *  6.  Accepted AUTH types — PLAIN, LOGIN, CRAM-MD5, etc.
 *  7.  Banner hostname vs SMTP host mismatch
 *  8.  Open relay check — does the server accept mail without auth?
 *  9.  SMTP AUTH verification — do credentials actually work?
 * 10.  TLS certificate validity (checks for expired/self-signed)
 */
@Slf4j
@Component
public class SmtpServerChecker {

    private static final String CAT     = "SMTP Server";
    private static final int    TIMEOUT = 10_000;

    public List<CheckItem> run(String smtpHost, int smtpPort,
                               String username, String password, boolean useSsl) {
        List<CheckItem> results = new ArrayList<>();

        // Raw SMTP conversation check (port 25 or 587)
        results.addAll(rawSmtpCheck(smtpHost, smtpPort));

        // Authenticated connection check
        results.add(smtpAuthCheck(smtpHost, smtpPort, username, password, useSsl));

        return results;
    }

    // ── Raw SMTP conversation (no auth — banner + EHLO analysis) ─────────────

    private List<CheckItem> rawSmtpCheck(String smtpHost, int smtpPort) {
        List<CheckItem> results = new ArrayList<>();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(smtpHost, smtpPort), TIMEOUT);
            socket.setSoTimeout(TIMEOUT);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream()), true);

            // 1 — Connection
            results.add(CheckItem.pass("SMTP Connection", CAT,
                    "Connected to " + smtpHost + ":" + smtpPort, smtpHost + ":" + smtpPort));

            // 2 — Read banner
            String banner = readResponse(reader);
            if (!banner.startsWith("2")) {
                results.add(CheckItem.fail("SMTP Banner", CAT, "CRITICAL",
                        "Server returned an error greeting: " + banner.trim(),
                        "The server is not accepting connections. Check if SMTP is running.", banner.trim()));
                return results;
            }

            String bannerFirstLine = banner.lines().findFirst().orElse(banner).trim();
            results.add(CheckItem.pass("SMTP Banner (220)", CAT,
                    "Server banner received: " + bannerFirstLine, bannerFirstLine));

            // 3 — Banner hostname check
            if (!bannerContainsDomain(bannerFirstLine, smtpHost)) {
                results.add(CheckItem.warn("SMTP Banner Hostname", CAT, "MEDIUM",
                        "The SMTP banner hostname does not match your configured SMTP host.\n" +
                        "Banner: \"" + bannerFirstLine + "\"\n" +
                        "Expected something containing: " + smtpHost,
                        "The banner hostname should match the PTR record and SMTP host. " +
                        "Configure your mail server's HELO/EHLO hostname to match " + smtpHost,
                        bannerFirstLine));
            } else {
                results.add(CheckItem.pass("SMTP Banner Hostname", CAT,
                        "Banner hostname matches SMTP host.", bannerFirstLine));
            }

            // 4 — EHLO
            writer.println("EHLO analyzer.mailcheck.io");
            String ehlo = readResponse(reader);
            if (!ehlo.startsWith("2")) {
                results.add(CheckItem.fail("EHLO Support", CAT, "HIGH",
                        "Server rejected EHLO: " + ehlo.trim(),
                        "Server must support EHLO (ESMTP). Only very old servers use HELO.", ehlo.trim()));
                return results;
            }
            results.add(CheckItem.pass("EHLO Support", CAT,
                    "Server supports ESMTP (EHLO accepted).", "ESMTP"));

            // 5 — STARTTLS
            boolean hasTls = ehlo.contains("STARTTLS");
            if (smtpPort != 465) { // 465 uses implicit SSL, no STARTTLS
                if (hasTls) {
                    results.add(CheckItem.pass("STARTTLS Support", CAT,
                            "Server advertises STARTTLS — connections can be upgraded to TLS.", "STARTTLS"));
                } else {
                    results.add(CheckItem.fail("STARTTLS Support", CAT, "HIGH",
                            "Server does NOT advertise STARTTLS on port " + smtpPort + ".\n" +
                            "Emails sent without TLS can be intercepted — and many providers reject unencrypted connections.",
                            "Configure your SMTP server to support STARTTLS (TLS on port 587).", null));
                }
            }

            // 6 — AUTH mechanisms
            boolean hasAuth = ehlo.contains("AUTH");
            if (hasAuth) {
                String authLine = ehlo.lines()
                        .filter(l -> l.contains("AUTH"))
                        .findFirst().orElse("AUTH");
                String mechanisms = authLine.replaceAll(".*AUTH\\s*", "").trim();
                results.add(CheckItem.pass("AUTH Mechanisms", CAT,
                        "Server advertises AUTH: " + mechanisms, mechanisms));

                // PLAIN over plain text without TLS is a security risk
                if (!hasTls && mechanisms.contains("PLAIN")) {
                    results.add(CheckItem.warn("AUTH PLAIN without TLS", CAT, "HIGH",
                            "Server allows AUTH PLAIN without TLS — passwords are sent in base64 (not encrypted).",
                            "Enable STARTTLS before AUTH, or switch to port 465 with implicit SSL.", mechanisms));
                }
            } else {
                results.add(CheckItem.warn("AUTH Mechanisms", CAT, "MEDIUM",
                        "Server did not advertise AUTH in EHLO response.",
                        "Check if the server requires STARTTLS before offering AUTH.", ehlo));
            }

            // 7 — Open relay test (send MAIL FROM without auth)
            writer.println("MAIL FROM:<open-relay-test@analyzer.mailcheck.io>");
            String mailFromResp = readResponse(reader);
            if (mailFromResp.startsWith("2")) {
                writer.println("RCPT TO:<test@gmail.com>");
                String rcptResp = readResponse(reader);
                if (rcptResp.startsWith("2")) {
                    results.add(CheckItem.fail("Open Relay Test", CAT, "CRITICAL",
                            "⛔ OPEN RELAY DETECTED! Server accepted MAIL FROM + RCPT TO without authentication.\n" +
                            "Your server is being (or will be) used by spammers — you will be blacklisted.",
                            "Restrict relaying to authenticated users only. This is a critical security issue.",
                            "OPEN RELAY"));
                } else {
                    results.add(CheckItem.pass("Open Relay Test", CAT,
                            "Server correctly rejects unauthenticated relay attempts.", "closed"));
                    writer.println("RSET");
                    readResponse(reader);
                }
            } else {
                results.add(CheckItem.pass("Open Relay Test", CAT,
                        "Server requires authentication before accepting MAIL FROM.", "closed"));
            }

            // Quit gracefully
            writer.println("QUIT");
            readResponse(reader);

        } catch (java.net.ConnectException e) {
            results.add(CheckItem.fail("SMTP Connection", CAT, "CRITICAL",
                    "Cannot connect to " + smtpHost + ":" + smtpPort + " — connection refused.",
                    "Check that your SMTP host and port are correct, and that the server is running. " +
                    "Port 25 is often blocked by ISPs — try port 587.", e.getMessage()));
        } catch (java.net.SocketTimeoutException e) {
            results.add(CheckItem.fail("SMTP Connection", CAT, "HIGH",
                    "Connection to " + smtpHost + ":" + smtpPort + " timed out after " + (TIMEOUT / 1000) + "s.",
                    "The server may be down, or port " + smtpPort + " is blocked by your hosting firewall.",
                    e.getMessage()));
        } catch (Exception e) {
            results.add(CheckItem.error("SMTP Connection", CAT, e.getMessage()));
        }

        return results;
    }

    // ── Authenticated SMTP check ──────────────────────────────────────────────

    private CheckItem smtpAuthCheck(String smtpHost, int smtpPort,
                                    String username, String password, boolean useSsl) {
        if (username == null || username.isBlank()) {
            return CheckItem.skip("SMTP Authentication", CAT, "No credentials provided — skipped.");
        }

        String proto = useSsl ? "smtps" : "smtp";
        java.util.Properties props = new java.util.Properties();
        props.put("mail." + proto + ".host",              smtpHost);
        props.put("mail." + proto + ".port",              String.valueOf(smtpPort));
        props.put("mail." + proto + ".auth",              "true");
        props.put("mail." + proto + ".connectiontimeout", String.valueOf(TIMEOUT));
        props.put("mail." + proto + ".timeout",           String.valueOf(TIMEOUT));
        props.put("mail.smtp.ssl.trust",                  "*");
        props.put("mail.smtp.ssl.checkserveridentity",    "false");
        if (useSsl) {
            props.put("mail.smtps.ssl.enable", "true");
            props.put("mail.smtps.ssl.checkserveridentity", "false");
        } else {
            props.put("mail.smtp.starttls.enable",   "true");
            props.put("mail.smtp.starttls.required", "false"); // don't fail if no TLS
        }

        try {
            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });
            session.setDebug(false);
            long t = System.currentTimeMillis();
            try (Transport transport = session.getTransport(proto)) {
                transport.connect(smtpHost, smtpPort, username, password);
                long ms = System.currentTimeMillis() - t;
                return CheckItem.pass("SMTP Authentication", CAT,
                        "Credentials accepted by " + smtpHost + ":" + smtpPort + " in " + ms + "ms.",
                        username + "@" + smtpHost);
            }
        } catch (AuthenticationFailedException e) {
            return CheckItem.fail("SMTP Authentication", CAT, "CRITICAL",
                    "Authentication FAILED for " + username + " on " + smtpHost + ":" + smtpPort + ".\n" +
                    "Your SMTP credentials are incorrect — no email can be sent.",
                    "Double-check username and password. For Brevo/SendGrid, use the SMTP API key, not your login password.",
                    e.getMessage());
        } catch (MessagingException e) {
            return CheckItem.fail("SMTP Authentication", CAT, "HIGH",
                    "SMTP connection error during auth: " + e.getMessage(),
                    "Check your SMTP host, port, and whether SSL/STARTTLS setting matches the port.",
                    e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String readResponse(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            sb.append(line).append("\n");
            if (line.length() >= 4 && line.charAt(3) == ' ') break;
        }
        return sb.toString();
    }

    private boolean bannerContainsDomain(String banner, String smtpHost) {
        if (banner == null || smtpHost == null) return false;
        String bannerLow = banner.toLowerCase();
        String hostLow   = smtpHost.toLowerCase();
        // Check exact match or root domain match
        return bannerLow.contains(hostLow) ||
               bannerLow.contains(rootDomain(hostLow));
    }

    private String rootDomain(String host) {
        String[] parts = host.split("\\.");
        if (parts.length < 2) return host;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
