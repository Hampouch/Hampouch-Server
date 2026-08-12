package Hampouch.server.domain.expense.dto;

public record ExpenseImagePresignResponse(
        String imageKey,
        String uploadUrl,
        long expiresInSeconds
) {
    public static ExpenseImagePresignResponse of(String imageKey, String uploadUrl, long expiresInSeconds) {
        return new ExpenseImagePresignResponse(imageKey, uploadUrl, expiresInSeconds);
    }
}
