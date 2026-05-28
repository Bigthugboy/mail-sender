package xy.mailsenders.service;

import org.springframework.stereotype.Service;
import xy.mailsenders.mail.domain.BulkMailResult;
import xy.mailsenders.mail.domain.MailFailure;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory metrics store — survives the process lifetime.
 * Replace with Redis / JPA for persistence across restarts.
 *
 * SRP : stores and serves campaign metrics only.
 * DRY : formatting in one place (FMT constant).
 */
@Service
public class MetricsService {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final int MAX_CAMPAIGNS = 100;

    private final AtomicLong totalRequested = new AtomicLong();
    private final AtomicLong totalSent      = new AtomicLong();
    private final AtomicLong totalFailed    = new AtomicLong();
    private final AtomicLong totalCampaigns = new AtomicLong();

    private final Deque<Map<String, Object>> campaigns = new ArrayDeque<>();

    // ── write ─────────────────────────────────────────────────────────────────

    public void record(String campaignName, BulkMailResult result) {
        totalCampaigns.incrementAndGet();
        totalRequested.addAndGet(result.getRequestedRecipients());
        totalSent.addAndGet(result.getSentCount());
        totalFailed.addAndGet(result.getFailedCount());

        int unique   = result.getUniqueRecipients();
        int sent     = result.getSentCount();
        double rate  = unique == 0 ? 0.0 : Math.round(sent * 1000.0 / unique) / 10.0;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id",           UUID.randomUUID().toString().substring(0, 8));
        entry.put("name",         campaignName != null && !campaignName.isBlank() ? campaignName : "(untitled)");
        entry.put("sentAt",       FMT.format(Instant.now()));
        entry.put("epochMs",      System.currentTimeMillis());   // for chart ordering
        entry.put("requested",    result.getRequestedRecipients());
        entry.put("unique",       unique);
        entry.put("sent",         sent);
        entry.put("failed",       result.getFailedCount());
        entry.put("deliveryRate", rate);
        entry.put("failures",     buildFailureList(result));

        synchronized (campaigns) {
            if (campaigns.size() >= MAX_CAMPAIGNS) campaigns.pollFirst();
            campaigns.addLast(entry);
        }
    }

    // ── read ──────────────────────────────────────────────────────────────────

    public Map<String, Object> getSummary() {
        long req    = totalRequested.get();
        long sent   = totalSent.get();
        long failed = totalFailed.get();
        double rate = req == 0 ? 0.0 : Math.round(sent * 1000.0 / req) / 10.0;

        return Map.of(
                "totalCampaigns",      totalCampaigns.get(),
                "totalRequested",      req,
                "totalSent",           sent,
                "totalFailed",         failed,
                "overallDeliveryRate", rate
        );
    }

    public List<Map<String, Object>> getCampaigns() {
        synchronized (campaigns) {
            List<Map<String, Object>> list = new ArrayList<>(campaigns);
            Collections.reverse(list);   // newest first
            return list;
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    private List<Map<String, String>> buildFailureList(BulkMailResult result) {
        List<Map<String, String>> list = new ArrayList<>();
        if (result.getFailures() == null) return list;
        for (MailFailure f : result.getFailures()) {
            list.add(Map.of(
                    "recipient", f.getRecipient() != null ? f.getRecipient() : "",
                    "reason",    f.getReason()    != null ? f.getReason()    : "unknown"
            ));
        }
        return list;
    }
}
