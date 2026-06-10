package xy.mailsenders.sender.analyzer;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import xy.mailsenders.sender.analyzer.checks.*;

import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Sender Spam Analyzer — orchestrates all checks.
 *
 * Given your SMTP server credentials (exactly as you use for sending),
 * this service runs every available check and returns a SenderAnalysisReport
 * that explains WHY emails might be landing in spam/junk/promotions.
 *
 * Checks performed (in order):
 *
 *   Category 1 — DNS Authentication
 *     ① MX record presence
 *     ② SPF record + mechanism analysis (lookup count, +all, ~all, -all)
 *     ③ DKIM public key (20+ selectors probed)
 *     ④ DMARC record + policy strength
 *     ⑤ BIMI brand logo record
 *
 *   Category 2 — IP & Domain Blacklists (DNSBL)
 *     ⑥  SMTP IP resolved
 *     ⑦  IP vs Spamhaus ZEN (Gmail/Outlook/Yahoo use this)
 *     ⑧  IP vs Barracuda BRBL
 *     ⑨  IP vs SpamCop
 *     ⑩  IP vs SORBS DNSBL
 *     ⑪  IP vs SORBS Spam
 *     ⑫  IP vs PSBL
 *     ⑬  IP vs UCEPROTECT L1
 *     ⑭  IP vs Mailspike BL
 *     ⑮  IP vs Truncate (GBUdb)
 *     ⑯  IP vs Abuse.ch
 *     ⑰  IP vs INPS DNSBL
 *     ⑱  IP vs CBL (Composite BL)
 *     ⑲  Domain vs Spamhaus DBL
 *     ⑳  Domain vs URIBL Black
 *     ㉑  Domain vs URIBL Multi
 *     ㉒  Domain vs SURBL
 *
 *   Category 3 — Reverse DNS (PTR)
 *     ㉓  PTR record exists for SMTP IP
 *     ㉔  Forward-confirmed reverse DNS (FCrDNS)
 *     ㉕  PTR matches SMTP hostname
 *     ㉖  Dynamic/residential IP detection
 *
 *   Category 4 — SMTP Server Health
 *     ㉗  SMTP connection (host:port reachable)
 *     ㉘  SMTP 220 banner content
 *     ㉙  Banner hostname vs configured host
 *     ㉚  EHLO / ESMTP support
 *     ㉛  STARTTLS advertisement
 *     ㉜  AUTH mechanisms
 *     ㉝  AUTH PLAIN without TLS (security risk)
 *     ㉞  Open relay vulnerability
 *     ㉟  SMTP authentication with provided credentials
 *
 *   Category 5 — Reputation & Config Advice
 *     ㊱  SMTP port selection (25/465/587)
 *     ㊲  From/SMTP domain alignment (SPF alignment)
 *     ㊳  Sender role address detection
 *     ㊴  Consumer domain detection (gmail.com, etc.)
 *     ㊵  Gmail/Yahoo List-Unsubscribe requirement (Feb 2024)
 *     ㊶  Gmail spam rate threshold
 *     ㊷  IP warming guidance
 */
@Slf4j
@Service
@AllArgsConstructor
public class SenderAnalyzerService {

    private final DnsAuthChecker    dnsAuthChecker;
    private final BlacklistChecker  blacklistChecker;
    private final ReverseDnsChecker reverseDnsChecker;
    private final SmtpServerChecker smtpServerChecker;
    private final ReputationAdvisor reputationAdvisor;

    public SenderAnalysisReport analyze(SenderAnalysisRequest request) {
        String domain    = extractDomain(request.getSenderEmail());
        String smtpHost  = request.getSmtpHost();
        String resolvedIp = resolveIp(smtpHost);

        log.info("SenderAnalyzer — start: domain={} smtpHost={} ip={}",
                domain, smtpHost, resolvedIp);

        List<CheckItem> all = new ArrayList<>();

        // 1 — DNS authentication records
        all.addAll(dnsAuthChecker.run(domain, request.getDkimSelector()));

        // 2 — Blacklists (IP + domain)
        all.addAll(blacklistChecker.run(smtpHost, domain));

        // 3 — Reverse DNS
        all.addAll(reverseDnsChecker.run(smtpHost, String.valueOf(request.getSmtpPort())));

        // 4 — SMTP server health
        all.addAll(smtpServerChecker.run(
                smtpHost,
                request.getSmtpPort(),
                request.getSmtpUsername(),
                request.getSmtpPassword(),
                request.isUseSsl()));

        // 5 — Reputation & configuration advice
        all.addAll(reputationAdvisor.run(
                request.getSenderEmail(),
                smtpHost,
                request.getSmtpPort(),
                request.isUseSsl()));

        log.info("SenderAnalyzer — done: {} checks, score={}",
                all.size(), new SenderAnalysisReport(domain, smtpHost, resolvedIp,
                        Instant.now().toString(), all).getDeliverabilityScore());

        return new SenderAnalysisReport(domain, smtpHost, resolvedIp,
                Instant.now().toString(), all);
    }

    private static String extractDomain(String email) {
        if (email == null || !email.contains("@")) return email != null ? email : "unknown";
        return email.substring(email.indexOf('@') + 1).toLowerCase().trim();
    }

    private static String resolveIp(String host) {
        if (host == null) return null;
        try { return InetAddress.getByName(host).getHostAddress(); }
        catch (Exception e) { return null; }
    }
}
