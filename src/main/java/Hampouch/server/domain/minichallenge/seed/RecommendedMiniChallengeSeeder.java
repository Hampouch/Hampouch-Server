package Hampouch.server.domain.minichallenge.seed;

import Hampouch.server.domain.minichallenge.entity.RecommendedMiniChallenge;
import Hampouch.server.domain.minichallenge.repository.RecommendedMiniChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 추천 미니 챌린지 프리셋 카탈로그 시드.
 *
 * 임시 시드 — 기획 확정 리스트로 교체 예정(PM 질문 누적됨).
 * API명세_미니챌린지.md §2는 "목록 = 기획 리스트업 확정(0630, 시드 데이터로 반영)"인데 확정 실물 리스트가
 * 레포에 없어, 명세 예시(편의점 디저트 안 먹기 7일)와 톤을 맞춘 절약 미션 문구로 임시 구성했다.
 * 기간은 화이트리스트(오늘만 1 / 3 / 7 / 14 / 31)를 전부 덮도록 각 2~3개.
 *
 * data.sql이 아니라 ApplicationRunner인 이유: 팀 방침이 ddl-auto=update(Flyway 없음)라
 * data.sql은 매 기동마다 다시 실행돼 중복 삽입되고, 그걸 막을 설정(application.yml)은
 * 나연 담당이라 못 건드린다. 러너는 테이블이 비어 있을 때만 넣어 재기동에 안전(멱등).
 *
 * 단일 인스턴스 기동 전제(현 배포 구성 — 도커 MySQL 1대에 앱 1대): count 가드와 saveAll 사이에
 * 잠금이 없어, 빈 DB에 앱 인스턴스 여러 대가 동시에 첫 기동하면 둘 다 시드를 넣어 중복될 수 있다.
 * 다중 인스턴스 배포가 실제로 생기면 (title, duration_days) unique 제약 + 중복 삽입 예외 무시
 * 방식으로 전환할 것.
 */
@Component
@RequiredArgsConstructor
// ApplicationRunner 구현(인터페이스라 상속이 아니라 구현) = "기동이 다 끝난 직후 한 번 불러 달라"는
// 부트의 콜백 약속. 부트는 이 인터페이스 구현 빈을 전부 찾아 run()을 호출한다 — 구현해야 그 목록에 든다.
// 이 시점엔 컨텍스트·DB 연결·ddl-auto 테이블 생성이 전부 끝나 있고, 호출이 스프링 프록시를 거치므로
// 아래 run()의 @Transactional도 정상 적용된다(@PostConstruct였다면 둘 다 보장 안 됨).
public class RecommendedMiniChallengeSeeder implements ApplicationRunner {

    private final RecommendedMiniChallengeRepository repository;

    @Override
    @Transactional // 시드 전체가 한 덩어리로 들어가거나 말거나 — 중간 실패로 반쪽 카탈로그가 남지 않게
    // args = 앱 실행 시 붙인 커맨드라인 인자를 파싱해 담은 객체(--키=값 옵션 등을 꺼낼 수 있다).
    // 이 시더는 인자를 쓸 일이 없지만 시그니처가 인터페이스에 고정돼 있어 받기만 한다.
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            // 이미 시드됨(또는 운영 데이터 존재) — 건드리지 않는다.
            // 이 가드는 시더 자신의 재삽입만 막는다. 운영자가 SQL 등으로 카탈로그에 행을
            // 추가·교체하는 건 막지 않으며, 시더는 그걸 덮지도 않는다(보존).
            return;
        }
        // TODO(기획 확정): 추천 리스트 실물이 확정되면 아래 임시 문구를 통째로 교체할 것 — PM_질문목록 참조.
        //   주의: 위 count 가드 때문에 코드 교체·재배포만으로는 이미 시드가 들어간 DB에 반영되지 않는다.
        //   교체 배포 시 recommended_mini_challenge 테이블을 비운 뒤 재기동해야 새 리스트가 들어간다.
        repository.saveAll(List.of(
                // 오늘만(1일)
                RecommendedMiniChallenge.of("오늘 커피 사먹지 않기", 1),
                RecommendedMiniChallenge.of("오늘 배달 앱 열지 않기", 1),
                RecommendedMiniChallenge.of("오늘 간식 지출 0원", 1),
                // 3일
                RecommendedMiniChallenge.of("카페 음료 안 마시기", 3),
                RecommendedMiniChallenge.of("야식 안 먹기", 3),
                // 7일
                RecommendedMiniChallenge.of("편의점 디저트 안 먹기", 7), // 명세 §2 예시 그대로
                RecommendedMiniChallenge.of("배달 음식 안 시키기", 7),
                RecommendedMiniChallenge.of("점심 도시락 싸기", 7),
                // 14일
                RecommendedMiniChallenge.of("하루 식비 1만 원 이하로 쓰기", 14),
                RecommendedMiniChallenge.of("술자리 지출 안 하기", 14),
                // 31일
                RecommendedMiniChallenge.of("한 달 커피값 절반으로 줄이기", 31),
                RecommendedMiniChallenge.of("장보기 전에 살 것 목록 쓰기", 31)));
    }
}
