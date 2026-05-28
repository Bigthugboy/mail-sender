package xy.mailsenders.mail.brevo;

import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import xy.mailsenders.mail.brevo.request.BrevoAttachment;
import xy.mailsenders.mail.brevo.request.BrevoEmailContact;
import xy.mailsenders.mail.brevo.request.BrevoSendEmailRequest;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.mail.smtp.message.MimeMessageBuilder;

import java.util.List;
import java.util.Map;

/**
 * Maps a MailPayload → BrevoSendEmailRequest.
 *
 * SRP: payload mapping only — no HTTP, no gateway logic.
 * DRY: stripHtml reused from MimeMessageBuilder (single source).
 */
@Component
public class BrevoRequestMapper {

    private final MailSendingProperties props;

    public BrevoRequestMapper(MailSendingProperties props) {
        this.props = props;
    }

    public BrevoSendEmailRequest toBrevoRequest(MailPayload payload) {
        BrevoSendEmailRequest req = new BrevoSendEmailRequest();
        String from = StringUtils.hasText(props.getFromAddress())
                ? props.getFromAddress().trim() : props.getAnalyticsRecipient();

        req.setSender(new BrevoEmailContact(from, props.getAnalyticsSenderName()));
        req.setTo(List.of(new BrevoEmailContact(payload.getTo().trim(), null)));
        req.setSubject(payload.getSubject().trim());

        if (payload.isHtml()) {
            req.setHtmlContent(payload.getBody());
            req.setTextContent(MimeMessageBuilder.stripHtml(payload.getBody()));
        } else {
            req.setTextContent(payload.getBody());
        }

        if (StringUtils.hasText(props.getUnsubscribeMailto())) {
            req.setHeaders(Map.of("List-Unsubscribe",
                    "<mailto:" + props.getUnsubscribeMailto().trim() + ">"));
        }

        if (!CollectionUtils.isEmpty(payload.getAttachments())) {
            req.setAttachment(payload.getAttachments().stream()
                    .map(a -> new BrevoAttachment(a.getFileName(), a.getBase64Content()))
                    .toList());
        }
        return req;
    }
}
