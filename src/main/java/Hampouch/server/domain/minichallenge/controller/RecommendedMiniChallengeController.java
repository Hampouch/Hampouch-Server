package Hampouch.server.domain.minichallenge.controller;

import Hampouch.server.domain.minichallenge.dto.RecommendedMiniChallengeListResponse;
import Hampouch.server.domain.minichallenge.service.RecommendedMiniChallengeService;
import Hampouch.server.global.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 추천 미니 챌린지 카탈로그 REST API (API명세_미니챌린지.md §2, 정일혁 파트).
 *
 * X-User-Id 스텁 헤더를 안 받는 이유(자체 결정): 명세 §2는 "(공용)" 카탈로그라 응답이 유저와 무관 —
 * 소유 확인도 유저별 데이터도 없어 유저 식별을 읽을 곳이 없다. 명세의 401은 로그인 여부이므로
 * JWT 도입 시 시큐리티 필터 계층에서 처리될 몫(컨트롤러 파라미터 아님).
 */
// @Controller + @ResponseBody 합성 애너테이션. @Controller 몫 = 빈 등록 + 메서드들을
// DispatcherServlet의 요청 핸들러로 인식시킴. @ResponseBody 몫 = 반환값을 뷰(화면) 이름으로
// 해석하지 않고 HTTP 응답 본문에 바로 직렬화(Jackson이 JSON으로) — REST API 컨트롤러라는 선언.
@RestController
@RequestMapping("/api/mini-challenges/recommended")
@RequiredArgsConstructor
public class RecommendedMiniChallengeController {

    private final RecommendedMiniChallengeService service;

    /**
     * 추천 목록 조회. durationDays는 기간 탭 필터(선택) — 생략하면 전체.
     * RequestParam은 경로 조각이 아니라 쿼리 스트링 바인딩 — ?durationDays=7 처럼 물음표 뒤에 온다.
     * 슬래시로 이어지는 /7 형태(경로 조각)로 받는 건 PathVariable + 매핑 경로의 {변수} 몫이라 별개.
     * required=false로 생략 가능하므로 null을 담을 수 있는 래퍼 Integer를 쓴다(원시 int면 생략 시 예외).
     * 검증 애너테이션을 안 붙인 것도 자체 결정: 화이트리스트 밖 값은 400이 아니라
     * 빈 items(200)로 처리하기로 해서(서비스 주석 참조), 여기서 걸러낼 게 없다.
     */
    @GetMapping
    public ApiResponse<RecommendedMiniChallengeListResponse> recommended(
            @RequestParam(required = false) Integer durationDays) {
        return ApiResponse.success(service.getRecommended(durationDays));
    }
}
