package Hampouch.server.domain.minichallenge.repository;

import Hampouch.server.domain.minichallenge.entity.MiniChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MiniChallengeRepository extends JpaRepository<MiniChallenge, Long> {

    /**
     * 유저의 미니 전체 조회 — 그날(§1) 응답의 기초 데이터.
     * "그날 활성"만 골라 오지 않고 전체를 가져오는 이유: 유저 스트릭(streakDays)은 과거 날짜를
     * 거슬러 가며 "그날 활성이던" 미니를 봐야 해서, 이미 끝난 미니도 계산에 필요하다.
     * 미니는 기간이 최대 31일이라 유저당 행 수가 작아 전체 조회 부담이 없다.
     */
    List<MiniChallenge> findByUserId(Long userId);
}
