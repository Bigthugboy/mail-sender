package xy.mailsenders.service;

import xy.mailsenders.mail.domain.BulkMailResult;
import xy.mailsenders.mail.domain.MailPayload;

import java.util.Collection;

public interface MailService {

    BulkMailResult sendMails(Collection<MailPayload> mails);
}
