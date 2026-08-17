package Hampouch.server.domain.community.dto.response;

public record FoodDetailResponse(
        String menuName,
        String placeName,
        int price,
        int tasteRating,
        int costRating,
        int moodRating
) {
    public static FoodDetailResponse of(String menuName, String placeName, int price,
                                        int tasteRating, int costRating, int moodRating) {
        return new FoodDetailResponse(menuName, placeName, price, tasteRating, costRating, moodRating);
    }
}