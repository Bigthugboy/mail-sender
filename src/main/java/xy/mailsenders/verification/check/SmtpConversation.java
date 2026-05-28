package xy.mailsenders.verification.check;

import lombok.extern.slf4j.Slf4j;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Comparator;
import java.util.List;

/**
 * Performs a minimal SMTP conversation to probe a mailbox.
 *
 * Steps: connect → EHLO → MAIL FROM → RCPT TO → QUIT
 * No message is ever sent. The RCPT TO response tells us if the mailbox exists.
 *
 * DRY: shared by SmtpProbeCheck and CatchAllCheck — one implementation.
 * SRP: only speaks SMTP — no scoring, no throttling, no check logic.
 */
@Slf4j
public class SmtpConversation {

    private static final int    TIMEOUT_MS  = 8_000;
    private static final int    SMTP_PORT   = 25;
    private static final String HELO_DOMAIN = "verify.mailcheck.io";
    private static final String PROBE_FROM  = "verify@mailcheck.io";

    public enum Outcome { ACCEPTED, REJECTED, CATCH_ALL_LIKELY, ERROR }

    /**
     * Probes whether {@code targetEmail} exists on its MX server.
     *
     * @param targetEmail  the address to verify
     * @param canaryEmail  a made-up address on the same domain — used to detect catch-all.
     *                     Pass {@code null} to skip catch-all detection.
     */
    public static SmtpProbeResult probe(String targetEmail, String canaryEmail) {
        String domain = domainOf(targetEmail);
        String mxHost = resolveMx(domain);
        if (mxHost == null)
            return new SmtpProbeResult(Outcome.ERROR, "No MX resolved for " + domain);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(mxHost, SMTP_PORT), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

            // Read greeting
            String greeting = readResponse(in);
            if (!greeting.startsWith("2"))
                return new SmtpProbeResult(Outcome.ERROR, "Bad greeting: " + greeting.trim());

            // EHLO
            out.println("EHLO " + HELO_DOMAIN);
            String ehlo = readResponse(in);
            if (!ehlo.startsWith("2"))
                return new SmtpProbeResult(Outcome.ERROR, "EHLO rejected: " + ehlo.trim());

            // MAIL FROM
            out.println("MAIL FROM:<" + PROBE_FROM + ">");
            String mailFrom = readResponse(in);
            if (!mailFrom.startsWith("2")) {
                quit(out, in);
                return new SmtpProbeResult(Outcome.ERROR, "MAIL FROM rejected: " + mailFrom.trim());
            }

            // Canary probe first (catch-all detection)
            boolean catchAll = false;
            if (canaryEmail != null) {
                out.println("RCPT TO:<" + canaryEmail + ">");
                String canaryResp = readResponse(in);
                catchAll = canaryResp.startsWith("2");
                // Reset for the real probe
                out.println("RSET");
                readResponse(in);
                out.println("MAIL FROM:<" + PROBE_FROM + ">");
                readResponse(in);
            }

            // Real RCPT TO probe
            out.println("RCPT TO:<" + targetEmail + ">");
            String rcpt = readResponse(in);
            quit(out, in);

            if (catchAll)
                return new SmtpProbeResult(Outcome.CATCH_ALL_LIKELY,
                        "Domain accepts all addresses (catch-all)");
            if (rcpt.startsWith("2"))
                return new SmtpProbeResult(Outcome.ACCEPTED, "Mailbox exists");
            return new SmtpProbeResult(Outcome.REJECTED,
                    "RCPT TO rejected (" + rcpt.substring(0, Math.min(3, rcpt.length())) + "): " + rcpt.trim());

        } catch (Exception e) {
            log.debug("SMTP probe error for {}: {}", targetEmail, e.getMessage());
            return new SmtpProbeResult(Outcome.ERROR, "Connection error: " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Reads a full SMTP response (may span multiple lines with continuation dashes). */
    private static String readResponse(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            sb.append(line).append("\n");
            if (line.length() >= 4 && line.charAt(3) == ' ') break; // last line
        }
        return sb.toString();
    }

    private static void quit(PrintWriter out, BufferedReader in) {
        try { out.println("QUIT"); readResponse(in); } catch (Exception ignored) {}
    }

    static String domainOf(String email) {
        if (email == null || !email.contains("@")) return "";
        return email.substring(email.indexOf('@') + 1).toLowerCase().trim();
    }

    /** Resolves the lowest-priority MX for the domain. */
    static String resolveMx(String domain) {
        try {
            Lookup lookup = new Lookup(domain, Type.MX);
            lookup.run();
            if (lookup.getResult() != Lookup.SUCCESSFUL || lookup.getAnswers() == null)
                return null;
            String lowest = List.of(lookup.getAnswers()).stream()
                    .filter(r -> r instanceof MXRecord)
                    .map(r -> (MXRecord) r)
                    .min(Comparator.comparingInt(MXRecord::getPriority))
                    .map(MXRecord::getTarget)
                    .map(Name::toString)
                    .map(s -> s.endsWith(".") ? s.substring(0, s.length() - 1) : s)
                    .map(Object::toString)
                    .orElse(null);
            return lowest != null ? lowest.toString() : null;
        } catch (Exception e) {
            log.debug("MX resolve error for {}: {}", domain, e.getMessage());
            return null;
        }
    }

    public record SmtpProbeResult(Outcome outcome, String detail) {}
}
