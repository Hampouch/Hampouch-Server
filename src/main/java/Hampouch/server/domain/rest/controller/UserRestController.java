package Hampouch.server.domain.rest.controller;

import Hampouch.server.domain.rest.dto.RestResumeRequest;
import Hampouch.server.domain.rest.dto.RestResumeResponse;
import Hampouch.server.domain.rest.dto.RestStartRequest;
import Hampouch.server.domain.rest.dto.RestStartResponse;
import Hampouch.server.domain.rest.service.UserRestService;
import Hampouch.server.global.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 휴식 REST API (#8, 정일혁 파트) — 휴식은 유저 단위 리소스라 /challenges 하위가 아니라 /rests.
 * 클래스 이름이 RestController가 아닌 이유: 애너테이션 @RestController와 이름이 겹쳐 읽는 사람이 헷갈린다 — 엔티티(UserRest)를 따라 UserRestController.
 *
 * TODO(로그인 연동): 유저 식별은 연동 전까지 X-User-Id 헤더 스텁(기본 1) — 연동 시 JWT sub 클레임으로 교체.
 */
@RestController // = @Controller(요청 받는 빈으로 등록 + 아래 매핑들을 URL 라우팅에 올림) + @ResponseBody(반환 객체를 화면 이름이 아니라 JSON 응답 본문으로 직렬화 — 전 메서드 일괄)
// "빈으로 등록" = 생성·의존성 조립·수명 관리를 컨테이너가 맡는다(우리 코드에 new 없음). 인스턴스는 싱글턴 하나가
// 모든 요청을 받으므로(여러 스레드 동시 호출) 요청별 상태를 필드로 두지 않는다 — final 의존성만 갖는 이유.
@RequestMapping(UserRestController.BASE_PATH)
@RequiredArgsConstructor // final 필드(service)를 받는 생성자 자동 생성 — 스프링이 그 생성자로 주입
public class UserRestController {

    /** 클래스 매핑과 Location 헤더 조립이 공유하는 기본 경로 — ChallengeController와 같은 장치. */
    static final String BASE_PATH = "/api/rests";

    private static final String USER_HEADER = "X-User-Id";

    private final UserRestService service;

    /** 휴식 시작 — user_rest 행이 새로 생기므로 201 Created + Location (생성 API 관례, RFC 9110). */
    @PostMapping
    public ResponseEntity<ApiResponse<RestStartResponse>> start(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            // @Valid = 뒤에 붙은 이 파라미터를 검사하라는 스위치 — 역직렬화된 객체 안의 제약 선언 전부를 평가하고,
            // 위반이면 이 메서드 본문에 들어오기 전에 스프링이 예외를 던져 공통 처리기가 400으로 바꾼다(서비스까지 안 내려옴)
            @Valid @RequestBody RestStartRequest request) {
        RestStartResponse res = service.start(userId, request);
        return ResponseEntity
                .created(URI.create(BASE_PATH + "/" + res.restId()))
                .body(ApiResponse.success(res));
    }

    /**
     * 복귀/더 쉬기 — 새 리소스가 안 생기고 있던 휴식의 복귀일·예정일이 바뀔 뿐이라 201이 아닌 200
     * (give-up·days가 200인 것과 같은 이유: POST는 처리 요청, 201은 결과가 생성일 때만).
     */
    @PostMapping("/resume")
    public ApiResponse<RestResumeResponse> resume(
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = "1") Long userId,
            @Valid @RequestBody RestResumeRequest request) {
        return ApiResponse.success(service.resume(userId, request));
    }
}
