package Hampouch.server.domain.challenge.service;

import Hampouch.server.domain.challenge.repository.ChallengeRepository;
import Hampouch.server.domain.challenge.repository.ChallengeRepository.FinalizationCheckTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChallengeFinalizationScheduler {

    private static final int BATCH_SIZE = 100;

    private final ChallengeRepository challengeRepository;
    private final ChallengeService challengeService;
    private final Clock clock;

    @Scheduled(cron = "${challenge.finalization.cron:0 0 0 * * *}", zone = "Asia/Seoul")
    public void finalizeDueChallenges() {
        finalizeDueChallenges(LocalDate.now(clock));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void finalizeDueChallengesAfterStartup() {
        finalizeDueChallenges();
    }

    public void finalizeDueChallenges(LocalDate judgmentDate) {
        long afterId = 0L;
        while (true) {
            List<FinalizationCheckTarget> targets =
                    challengeRepository.findFinalizationCheckTargetsAfter(
                            judgmentDate,
                            afterId,
                            Pageable.ofSize(BATCH_SIZE));
            if (targets.isEmpty()) {
                return;
            }

            for (FinalizationCheckTarget target : targets) {
                try {
                    challengeService.finalizeDueChallenge(
                            target.getUserId(), target.getChallengeId(), judgmentDate);
                } catch (RuntimeException exception) {
                    log.error("챌린지 정기 확정 실패: challengeId={}, userId={}",
                            target.getChallengeId(), target.getUserId(), exception);
                }
            }

            afterId = targets.getLast().getChallengeId();
            if (targets.size() < BATCH_SIZE) {
                return;
            }
        }
    }
}
