package xy.mailsenders.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import xy.mailsenders.mail.brevo.AnalyticsNotifier;
import xy.mailsenders.mail.brevo.MailSendingReport;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.domain.MailAttachment;
import xy.mailsenders.mail.domain.BulkMailResult;
import xy.mailsenders.mail.domain.MailFailure;
import xy.mailsenders.mail.domain.MailPayload;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class MailServiceImpl implements MailService {
    private static final Pattern SIMPLE_EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_CONCURRENCY = 100;
    private final MailSendingProperties properties;
    private final AnalyticsNotifier analyticsNotifier;
    private final ResendMailGateWay resendMailGateWay;


    @Override
    public BulkMailResult sendMails(Collection<MailPayload> mails) {
        if (mails == null || mails.isEmpty()) {
            throw new IllegalArgumentException("mails must not be empty");
        }
        if (mails.size() > properties.getMaxRecipientsPerRequest()) {
            throw new IllegalArgumentException("Maximum recipients per request is " + properties.getMaxRecipientsPerRequest());
        }

        Map<String, MailPayload> uniqueMails = deduplicateByRecipient(mails);

        // Validate all payloads up-front before sending anything
        uniqueMails.values().forEach(this::validatePayload);

        // Fire all requests concurrently — no sequential loop, no Thread.sleep()
        int concurrency = Math.min(uniqueMails.size(), MAX_CONCURRENCY);
        List<MailFailure> failures = resendMailGateWay.sendAll(uniqueMails.values(), concurrency);

        Set<String> failedRecipients = failures.stream()
                .map(MailFailure::getRecipient)
                .collect(Collectors.toSet());

        List<String> successfulEmails = uniqueMails.keySet().stream()
                .filter(email -> !failedRecipients.contains(email))
                .collect(Collectors.toList());

        List<String> failedEmails = new ArrayList<>(failedRecipients);

        int sentCount = uniqueMails.size() - failures.size();

        BulkMailResult result = BulkMailResult.builder()
                .requestedRecipients(mails.size())
                .uniqueRecipients(uniqueMails.size())
                .sentCount(sentCount)
                .failedCount(failures.size())
                .failures(failures)
                .build();

        sendAnalyticsReport(result, successfulEmails, failedEmails);

        return result;
    }

    private void sendAnalyticsReport(BulkMailResult result, List<String> successfulEmails, List<String> failedEmails) {
        MailSendingReport report = new MailSendingReport(
                result.getUniqueRecipients(),
                result.getSentCount(),
                result.getFailedCount(),
                successfulEmails,
                failedEmails,
                "Batch send completed. Total processed: " + result.getUniqueRecipients()
        );
        analyticsNotifier.sendReport(report);
    }

    private Map<String, MailPayload> deduplicateByRecipient(Collection<MailPayload> mails) {
        Map<String, MailPayload> deduplicated = new LinkedHashMap<>();
        for (MailPayload mail : mails) {
            if (mail == null || !StringUtils.hasText(mail.getTo())) {
                continue;
            }
            String key = mail.getTo().trim().toLowerCase();
            deduplicated.putIfAbsent(key, mail);
        }
        if (deduplicated.isEmpty()) {
            throw new IllegalArgumentException("No valid recipient found in request");
        }
        return deduplicated;
    }

    private void validatePayload(MailPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("mail payload must not be null");
        }
        if (!StringUtils.hasText(payload.getTo())) {
            throw new IllegalArgumentException("recipient address must not be blank");
        }
        if (!StringUtils.hasText(payload.getSubject())) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (!StringUtils.hasText(payload.getBody())) {
            throw new IllegalArgumentException("body must not be blank");
        }
        if (!SIMPLE_EMAIL_PATTERN.matcher(payload.getTo().trim()).matches()) {
            throw new IllegalArgumentException("invalid recipient address: " + payload.getTo());
        }
        if (!CollectionUtils.isEmpty(payload.getAttachments())) {
            for (MailAttachment attachment : payload.getAttachments()) {
                if (attachment == null) {
                    throw new IllegalArgumentException("attachment must not be null");
                }
                if (!StringUtils.hasText(attachment.getFileName())) {
                    throw new IllegalArgumentException("attachment fileName must not be blank");
                }
                if (!StringUtils.hasText(attachment.getBase64Content())) {
                    throw new IllegalArgumentException("attachment content must not be blank");
                }
            }
        }
    }
}
