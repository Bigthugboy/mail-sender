package xy.mailsenders.mail.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import xy.mailsenders.mail.domain.BulkMailResult;
import xy.mailsenders.mail.domain.MailFailure;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkMailResponse {

    private int requestedRecipients;
    private int uniqueRecipients;
    private int sentCount;
    private int failedCount;
    private List<MailFailure> failures;

	public static BulkMailResponse from(BulkMailResult result) {
		return new BulkMailResponse(
				result.getRequestedRecipients(),
				result.getUniqueRecipients(),
				result.getSentCount(),
				result.getFailedCount(),
				result.getFailures()
		);
	}
}
