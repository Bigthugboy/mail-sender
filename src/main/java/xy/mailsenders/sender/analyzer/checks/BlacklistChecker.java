package xy.mailsenders.sender.analyzer.checks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import xy.mailsenders.sender.analyzer.CheckItem;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * IP / Domain Blacklist Checker (DNSBL).
 *
 * Tests the SMTP server's IP and the sender domain against major spam blacklists.
 * Being listed on even one major blacklist causes near-100% spam classification.
 *
 * Blacklists checked:
 *
 *  CRITICAL (major providers use these):
 *   - Spamhaus ZEN      (zen.spamhaus.org)       — Gmail, Outlook, Yahoo use this
 *   - Spamhaus DBL      (dbl.spamhaus.org)        — domain blacklist
 *   - Barracuda BRBL    (b.barracudacentral.org)
 *   - SpamCop           (bl.spamcop.net)
 *
 *  HIGH:
 *   - SORBS DNSBL       (dnsbl.sorbs.net)
 *   - SORBS Spam        (spam.dnsbl.sorbs.net)
 *   - Invaluement ivmSIP (sip.invalidment.com)
 *   - Passive Spam Block (psbl.surriel.com)
 *   - UCEPROTECT L1     (dnsbl-1.uceprotect.net)
 *
 *  MEDIUM:
 *   - Mailspike BL      (bl.mailspike.net)
 *   - NordSpam          (combined.njabl.org)
 *   - Truncate          (truncate.gbudb.net)
 *   - Abuse.ch          (spam.abuse.ch)
 *   - MX Toolbox style  (dnsbl.inps.de)
 */
@Slf4j
@Component
public class BlacklistChecker {

    private static final String CAT = "IP Blacklists";
    private static final String DOMAIN_CAT = "Domain Blacklists";

    private record Blacklist(String host, String name, String severity, String infoUrl) {}

    private static final List<Blacklist> IP_BLACKLISTS = List.of(
        // CRITICAL
        new Blacklist("zen.spamhaus.org",       "Spamhaus ZEN",        "CRITICAL", "https://check.spamhaus.org/"),
        new Blacklist("b.barracudacentral.org",  "Barracuda BRBL",      "CRITICAL", "https://www.barracudacentral.org/lookups"),
        new Blacklist("bl.spamcop.net",          "SpamCop",             "CRITICAL", "https://www.spamcop.net/bl.shtml"),
        // HIGH
        new Blacklist("dnsbl.sorbs.net",         "SORBS DNSBL",         "HIGH",     "http://www.sorbs.net/lookup.shtml"),
        new Blacklist("spam.dnsbl.sorbs.net",    "SORBS Spam",          "HIGH",     "http://www.sorbs.net/lookup.shtml"),
        new Blacklist("psbl.surriel.com",        "PSBL",                "HIGH",     "https://psbl.org/"),
        new Blacklist("dnsbl-1.uceprotect.net",  "UCEPROTECT L1",       "HIGH",     "http://www.uceprotect.net/en/rblcheck.php"),
        // MEDIUM
        new Blacklist("bl.mailspike.net",        "Mailspike BL",        "MEDIUM",   "https://mailspike.org/"),
        new Blacklist("truncate.gbudb.net",      "Truncate (GBUdb)",    "MEDIUM",   "https://www.gbudb.com/truncate/"),
        new Blacklist("spam.abuse.ch",           "Abuse.ch",            "MEDIUM",   "https://abuse.ch/"),
        new Blacklist("dnsbl.inps.de",           "INPS DNSBL",          "MEDIUM",   "http://dnsbl.inps.de/"),
        new Blacklist("cbl.abuseat.org",         "CBL (Composite BL)",  "HIGH",     "https://www.abuseat.org/lookup.cgi")
    );

    private static final List<Blacklist> DOMAIN_BLACKLISTS = List.of(
        new Blacklist("dbl.spamhaus.org",        "Spamhaus DBL",        "CRITICAL", "https://check.spamhaus.org/"),
        new Blacklist("black.uribl.com",         "URIBL Black",         "CRITICAL", "https://uribl.com/"),
        new Blacklist("multi.uribl.com",         "URIBL Multi",         "HIGH",     "https://uribl.com/"),
        new Blacklist("surbl.org",               "SURBL",               "HIGH",     "https://www.surbl.org/")
    );

    /**
     * Checks the SMTP server IP and the sender domain against all blacklists.
     *
     * @param smtpHost   hostname of the SMTP relay (will be resolved to IP)
     * @param domain     sender domain extracted from the From address
     */
    public List<CheckItem> run(String smtpHost, String domain) {
        List<CheckItem> results = new ArrayList<>();

        // Resolve SMTP host → IP
        String smtpIp = resolveIp(smtpHost);
        results.add(smtpIp != null
                ? CheckItem.pass("SMTP Server IP", CAT,
                        "Resolved " + smtpHost + " → " + smtpIp, smtpIp)
                : CheckItem.fail("SMTP Server IP", CAT, "HIGH",
                        "Cannot resolve " + smtpHost + " to an IP address.",
                        "Verify your SMTP hostname is correct and DNS is working.", null));

        if (smtpIp != null) {
            // Check IP against all IP-based blacklists
            results.addAll(checkIpAgainstBlacklists(smtpIp, smtpHost));
        }

        // Check domain against domain-based blacklists
        results.addAll(checkDomainAgainstBlacklists(domain));

        return results;
    }

    // ── IP blacklist checks ───────────────────────────────────────────────────

    private List<CheckItem> checkIpAgainstBlacklists(String ip, String smtpHost) {
        List<CheckItem> results = new ArrayList<>();
        String reversed = reverseIp(ip);

        for (Blacklist bl : IP_BLACKLISTS) {
            String lookupHost = reversed + "." + bl.host();
            try {
                InetAddress.getByName(lookupHost); // resolves → listed
                results.add(CheckItem.fail(
                        "Blacklist: " + bl.name(), CAT, bl.severity(),
                        "⛔ IP " + ip + " (" + smtpHost + ") is LISTED on " + bl.name() + ".\n" +
                        "This alone can cause 100% of your emails to go to spam on servers using this list.",
                        "Request delisting at: " + bl.infoUrl() + "\n" +
                        "Also investigate why you were listed (spam complaints, open relay, etc.)", ip));
            } catch (java.net.UnknownHostException e) {
                // Not listed — good
                results.add(CheckItem.pass("Blacklist: " + bl.name(), CAT,
                        "IP " + ip + " is clean on " + bl.name(), "clean"));
            } catch (Exception e) {
                results.add(CheckItem.error("Blacklist: " + bl.name(), CAT,
                        "Lookup error: " + e.getMessage()));
            }
        }
        return results;
    }

    // ── Domain blacklist checks ───────────────────────────────────────────────

    private List<CheckItem> checkDomainAgainstBlacklists(String domain) {
        List<CheckItem> results = new ArrayList<>();
        for (Blacklist bl : DOMAIN_BLACKLISTS) {
            String lookupHost = domain + "." + bl.host();
            try {
                Lookup lookup = new Lookup(lookupHost, Type.A);
                lookup.run();
                if (lookup.getResult() == Lookup.SUCCESSFUL && lookup.getAnswers() != null) {
                    results.add(CheckItem.fail(
                            "Domain BL: " + bl.name(), DOMAIN_CAT, bl.severity(),
                            "⛔ Domain " + domain + " is listed on " + bl.name() + " (domain blacklist).\n" +
                            "Links and images from this domain in emails will be blocked.",
                            "Request delisting at: " + bl.infoUrl(), domain));
                } else {
                    results.add(CheckItem.pass("Domain BL: " + bl.name(), DOMAIN_CAT,
                            "Domain " + domain + " is clean on " + bl.name(), "clean"));
                }
            } catch (Exception e) {
                results.add(CheckItem.error("Domain BL: " + bl.name(), DOMAIN_CAT,
                        "Lookup error: " + e.getMessage()));
            }
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
     * Reverses IPv4 for DNSBL lookups.
     * e.g. "1.2.3.4" → "4.3.2.1"
     */
    private String reverseIp(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return ip; // IPv6 — skip (complex reversal)
        return parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0];
    }
}
