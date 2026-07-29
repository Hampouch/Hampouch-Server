package Hampouch.server.domain.community.dto.request;

public record PostListQuery(
        String sortType,
        int page,
        int size
) {
    public static PostListQuery of(String sortType, int page, int size) {
        return new PostListQuery(
                sortType == null ? "LATEST" : sortType,
                page,
                size
        );
    }
}