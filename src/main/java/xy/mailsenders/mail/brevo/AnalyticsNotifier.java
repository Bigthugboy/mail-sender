package xy.mailsenders.mail.brevo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.service.ResendMailGateWay;


@Service
public class AnalyticsNotifier {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsNotifier.class);
    private final ResendMailGateWay mailGateWay;
    private final MailSendingProperties properties;

    public AnalyticsNotifier(ResendMailGateWay mailGateWay, MailSendingProperties properties) {
        this.mailGateWay = mailGateWay;
        this.properties = properties;
    }

    public void sendReport(MailSendingReport report) {
        if (report == null) {
            logger.debug("No report provided");
            return;
        }

        MailPayload req = new MailPayload();
        req.setTo(properties.getAnalyticsRecipient());
        req.setSubject("Mail Sending Analytics Report");
        req.setBody(buildAnalyticsHtml(report));
        req.setHtml(true);

        try {
            mailGateWay.send(req);
            logger.info("Analytics email report sent to {}", properties.getAnalyticsRecipient());
        } catch (Exception ex) {
            logger.warn("Failed to send analytics email to {}", properties.getAnalyticsRecipient(), ex);
        }
    }

    private String buildAnalyticsBody(MailSendingReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Mail sending analytics\n\n");
        sb.append("Total attempted: ").append(report.getTotal()).append('\n');
        sb.append("Total succeeded: ").append(report.getSuccessCount()).append('\n');
        sb.append("Total failed: ").append(report.getFailureCount()).append('\n');
        sb.append("\nSuccessful addresses:\n");
        if (report.getSuccessfulEmails() == null || report.getSuccessfulEmails().isEmpty()) {
            sb.append("  (none)\n");
        } else {
            report.getSuccessfulEmails().forEach(email -> sb.append("  - ").append(email).append('\n'));
        }
        sb.append("\nFailed addresses:\n");
        if (report.getFailedEmails() == null || report.getFailedEmails().isEmpty()) {
            sb.append("  (none)\n");
        } else {
            report.getFailedEmails().forEach(email -> sb.append("  - ").append(email).append('\n'));
        }
        return sb.toString();
    }

    private String buildAnalyticsHtml(MailSendingReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><style>")
          .append("body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px; }")
          .append(".header { background: linear-gradient(135deg, #6e8efb, #a777e3); color: white; padding: 30px; border-radius: 10px 10px 0 0; text-align: center; }")
          .append(".content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; border: 1px solid #eee; border-top: none; }")
          .append(".stat-card { background: white; padding: 15px; border-radius: 8px; margin-bottom: 15px; box-shadow: 0 2px 5px rgba(0,0,0,0.05); display: flex; justify-content: space-between; align-items: center; }")
          .append(".stat-label { font-weight: bold; color: #555; }")
          .append(".stat-value { font-size: 1.2em; color: #6e8efb; font-weight: 800; }")
          .append(".success { color: #28a745; }")
          .append(".failure { color: #dc3545; }")
          .append(".list-section { margin-top: 25px; }")
          .append(".list-header { font-weight: bold; margin-bottom: 10px; color: #444; border-bottom: 2px solid #eee; padding-bottom: 5px; }")
          .append(".email-list { list-style: none; padding: 0; font-size: 0.9em; }")
          .append(".email-item { padding: 5px 0; border-bottom: 1px solid #f0f0f0; }")
          .append("</style></head><body>")
          .append("<div class='header'><h1>Mail Sending Analytics</h1></div>")
          .append("<div class='content'>")
          
          .append("<div class='stat-card'><span class='stat-label'>Total Attempted</span><span class='stat-value'>").append(report.getTotal()).append("</span></div>")
          .append("<div class='stat-card'><span class='stat-label'>Successfully Sent</span><span class='stat-value success'>").append(report.getSuccessCount()).append("</span></div>")
          .append("<div class='stat-card'><span class='stat-label'>Failed</span><span class='stat-value failure'>").append(report.getFailureCount()).append("</span></div>")

          .append("<div class='list-section'><div class='list-header'>Successful Addresses</div><ul class='email-list'>");
        if (report.getSuccessfulEmails() == null || report.getSuccessfulEmails().isEmpty()) {
            sb.append("<li class='email-item'>(none)</li>");
        } else {
            report.getSuccessfulEmails().forEach(email -> sb.append("<li class='email-item'>").append(email).append("</li>"));
        }
        sb.append("</ul></div>")

          .append("<div class='list-section'><div class='list-header'>Failed Addresses</div><ul class='email-list'>");
        if (report.getFailedEmails() == null || report.getFailedEmails().isEmpty()) {
            sb.append("<li class='email-item'>(none)</li>");
        } else {
            report.getFailedEmails().forEach(email -> sb.append("<li class='email-item'>").append(email).append("</li>"));
        }
        sb.append("</ul></div>")
          
          .append("</div></body></html>");
        return sb.toString();
    }
}
