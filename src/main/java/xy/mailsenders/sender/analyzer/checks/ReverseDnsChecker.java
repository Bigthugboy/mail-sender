package xy.mailsenders.sender.analyzer.checks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import xy.mailsenders.sender.analyzer.CheckItem;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Reverse DNS (PTR) Checker.
 *
 * A missing or mismatched PTR record is one of the most common reasons
 * emails land in spam — Gmail, Outlook, and many others check this.
 *
 * Checks:
 *  1. PTR record exists for the SMTP server IP
 *  2. Forward-confirmed reverse DNS (FCrDNS) — PTR → A resolves back to same IP
 *  3. PTR hostname matches the SMTP EHLO/HELO domain
 *  4. PTR hostname is not a generic dynamic IP pattern (e.g. "1-2-3-4.dynamic.isp.com")
 */
@Slf4j
@Component
public class ReverseDnsChecker {

    private static final String CAT = "Reverse DNS";
    private static final int SMTP_TIMEOUT_MS = 8_000;

    // Patterns that indicate a dynamic/residential IP (very bad for reputation)
    private static final List<String> DYNAMIC_IP_PATTERNS = List.of(
            "dynamic", "dyn", "dhcp", "pool", "ppp", "cable", "broadband",
            "adsl", "dialup", "residential", "client", "cust", "user", "home"
    );

    public List<CheckItem> run(String smtpHost, String smtpPort) {
        List<CheckItem> results = new ArrayList<>();

        String ip = resolveIp(smtpHost);
        if (ip == null) {
            results.add(CheckItem.fail("PTR: IP Resolution", CAT, "HIGH",
                    "Cannot resolve SMTP host '" + smtpHost + "' to an IP.",
                    "Verify your SMTP hostname is correct.", null));
            return results;
        }

        // 1 — PTR record
        String ptr = resolvePtr(ip);
        if (ptr == null) {
            results.add(CheckItem.fail("PTR Record", CAT, "CRITICAL",
                    "No PTR (reverse DNS) record found for IP " + ip + ".\n" +
                    "Gmail, Outlook, and many spam filters require a valid PTR record.",
                    "Ask your hosting/VPS provider or ISP to set a PTR record for " + ip +
                    " pointing to " + smtpHost + ". This is set by the IP owner, not in your DNS.", ip));
            return results;
        }

        results.add(CheckItem.pass("PTR Record", CAT,
                "PTR record found: " + ip + " → " + ptr, ptr));

        // 2 — Forward-confirmed reverse DNS (FCrDNS)
        String fwdIp = resolveIp(ptr);
        if (fwdIp == null || !fwdIp.equals(ip)) {
            results.add(CheckItem.fail("FCrDNS (PTR→A match)", CAT, "HIGH",
                    "PTR resolves to " + ptr + " but " + ptr +
                    " does not resolve back to " + ip + " (resolves to: " + fwdIp + ").\n" +
                    "This mismatch causes many spam filters to reject or flag your email.",
                    "Ensure " + ptr + " has an A record pointing to " + ip +
                    " (forward-confirmed reverse DNS).", ptr));
        } else {
            results.add(CheckItem.pass("FCrDNS (PTR→A match)", CAT,
                    "Forward-confirmed: " + ptr + " → " + fwdIp + " ✓", fwdIp));
        }

        // 3 — PTR matches SMTP hostname
        if (!ptr.equalsIgnoreCase(smtpHost) && !ptr.endsWith("." + smtpHost)) {
            results.add(CheckItem.warn("PTR matches SMTP host", CAT, "MEDIUM",
                    "PTR record (" + ptr + ") does not match your SMTP host (" + smtpHost + ").\n" +
                    "Some spam filters flag this mismatch.",
                    "Ideally your PTR should be the same as your SMTP hostname, or a subdomain of it.",
                    ptr + " ≠ " + smtpHost));
        } else {
            results.add(CheckItem.pass("PTR matches SMTP host", CAT,
                    "PTR hostname matches SMTP host (" + ptr + ")", ptr));
        }

        // 4 — Dynamic/residential IP detection
        String ptrLower = ptr.toLowerCase();
        boolean isDynamic = DYNAMIC_IP_PATTERNS.stream().anyMatch(ptrLower::contains);
        if (isDynamic) {
            results.add(CheckItem.fail("Dynamic IP Detection", CAT, "CRITICAL",
                    "PTR record '" + ptr + "' looks like a dynamic/residential IP.\n" +
                    "Major providers (Gmail, Outlook, Yahoo) block residential IPs by default — " +
                    "they are associated with compromised home computers sending spam.",
                    "Use a dedicated VPS or cloud server (DigitalOcean, Vultr, Hetzner, AWS EC2) " +
                    "with a static IP for your SMTP server. Residential/dynamic IPs cannot be " +
                    "used for reliable email delivery.", ptr));
        } else {
            results.add(CheckItem.pass("Dynamic IP Detection", CAT,
                    "PTR hostname does not match dynamic/residential IP patterns.", ptr));
        }

        return results;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String resolveIp(String host) {
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (Exception e) {
            log.debug("Cannot resolve {}: {}", host, e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the PTR (reverse DNS) for an IPv4 address.
     * Constructs the in-addr.arpa query: "1.2.3.4" → "4.3.2.1.in-addr.arpa"
     */
    private String resolvePtr(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return null; // IPv6 not implemented
            String arpa = parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0] + ".in-addr.arpa";
            Lookup lookup = new Lookup(arpa, Type.PTR);
            lookup.run();
            if (lookup.getResult() == Lookup.SUCCESSFUL && lookup.getAnswers() != null) {
                for (Record r : lookup.getAnswers()) {
                    if (r instanceof PTRRecord ptr) {
                        String name = ptr.getTarget().toString();
                        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("PTR lookup error for {}: {}", ip, e.getMessage());
            return null;
        }
    }
}
