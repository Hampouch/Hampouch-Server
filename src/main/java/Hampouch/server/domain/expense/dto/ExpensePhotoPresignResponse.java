package Hampouch.server.domain.expense.dto;

public record ExpensePhotoPresignResponse(
        String imageKey,
        String uploadUrl,
        long expiresInSeconds
) {
    public static ExpensePhotoPresignResponse of(String imageKey, String uploadUrl, long expiresInSeconds) {
        return new ExpensePhotoPresignResponse(imageKey, uploadUrl, expiresInSeconds);
    }
}
