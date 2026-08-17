package Hampouch.server.domain.battle.dto.response;

import Hampouch.server.domain.battle.entity.BattleStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /battles 목록 항목 — READY(정원/참가인원)·ONGOING(참가자별 today/total)·TERMINATED(승자 요약)
 * 3가지가 shape 자체가 달라, 공통 필드+널러블 옵션필드 하나 대신 sealed interface로 분리.
 */
public sealed interface BattleSummary
        permits BattleSummary.Ready, BattleSummary.Ongoing, BattleSummary.Terminated {

    Long battleId();
    String battleCode();
    String title();
    String penalty();
    LocalDate startDate();
    LocalDate endDate();
    BattleStatus status();

    record Ready(
            Long battleId, String battleCode, String title, String penalty,
            LocalDate startDate, LocalDate endDate, BattleStatus status,
            int capacity, int joinedCount
    ) implements BattleSummary {
    }

    record Ongoing(
            Long battleId, String battleCode, String title, String penalty,
            LocalDate startDate, LocalDate endDate, BattleStatus status,
            List<ParticipantAmount> participants
    ) implements BattleSummary {
        public record ParticipantAmount(
                Long userId, String nickname, String avatarUrl, long todayAmount, long totalAmount
        ) {
        }
    }

    record Terminated(
            Long battleId, String battleCode, String title, String penalty,
            LocalDate startDate, LocalDate endDate, BattleStatus status,
            String winnerNickname
    ) implements BattleSummary {
    }
}
