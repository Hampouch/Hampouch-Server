package Hampouch.server.domain.community.event;

import java.util.List;

public record CommunityImageDeleteEvent(
        List<String> imageKeys
) {
    public CommunityImageDeleteEvent {
        imageKeys = List.copyOf(imageKeys);
    }
}