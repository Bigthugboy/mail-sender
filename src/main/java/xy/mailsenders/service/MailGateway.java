package xy.mailsenders.service;

import xy.mailsenders.mail.domain.MailFailure;
import xy.mailsenders.mail.domain.MailPayload;

import java.util.Collection;
import java.util.List;

public interface MailGateway {

    void send(MailPayload payload);
    List<MailFailure> sendAll(Collection<MailPayload> payloads, int concurrency);

}
