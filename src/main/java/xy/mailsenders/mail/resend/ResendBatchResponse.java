package xy.mailsenders.mail.resend;

import lombok.Data;

import java.util.List;

/**
 * Response from Resend's POST /emails/batch endpoint.
 * Each entry in 'data' corresponds to one email in the request array,
 * in the same order.
 */
@Data
public class ResendBatchResponse {

    private List<ResendEmailResult> data;

    @Data
    public static class ResendEmailResult {
        /** Resend's internal message ID — present on success */
        private String id;
    }
}
