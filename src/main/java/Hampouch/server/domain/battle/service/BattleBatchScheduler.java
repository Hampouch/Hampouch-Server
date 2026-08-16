package Hampouch.server.domain.battle.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 배틀 자동 취소·무효화·종료 스냅샷 배치의 스케줄러(#139). 매일 시작→무효화→종료 순서로 실행한다
 * — 무효화(탈퇴 유저 즉시 무효화 포함)가 종료보다 먼저 돌아야 그날 바로 종료되는 배틀의 랭킹·벌칙
 * 대상 산정에 즉시 반영된다. ChallengeFinalizationScheduler와 동일하게 대상 하나의 실패가 나머지를
 * 막지 않도록 항목별로 예외를 잡아 로그만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BattleBatchScheduler {

    private final BattleBatchService battleBatchService;
    private final Clock clock;

    @Scheduled(cron = "${battle.finalization.cron:0 0 0 * * *}", zone = "Asia/Seoul")
    public void runDueBattleBatches() {
        runDueBattleBatches(LocalDate.now(clock));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runDueBattleBatchesAfterStartup() {
        runDueBattleBatches();
    }

    public void runDueBattleBatches(LocalDate judgmentDate) {
        processStartTargets(judgmentDate);
        processInvalidation(judgmentDate);
        processTerminationTargets(judgmentDate);
    }

    private void processStartTargets(LocalDate judgmentDate) {
        for (Long battleId : battleBatchService.findStartTargetIds(judgmentDate)) {
            try {
                battleBatchService.processStart(battleId);
            } catch (RuntimeException exception) {
                log.error("배틀 시작일 판정 실패: battleId={}", battleId, exception);
            }
        }
    }

    private void processInvalidation(LocalDate judgmentDate) {
        for (Long participantId : battleBatchService.findInvalidationTargetIds()) {
            try {
                battleBatchService.processInvalidation(participantId, judgmentDate);
            } catch (RuntimeException exception) {
                log.error("배틀 무효화 판정 실패: participantId={}", participantId, exception);
            }
        }
    }

    private void processTerminationTargets(LocalDate judgmentDate) {
        for (Long battleId : battleBatchService.findTerminationTargetIds(judgmentDate)) {
            try {
                battleBatchService.processTermination(battleId, judgmentDate);
            } catch (RuntimeException exception) {
                log.error("배틀 종료 처리 실패: battleId={}", battleId, exception);
            }
        }
    }
}
