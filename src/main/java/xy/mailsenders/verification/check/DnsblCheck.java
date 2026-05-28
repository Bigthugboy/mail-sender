package xy.mailsenders.verification.check;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xbill.DNS.*;
import xy.mailsenders.verification.domain.CheckResult;
import xy.mailsenders.verification.domain.CheckType;

import java.net.InetAddress;
import java.util.List;


/**
 * Check 5 — DNSBL (DNS Blackhole List) check.
 *
 * Queries the domain against the major public blacklists.
 * A listed domain/IP means it has a history of sending spam — your mail
 * will be rejected or junked if sent to or from a blacklisted address.
 *
 * Protocol: reverse the IP octets + append the DNSBL zone, do an A lookup.
 * If it resolves → listed. If NXDOMAIN → clean.
 *
 * Network I/O: one DNS query per DNSBL zone queried.
 */
@Slf4j
@Component
public class DnsblCheck implements EmailCheck {

    private static final List<String> DNSBL_ZONES = List.of(
            "zen.spamhaus.org",       // Spamhaus — the gold standard
            "bl.spamcop.net",         // SpamCop
            "b.barracudacentral.org", // Barracuda
            "dnsbl.sorbs.net"         // SORBS
    );

    @Override
    public CheckResult run(String email) {
        String domain = DisposableCheck.domainOf(email);
        if (domain.isBlank())
            return CheckResult.fail(CheckType.DNSBL, "Cannot extract domain: " + email);

        try {
            // Resolve domain → IP, then check the IP against DNSBLs
            InetAddress[] addresses = InetAddress.getAllByName(domain);
            for (InetAddress addr : addresses) {
                String listed = checkIp(addr.getHostAddress());
                if (listed != null)
                    return CheckResult.fail(CheckType.DNSBL,
                            "Domain IP listed on blacklist " + listed + " — high spam risk");
            }
        } catch (Exception e) {
            // Domain doesn't resolve — MxRecordCheck already catches this,
            // treat as clean here to avoid double-penalising.
            log.debug("DNSBL: could not resolve {} — skipping: {}", domain, e.getMessage());
        }
        return CheckResult.pass(CheckType.DNSBL);
    }

    /**
     * Returns the DNSBL zone name if the IP is listed, null if clean.
     * Uses the standard reversed-IP lookup format: 4.3.2.1.zen.spamhaus.org
     */
    private String checkIp(String ip) {
        if (!ip.contains(".")) return null; // skip IPv6 — most DNSBLs are IPv4-only
        String reversed = reverseIp(ip);
        for (String zone : DNSBL_ZONES) {
            try {
                String query = reversed + "." + zone;
                Lookup lookup = new Lookup(query, Type.A);
                lookup.run();
                if (lookup.getResult() == Lookup.SUCCESSFUL) {
                    log.warn("IP {} listed on DNSBL: {}", ip, zone);
                    return zone;
                }
            } catch (Exception e) {
                log.debug("DNSBL query error for {} on {}: {}", ip, zone, e.getMessage());
            }
        }
        return null;
    }

    private String reverseIp(String ip) {
        String[] parts = ip.split("\\.");
        return parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0];
    }
}
