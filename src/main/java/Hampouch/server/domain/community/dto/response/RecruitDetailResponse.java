package Hampouch.server.domain.community.dto.response;

import java.time.LocalDate;

public record RecruitDetailResponse(
        Long battleId,
        String battleUrl,
        String battleTitle,
        LocalDate startDate,
        int durationDays,
        int maxMemberCount,
        int currentMemberCount,
        String penalty,
        boolean recruit
) {
    public static RecruitDetailResponse of(
            Long battleId, String battleUrl, String battleTitle, LocalDate startDate,
            int durationDays, int maxMemberCount, int currentMemberCount, String penalty, boolean recruit
    ) {
        return new RecruitDetailResponse(battleId, battleUrl, battleTitle, startDate, durationDays,
                maxMemberCount, currentMemberCount, penalty, recruit);
    }
}