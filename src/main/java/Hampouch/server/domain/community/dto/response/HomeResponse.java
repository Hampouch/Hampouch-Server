package Hampouch.server.domain.community.dto.response;

import java.util.List;

public record HomeResponse(
        List<PostListResponse> popularPosts,
        List<PostListResponse> pochiPicks,
        PageResponse<PostListResponse> posts
) {
    public static HomeResponse of(
            List<PostListResponse> popularPosts,
            List<PostListResponse> pochiPicks,
            PageResponse<PostListResponse> posts
    ) {
        return new HomeResponse(popularPosts, pochiPicks, posts);
    }
}