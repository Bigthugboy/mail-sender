package xy.mailsenders.mail.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BulkMailResult {
   private int requestedRecipients;
   private int uniqueRecipients;
   private int sentCount;
   private int failedCount;
   private List<MailFailure> failures;
}
