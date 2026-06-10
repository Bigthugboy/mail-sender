package xy.mailsenders.sender.analyzer.checks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import xy.mailsenders.sender.analyzer.CheckItem;

import java.util.ArrayList;
import java.util.List;

/**
 * DNS Authentication Checker.
 *
 * Checks every DNS record that affects whether your emails pass authentication
 * at the receiving mail server (Gmail, Outlook, Yahoo…).
 *
 * Checks:
 *  1. MX  — domain can receive replies
 *  2. SPF — authorised sending IP list (v=spf1)
 *  3. DKIM — DKIM public key presence (tries 15+ selectors)
 *  4. DMARC — policy at _dmarc.<domain>
 *  5. BIMI — brand logo indicator (nice-to-have, boosts trust score)
 *  6. SPF include depth — detects "too many DNS lookups" error (max 10)
 *  7. DMARC policy strength — none/quarantine/reject
 *  8. SPF all mechanism — ~all (soft) vs -all (hard) vs +all (open relay!)
 */
@Slf4j
@Component
public class DnsAuthChecker {

    private static final String CAT = "DNS Authentication";

    // Common DKIM selectors used by popular ESPs + providers
    private static final List<String> DKIM_SELECTORS = List.of(
            "default", "mail", "email", "dkim", "google", "selector1", "selector2",
            "k1", "s1", "s2", "smtp", "brevo", "sendgrid", "mailgun",
            "mandrill", "ses", "postmark", "sparkpost", "protonmail", "zoho"
    );

    public List<CheckItem> run(String domain, String explicitDkimSelector) {
        List<CheckItem> results = new ArrayList<>();

        results.add(checkMx(domain));
        results.add(checkSpf(domain));
        results.addAll(checkSpfMechanism(domain));
        results.add(checkDkim(domain, explicitDkimSelector));
        results.add(checkDmarc(domain));
        results.add(checkDmarcPolicy(domain));
        results.add(checkBimi(domain));

        return results;
    }

    // ── MX ───────────────────────────────────────────────────────────────────

    private CheckItem checkMx(String domain) {
        try {
            Lookup lookup = new Lookup(domain, Type.MX);
            lookup.run();
            if (lookup.getResult() == Lookup.SUCCESSFUL && lookup.getAnswers() != null) {
                StringBuilder sb = new StringBuilder();
                for (Record r : lookup.getAnswers()) {
                    if (r instanceof MXRecord mx) {
                        String h = mx.getTarget().toString();
                        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
                        sb.append(mx.getPriority()).append(" ").append(h).append(", ");
                    }
                }
                if (!sb.isEmpty()) {
                    String val = sb.toString().replaceAll(", $", "");
                    return CheckItem.pass("MX Record", CAT,
                            "Domain has MX records — can receive replies.", val);
                }
            }
            return CheckItem.fail("MX Record", CAT, "MEDIUM",
                    "No MX record found on " + domain + ". Recipients cannot reply to your emails.",
                    "Add an MX record pointing to your mail server so replies work.", null);
        } catch (Exception e) {
            return CheckItem.error("MX Record", CAT, "DNS lookup error: " + e.getMessage());
        }
    }

    // ── SPF ──────────────────────────────────────────────────────────────────

    private CheckItem checkSpf(String domain) {
        try {
            String spf = findTxtStartingWith(domain, "v=spf1");
            if (spf != null) {
                return CheckItem.pass("SPF Record", CAT,
                        "SPF record found — defines which servers can send on behalf of " + domain, spf);
            }
            return CheckItem.fail("SPF Record", CAT, "CRITICAL",
                    "No SPF record found on " + domain + ". Without SPF, receiving servers have no way to " +
                    "verify your email is authorised — extremely high spam risk.",
                    "Add a TXT record: \"v=spf1 include:your-smtp-provider.com ~all\"\n" +
                    "Example for 1free.fr: \"v=spf1 mx a ~all\"", null);
        } catch (Exception e) {
            return CheckItem.error("SPF Record", CAT, "DNS error: " + e.getMessage());
        }
    }

    private List<CheckItem> checkSpfMechanism(String domain) {
        List<CheckItem> out = new ArrayList<>();
        try {
            String spf = findTxtStartingWith(domain, "v=spf1");
            if (spf == null) return out; // already reported above

            // Check for +all (open relay — very bad)
            if (spf.contains("+all")) {
                out.add(CheckItem.fail("SPF: +all mechanism", CAT, "CRITICAL",
                        "Your SPF record uses \"+all\" which allows ANY server to send as you — open relay!",
                        "Change \"+all\" to \"~all\" (soft fail) or \"-all\" (hard fail) immediately.", spf));
            }
            // Check for ~all vs -all
            else if (spf.contains("-all")) {
                out.add(CheckItem.pass("SPF: -all mechanism", CAT,
                        "Strict SPF: \"-all\" hard-fails unauthorised senders (best).", "-all"));
            } else if (spf.contains("~all")) {
                out.add(CheckItem.warn("SPF: ~all mechanism", CAT, "LOW",
                        "SPF uses \"~all\" (soft fail) — unauthorised senders are tagged but not rejected.",
                        "Consider changing to \"-all\" for stricter enforcement once SPF is confirmed correct.",
                        "~all"));
            } else if (spf.contains("?all")) {
                out.add(CheckItem.warn("SPF: ?all mechanism", CAT, "MEDIUM",
                        "SPF uses \"?all\" (neutral) — provides no protection against spoofing.",
                        "Change to \"~all\" or \"-all\".", "?all"));
            }

            // Count DNS lookups (limit is 10 per RFC 7208)
            long lookups = java.util.Arrays.stream(spf.split("\\s+"))
                    .filter(t -> t.startsWith("include:") || t.startsWith("a") ||
                                 t.startsWith("mx") || t.startsWith("ptr") ||
                                 t.startsWith("exists:") || t.startsWith("redirect="))
                    .count();
            if (lookups > 8) {
                out.add(CheckItem.fail("SPF: DNS lookup count", CAT, "HIGH",
                        "SPF record triggers ~" + lookups + " DNS lookups. RFC 7208 limits SPF to 10 lookups " +
                        "max — exceeding this causes SPF to fail (\"permerror\").",
                        "Flatten your SPF record using a tool like dmarcian.com/spf-survey/", spf));
            }

        } catch (Exception e) {
            log.debug("SPF mechanism check failed for {}: {}", domain, e.getMessage());
        }
        return out;
    }

    // ── DKIM ─────────────────────────────────────────────────────────────────

    private CheckItem checkDkim(String domain, String explicitSelector) {
        List<String> selectors = new ArrayList<>();
        if (StringUtils.hasText(explicitSelector)) selectors.add(explicitSelector.trim());
        selectors.addAll(DKIM_SELECTORS);

        List<String> found = new ArrayList<>();
        for (String selector : selectors) {
            try {
                String dkimHost = selector + "._domainkey." + domain;
                Lookup lookup = new Lookup(dkimHost, Type.TXT);
                lookup.run();
                if (lookup.getResult() == Lookup.SUCCESSFUL && lookup.getAnswers() != null) {
                    for (Record r : lookup.getAnswers()) {
                        if (r instanceof TXTRecord txt) {
                            String val = String.join("", txt.getStrings());
                            if (val.contains("p=") || val.contains("v=DKIM1")) {
                                // Check for revoked key (empty p=)
                                if (val.contains("p=;") || val.contains("p= ")) {
                                    found.add(selector + " [REVOKED — key is empty!]");
                                } else {
                                    found.add(selector + " → " + dkimHost);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (!found.isEmpty()) {
            boolean hasRevoked = found.stream().anyMatch(s -> s.contains("REVOKED"));
            if (hasRevoked) {
                return CheckItem.warn("DKIM Record", CAT, "HIGH",
                        "DKIM key found but one or more selectors are revoked (empty p=).",
                        "Regenerate your DKIM key pair and update the DNS TXT record.",
                        String.join("; ", found));
            }
            return CheckItem.pass("DKIM Record", CAT,
                    "DKIM signing key published at " + found.size() + " selector(s). " +
                    "Emails signed with this key will pass DKIM verification.",
                    String.join("; ", found));
        }

        return CheckItem.fail("DKIM Record", CAT, "CRITICAL",
                "No DKIM public key found for " + domain +
                " (tried " + selectors.size() + " selectors: " +
                selectors.subList(0, Math.min(5, selectors.size())) + "…).\n" +
                "Without DKIM, emails are NOT cryptographically signed — major spam risk.",
                "Enable DKIM signing in your ESP (Brevo, Gmail, cPanel…) and publish the " +
                "provided TXT record in your DNS under <selector>._domainkey." + domain, null);
    }

    // ── DMARC ────────────────────────────────────────────────────────────────

    private CheckItem checkDmarc(String domain) {
        try {
            String dmarc = findTxtStartingWith("_dmarc." + domain, "v=DMARC1");
            if (dmarc != null) {
                return CheckItem.pass("DMARC Record", CAT,
                        "DMARC record found — defines what to do when SPF/DKIM fail.", dmarc);
            }
            return CheckItem.fail("DMARC Record", CAT, "HIGH",
                    "No DMARC record at _dmarc." + domain + ". Without DMARC:\n" +
                    "• Gmail and Yahoo bulk sender requirements are not met.\n" +
                    "• Spam filters distrust your domain more.\n" +
                    "• You get no visibility into spoofing attempts.",
                    "Add a TXT record at _dmarc." + domain + ":\n" +
                    "\"v=DMARC1; p=none; rua=mailto:dmarc@" + domain +
                    "; ruf=mailto:dmarc@" + domain + "; fo=1\"\n" +
                    "(Start with p=none to monitor, then move to p=quarantine or p=reject.)", null);
        } catch (Exception e) {
            return CheckItem.error("DMARC Record", CAT, "DNS error: " + e.getMessage());
        }
    }

    private CheckItem checkDmarcPolicy(String domain) {
        try {
            String dmarc = findTxtStartingWith("_dmarc." + domain, "v=DMARC1");
            if (dmarc == null) return CheckItem.skip("DMARC Policy", CAT, "No DMARC record — skipped.");

            if (dmarc.contains("p=reject")) {
                return CheckItem.pass("DMARC Policy", CAT,
                        "DMARC policy is 'reject' — the strictest level. Spoofed emails are rejected outright.",
                        "p=reject");
            } else if (dmarc.contains("p=quarantine")) {
                return CheckItem.pass("DMARC Policy", CAT,
                        "DMARC policy is 'quarantine' — spoofed emails go to spam/junk.", "p=quarantine");
            } else if (dmarc.contains("p=none")) {
                return CheckItem.warn("DMARC Policy", CAT, "LOW",
                        "DMARC policy is 'none' — you receive reports but spoofed emails are not blocked.",
                        "After reviewing DMARC reports for 2–4 weeks, upgrade to p=quarantine then p=reject.",
                        "p=none");
            }
            return CheckItem.warn("DMARC Policy", CAT, "MEDIUM",
                    "DMARC record found but policy value is missing or unrecognised.",
                    "Ensure your DMARC TXT includes p=none, p=quarantine, or p=reject.", dmarc);
        } catch (Exception e) {
            return CheckItem.error("DMARC Policy", CAT, "DNS error: " + e.getMessage());
        }
    }

    // ── BIMI ─────────────────────────────────────────────────────────────────

    private CheckItem checkBimi(String domain) {
        try {
            String bimi = findTxtStartingWith("default._bimi." + domain, "v=BIMI1");
            if (bimi != null) {
                return CheckItem.pass("BIMI Record", CAT,
                        "BIMI record found — your brand logo may appear in Gmail/Apple Mail inboxes.", bimi);
            }
            return CheckItem.warn("BIMI Record", CAT, "LOW",
                    "No BIMI record at default._bimi." + domain + ". Not required, but boosts brand trust.",
                    "After DMARC p=enforce is active, add BIMI to display your logo in Gmail and Apple Mail:\n" +
                    "TXT default._bimi." + domain + " \"v=BIMI1; l=https://your-logo-url.com/logo.svg\"", null);
        } catch (Exception e) {
            return CheckItem.skip("BIMI Record", CAT, "DNS lookup skipped: " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String findTxtStartingWith(String host, String prefix) throws Exception {
        Lookup lookup = new Lookup(host, Type.TXT);
        lookup.run();
        if (lookup.getResult() == Lookup.SUCCESSFUL && lookup.getAnswers() != null) {
            for (Record r : lookup.getAnswers()) {
                if (r instanceof TXTRecord txt) {
                    String val = String.join("", txt.getStrings());
                    if (val.toLowerCase().startsWith(prefix.toLowerCase())) return val;
                }
            }
        }
        return null;
    }
}
