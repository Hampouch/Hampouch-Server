package Hampouch.server.domain.community.dto.response;

import java.util.List;

public record PresignResponse(
        List<FileResult> files
) {
    public static PresignResponse of(List<FileResult> files) {
        return new PresignResponse(files);
    }

    public record FileResult(
            String uploadUrl,
            String imageKey,
            String imageUrl
    ) {
        public static FileResult of(String uploadUrl, String imageKey, String imageUrl) {
            return new FileResult(uploadUrl, imageKey, imageUrl);
        }
    }
}