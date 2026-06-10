package xy.mailsenders.mail.smtp.tester;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.*;

/**
 * SMTP Domain Tester.
 *
 * Performs a complete pre-flight check of an SMTP server + domain configuration:
 *
 * 1. MX Records    — confirms the domain publishes valid mail exchangers.
 * 2. SPF Record    — checks the TXT record for v=spf1 (anti-spoofing policy).
 * 3. DKIM Record   — probes common DKIM selector TXT records (checks for public key).
 * 4. DMARC Record  — checks _dmarc.<domain> TXT for a DMARC policy.
 * 5. SMTP Connect  — opens a raw SMTP connection on port 25 (outbound MX test).
 * 6. SMTP Auth     — connects to the configured SMTP relay (host:port) and authenticates.
 * 7. SMTP Send     — optionally sends a real test message to a probe address.
 *
 * SRP: testing only — no delivery, no session pooling.
 */
@Slf4j
@Service
public class SmtpDomainTesterService {

    private static final int    DNS_TIMEOUT_MS  = 8_000;
    private static final int    SMTP_TIMEOUT_MS = 10_000;

    // Common DKIM selectors used by popular ESPs
    private static final List<String> DKIM_SELECTORS = List.of(
            "default", "mail", "email", "dkim", "google", "selector1", "selector2",
            "k1", "s1", "smtp", "brevo", "sendgrid", "mailgun", "mandrill"
    );

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full diagnostic run on a domain + SMTP server.
     *
     * @param request  the domain and SMTP credentials to test
     * @return         structured test report
     */
    public SmtpDomainTestReport test(SmtpDomainTestRequest request) {
        String domain = extractDomain(request.getSenderEmail());
        log.info("SmtpDomainTester — testing domain={} smtpHost={} port={}",
                domain, request.getSmtpHost(), request.getSmtpPort());

        // Perform all checks
        DnsCheckResult   mxResult      = checkMx(domain);
        DnsCheckResult   spfResult     = checkSpf(domain);
        DnsCheckResult   dkimResult    = checkDkim(domain, request.getDkimSelector());
        DnsCheckResult   dmarcResult   = checkDmarc(domain);
        DnsCheckResult   smtpMxResult  = checkSmtpMxConnect(domain);
        SmtpAuthResult   authResult    = checkSmtpAuth(request);
        TestSendResult   sendResult    = null;

        if (StringUtils.hasText(request.getTestRecipient()) && authResult.success()) {
            sendResult = sendTestMail(request, authResult);
        }

        return new SmtpDomainTestReport(
                domain,
                Instant.now().toString(),
                mxResult, spfResult, dkimResult, dmarcResult,
                smtpMxResult, authResult, sendResult
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DNS checks
    // ─────────────────────────────────────────────────────────────────────────

    private DnsCheckResult checkMx(String domain) {
        try {
            Lookup lookup = new Lookup(domain, Type.MX);
            lookup.run();
            if (lookup.getResult() == Lookup.SUCCESSFUL && lookup.getAnswers() != null) {
                List<String> hosts = new ArrayList<>();
                for (Record r : lookup.getAnswers()) {
                    if (r instanceof MXRecord mx) {
                        String host = mx.getTarget().toString();
                        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                        hosts.add(mx.getPriority() + " " + host);
                    }
                }
                if (!hosts.isEmpty()) {
                    return DnsCheckResult.pass("MX", "Found " + hosts.size() + " MX record(s): " + hosts);
                }
            }
            return DnsCheckResult.fail("MX", "No MX records found for " + domain + ". Domain cannot receive email.");
        } catch (Exception e) {
            return DnsCheckResult.error("MX", "DNS lookup failed: " + e.getMessage());
        }
    }

    private DnsCheckResult checkSpf(String domain) {
        try {
            Lookup lookup = new Lookup(domain, Type.TXT);
            lookup.run();
            if (lookup.getResult() == Lookup.SUCCESSFUL && lookup.getAnswers() != null) {
                for (Record r : lookup.getAnswers()) {
                    if (r instanceof TXTRecord txt) {
                        String value = String.join("", txt.getStrings());
                        if (value.toLowerCase().startsWith("v=spf1")) {
                            return DnsCheckResult.pass("SPF", "SPF record found: " + value);
                        }
                    }
                }
            }
            return DnsCheckResult.fail("SPF",
                    "No SPF record (v=spf1) found on " + domain + ". Mails may fail SPF check and land in spam.");
        } catch (Exception e) {
            return DnsCheckResult.error("SPF", "DNS lookup failed: " + e.getMessage());
        }
    }

    private DnsCheckResult checkDkim(String domain, String explicitSelector) {
        List<String> selectors = new ArrayList<>();
        if (StringUtils.hasText(explicitSelector)) selectors.add(explicitSelector.trim());
        selectors.addAll(DKIM_SELECTORS);

        List<String> found = new ArrayList<>();
        for (String selector : selectors) {
            String dkimDomain = selector + "._domainkey." + domain;
            try {
                Lookup lookup = new Lookup(dkimDomain, Type.TXT);
                lookup.run();
                if (lookup.getResult() == Lookup.SUCCESSFUL && lookup.getAnswers() != null) {
                    for (Record r : lookup.getAnswers()) {
                        if (r instanceof TXTRecord txt) {
                            String value = String.join("", txt.getStrings());
                            if (value.contains("p=") || value.contains("v=DKIM1")) {
                                found.add(selector + " → " + dkimDomain);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (!found.isEmpty()) {
            return DnsCheckResult.pass("DKIM", "DKIM public key found at: " + found);
        }
        return DnsCheckResult.fail("DKIM",
                "No DKIM TXT record found under " + domain +
                " (tried selectors: default, mail, google, brevo…). " +
                "Set up DKIM signing in your ESP or DNS panel.");
    }

    private DnsCheckResult checkDmarc(String domain) {
        try {
            String dmarcDomain = "_dmarc." + domain;
            Lookup lookup = new Lookup(dmarcDomain, Type.TXT);
            lookup.run();
            if (lookup.getResult() == Lookup.SUCCESSFUL && lookup.getAnswers() != null) {
                for (Record r : lookup.getAnswers()) {
                    if (r instanceof TXTRecord txt) {
                        String value = String.join("", txt.getStrings());
                        if (value.toLowerCase().startsWith("v=dmarc1")) {
                            return DnsCheckResult.pass("DMARC", "DMARC record found: " + value);
                        }
                    }
                }
            }
            return DnsCheckResult.fail("DMARC",
                    "No DMARC record at " + dmarcDomain + ". " +
                    "Add TXT: \"v=DMARC1; p=none; rua=mailto:dmarc@" + domain + "\"");
        } catch (Exception e) {
            return DnsCheckResult.error("DMARC", "DNS lookup failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMTP checks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Connects to port 25 of the domain's lowest-priority MX — simulates how
     * a receiving server would accept inbound mail for this domain.
     */
    private DnsCheckResult checkSmtpMxConnect(String domain) {
        String mxHost = resolvePrimaryMx(domain);
        if (mxHost == null) {
            return DnsCheckResult.fail("SMTP-MX-Connect",
                    "Cannot test: no MX record resolved for " + domain);
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(mxHost, 25), SMTP_TIMEOUT_MS);
            socket.setSoTimeout(SMTP_TIMEOUT_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String banner = readSmtpResponse(in);
            if (banner.startsWith("2")) {
                return DnsCheckResult.pass("SMTP-MX-Connect",
                        "Connected to MX " + mxHost + ":25 — banner: " + banner.lines().findFirst().orElse(""));
            }
            return DnsCheckResult.fail("SMTP-MX-Connect",
                    "MX " + mxHost + ":25 responded with error: " + banner.trim());
        } catch (Exception e) {
            return DnsCheckResult.fail("SMTP-MX-Connect",
                    "Cannot connect to MX " + mxHost + ":25 — " + e.getMessage() +
                    ". Check firewall / ISP blocks on port 25.");
        }
    }

    /**
     * Connects to the configured SMTP relay, sends EHLO + AUTH to verify credentials.
     * This is the definitive test that your SMTP server config works.
     */
    private SmtpAuthResult checkSmtpAuth(SmtpDomainTestRequest request) {
        if (!StringUtils.hasText(request.getSmtpHost())) {
            return new SmtpAuthResult(false, "No SMTP host configured", null, null);
        }
        String proto = request.isUseSsl() ? "smtps" : "smtp";
        Properties props = new Properties();
        props.put("mail." + proto + ".host",              request.getSmtpHost());
        props.put("mail." + proto + ".port",              String.valueOf(request.getSmtpPort()));
        props.put("mail." + proto + ".auth",              "true");
        props.put("mail." + proto + ".connectiontimeout", String.valueOf(SMTP_TIMEOUT_MS));
        props.put("mail." + proto + ".timeout",           String.valueOf(SMTP_TIMEOUT_MS));
        props.put("mail." + proto + ".ehlo",              "true");
        props.put("mail." + proto + ".allow8bitmime",     "true");
        props.put("mail.smtp.ssl.trust",                  "*");
        props.put("mail.smtp.ssl.checkserveridentity",    "false");

        if (request.isUseSsl()) {
            props.put("mail.smtps.ssl.enable",             "true");
            props.put("mail.smtps.ssl.checkserveridentity","false");
        } else {
            props.put("mail.smtp.starttls.enable",   "true");
            props.put("mail.smtp.starttls.required", "true");
        }

        String username = request.getSmtpUsername();
        String password = request.getSmtpPassword();

        try {
            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });
            session.setDebug(false);

            long start = System.currentTimeMillis();
            try (Transport transport = session.getTransport(proto)) {
                transport.connect(request.getSmtpHost(), request.getSmtpPort(), username, password);
                long ms = System.currentTimeMillis() - start;
                String detail = "Authenticated successfully to " + request.getSmtpHost() + ":" +
                        request.getSmtpPort() + " in " + ms + "ms";
                log.info("SmtpDomainTester auth OK: {}", detail);
                return new SmtpAuthResult(true, detail, session, proto);
            }
        } catch (Exception e) {
            String msg = "Auth failed on " + request.getSmtpHost() + ":" + request.getSmtpPort() +
                         " — " + e.getMessage();
            log.warn("SmtpDomainTester auth FAIL: {}", msg);
            return new SmtpAuthResult(false, msg, null, null);
        }
    }

    /**
     * Sends a real test message through the authenticated SMTP session.
     * Only called when testRecipient is set and auth succeeded.
     */
    private TestSendResult sendTestMail(SmtpDomainTestRequest request, SmtpAuthResult authResult) {
        try {
            Session session = authResult.session();
            String proto   = authResult.proto();

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(request.getSenderEmail()));
            msg.setRecipients(jakarta.mail.Message.RecipientType.TO,
                    InternetAddress.parse(request.getTestRecipient()));
            msg.setSubject("[SMTP Test] Delivery check from " + request.getSmtpHost(), "UTF-8");
            msg.setHeader("X-Mailer",     "MailSenders/SmtpTester");
            msg.setHeader("MIME-Version", "1.0");
            msg.setText(
                "This is an automated delivery test.\n\n" +
                "SMTP Host : " + request.getSmtpHost() + ":" + request.getSmtpPort() + "\n" +
                "Sender    : " + request.getSenderEmail() + "\n" +
                "Domain    : " + extractDomain(request.getSenderEmail()) + "\n" +
                "Sent at   : " + Instant.now() + "\n\n" +
                "If you received this, your SMTP configuration is working correctly.",
                "UTF-8");
            msg.setSentDate(new Date());

            try (Transport transport = session.getTransport(proto)) {
                transport.connect(request.getSmtpHost(), request.getSmtpPort(),
                        request.getSmtpUsername(), request.getSmtpPassword());
                transport.sendMessage(msg, msg.getAllRecipients());
            }

            String result = "Test mail sent to " + request.getTestRecipient() + " — check inbox/spam";
            log.info("SmtpDomainTester send OK: {}", result);
            return new TestSendResult(true, result);

        } catch (Exception e) {
            String msg = "Test send failed: " + e.getMessage();
            log.warn("SmtpDomainTester send FAIL: {}", msg);
            return new TestSendResult(false, msg);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String resolvePrimaryMx(String domain) {
        try {
            Lookup lookup = new Lookup(domain, Type.MX);
            lookup.run();
            if (lookup.getResult() != Lookup.SUCCESSFUL || lookup.getAnswers() == null) return null;
            return Arrays.stream(lookup.getAnswers())
                    .filter(r -> r instanceof MXRecord)
                    .map(r -> (MXRecord) r)
                    .min(Comparator.comparingInt(MXRecord::getPriority))
                    .map(mx -> {
                        String h = mx.getTarget().toString();
                        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
                    })
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String readSmtpResponse(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            sb.append(line).append("\n");
            if (line.length() >= 4 && line.charAt(3) == ' ') break;
        }
        return sb.toString();
    }

    private static String extractDomain(String email) {
        if (email == null || !email.contains("@")) return email != null ? email : "";
        return email.substring(email.indexOf('@') + 1).toLowerCase().trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data transfer records
    // ─────────────────────────────────────────────────────────────────────────

    public record DnsCheckResult(String name, String status, String detail) {
        public static DnsCheckResult pass(String name, String detail) {
            return new DnsCheckResult(name, "PASS", detail);
        }
        public static DnsCheckResult fail(String name, String detail) {
            return new DnsCheckResult(name, "FAIL", detail);
        }
        public static DnsCheckResult error(String name, String detail) {
            return new DnsCheckResult(name, "ERROR", detail);
        }
        public boolean passed() { return "PASS".equals(status); }
    }

    public record SmtpAuthResult(boolean success, String detail, Session session, String proto) {}

    public record TestSendResult(boolean success, String detail) {}
}
