package Hampouch.server.domain.community.dto.response;

public record PostMutationResponse(
        Long postId
) {
    public static PostMutationResponse from(Long postId) {
        return new PostMutationResponse(postId);
    }
}