package Hampouch.server.domain.battle.controller;

import Hampouch.server.domain.battle.dto.*;
import Hampouch.server.domain.battle.entity.BattleStatus;
import Hampouch.server.domain.battle.service.BattleService;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.BattleErrorCode;
import Hampouch.server.global.jwt.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 웹 계층(검증·상태코드·팀 공통 에러 응답 매핑) 검증. 서비스는 목 — DB 불필요
 * (ExpenseControllerTest와 동일 스타일 — BattleController도 X-User-Id 스텁이 아니라
 * @LoginUserId를 쓰므로 challenge 쪽이 아니라 expense 쪽 컨벤션을 따른다).
 */
@WebMvcTest(BattleController.class)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 제외 — 웹 계층(상태코드·필드)만 검증
class BattleControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    BattleService service;

    @MockitoBean
    JwtProvider jwtProvider; // SecurityConfig가 요구하는 빈 — addFilters=false라 실제 토큰 검증엔 안 쓰임

    private static final Long OWNER = 1L;

    /**
     * @LoginUserId가 읽어갈 인증 정보를 테스트 스레드의 SecurityContextHolder에 직접 심는다.
     * addFilters=false라 JwtFilter가 아예 안 돌기 때문에, .with(authentication(...))처럼
     * 필터가 복원해주길 기대하는 방식은 동작하지 않는다(ExpenseControllerTest와 동일 이유).
     */
    @BeforeEach
    void setUpSecurityContext() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                OWNER, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------- create ----------

    @Test
    @DisplayName("생성 요청이 정상이면 201 Created와 Location 헤더, 생성 결과 본문을 돌려준다")
    void create_201() throws Exception {
        when(service.create(anyLong(), any())).thenReturn(new CreateBattleResponse(
                1L, "ABCD1234", "짠테크 배틀", 4, 7,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), "치킨 사주기", BattleStatus.READY));

        mvc.perform(post("/api/battles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "title": "짠테크 배틀", "capacity": 4, "durationDays": 7,
                                  "startDate": "2026-08-01", "penalty": "치킨 사주기" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/battles/1"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("햄배틀이 생성됐습니다."))
                .andExpect(jsonPath("$.data.battleCode").value("ABCD1234"))
                .andExpect(jsonPath("$.data.status").value("READY"));
    }

    @Test
    @DisplayName("title이 비어 있으면 400으로 거절한다 (@NotBlank)")
    void create_400_whenTitleBlank() throws Exception {
        mvc.perform(post("/api/battles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "title": "", "capacity": 4, "durationDays": 7,
                                  "startDate": "2026-08-01", "penalty": "치킨 사주기" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").value("햄배틀 제목을 입력해주세요."));
    }

    @Test
    @DisplayName("정원이 범위를 벗어나면 서비스가 던진 400(INVALID_CAPACITY_RANGE)을 팀 공통 에러 본문으로 그대로 내려준다")
    void create_400_whenCapacityOutOfRange() throws Exception {
        when(service.create(anyLong(), any()))
                .thenThrow(new CustomException(BattleErrorCode.INVALID_CAPACITY_RANGE));

        mvc.perform(post("/api/battles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "title": "짠테크 배틀", "capacity": 11, "durationDays": 7,
                                  "startDate": "2026-08-01", "penalty": "치킨 사주기" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CAPACITY_RANGE"));
    }

    // ---------- getMyBattles ----------

    @Test
    @DisplayName("목록 조회가 정상이면 200과 배틀 카드 배열을 돌려준다")
    void getMyBattles_200() throws Exception {
        when(service.getMyBattles(eq(OWNER), any()))
                .thenReturn(new BattleListResponse(List.of(new BattleSummary.Ready(
                        1L, "ABCD1234", "짠테크 배틀", "치킨 사주기",
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), BattleStatus.READY,
                        4, 2))));

        mvc.perform(get("/api/battles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.battles[0].battleId").value(1))
                .andExpect(jsonPath("$.data.battles[0].capacity").value(4))
                .andExpect(jsonPath("$.data.battles[0].joinedCount").value(2));
    }

    @Test
    @DisplayName("status 쿼리 파라미터를 그대로 서비스에 전달한다")
    void getMyBattles_passesStatusFilter() throws Exception {
        when(service.getMyBattles(OWNER, BattleStatus.ONGOING)).thenReturn(new BattleListResponse(List.of()));

        mvc.perform(get("/api/battles").param("status", "ONGOING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.battles").isEmpty());
    }

    // ---------- getInvitation ----------

    @Test
    @DisplayName("참가 링크 조회가 정상이면 200과 미리보기 본문(battleId 제외, endDate 대신 durationDays)을 돌려준다")
    void getInvitation_200() throws Exception {
        when(service.getInvitation(OWNER, "ABCD1234")).thenReturn(new BattleInvitationResponse(
                "짠테크 배틀", "치킨 사주기", 4, 2,
                LocalDate.of(2026, 8, 1), 7));

        mvc.perform(get("/api/battles/invitations/ABCD1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.title").value("짠테크 배틀"))
                .andExpect(jsonPath("$.data.joinedCount").value(2))
                .andExpect(jsonPath("$.data.durationDays").value(7))
                .andExpect(jsonPath("$.data.battleId").doesNotExist())
                .andExpect(jsonPath("$.data.endDate").doesNotExist());
    }

    @Test
    @DisplayName("존재하지 않는 초대 코드면 404(BATTLE_CODE_NOT_FOUND)를 돌려준다")
    void getInvitation_404_whenCodeNotFound() throws Exception {
        when(service.getInvitation(OWNER, "ZZZZ9999"))
                .thenThrow(new CustomException(BattleErrorCode.BATTLE_CODE_NOT_FOUND));

        mvc.perform(get("/api/battles/invitations/ZZZZ9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATTLE_CODE_NOT_FOUND"));
    }

    @Test
    @DisplayName("정원이 찬 배틀이면 409(BATTLE_FULL)를 돌려준다")
    void getInvitation_409_whenFull() throws Exception {
        when(service.getInvitation(OWNER, "ABCD1234"))
                .thenThrow(new CustomException(BattleErrorCode.BATTLE_FULL));

        mvc.perform(get("/api/battles/invitations/ABCD1234"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATTLE_FULL"));
    }

    @Test
    @DisplayName("이미 취소된 배틀이면 400(BATTLE_CANCELLED)을 돌려준다 — 다른 409들과 달리 의도적으로 400")
    void getInvitation_400_whenCancelled() throws Exception {
        when(service.getInvitation(OWNER, "ABCD1234"))
                .thenThrow(new CustomException(BattleErrorCode.BATTLE_CANCELLED));

        mvc.perform(get("/api/battles/invitations/ABCD1234"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BATTLE_CANCELLED"));
    }

    // ---------- join ----------
    // CANCELLED/ALREADY_STARTED/BATTLE_FULL은 join과 getInvitation이 validateJoinable()을 공유하고
    // 에러 매핑(GlobalExceptionHandler)도 공통이라 위 getInvitation 케이스로 이미 검증됨 — 여기선
    // join 고유 관심사(성공 시 저장 흐름, 그리고 참가에서 특히 자주 맞물리는 404/409)만 확인한다.

    @Test
    @DisplayName("참가가 정상이면 201 Created와 Location 헤더, battleId 본문을 돌려준다 " +
            "— Location과 body가 같은 battleId를 가리키는지까지 확인(둘을 중복해서 내리는 게 설계 의도라서)")
    void join_201() throws Exception {
        when(service.join(OWNER, "ABCD1234")).thenReturn(new JoinBattleResponse(1L));

        mvc.perform(post("/api/battles/invitations/ABCD1234"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/battles/1"))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("햄배틀에 참가했습니다."))
                .andExpect(jsonPath("$.data.battleId").value(1))
                .andExpect(jsonPath("$.data.participantId").doesNotExist())
                .andExpect(jsonPath("$.data.joinedAt").doesNotExist());
    }

    @Test
    @DisplayName("존재하지 않는 초대 코드로 참가를 시도하면 404(BATTLE_CODE_NOT_FOUND)를 돌려준다 " +
            "— 락 조회(findByBattleCodeForUpdate) 경로도 조회(getInvitation)와 동일한 404 매핑인지 확인")
    void join_404_whenCodeNotFound() throws Exception {
        when(service.join(OWNER, "ZZZZ9999"))
                .thenThrow(new CustomException(BattleErrorCode.BATTLE_CODE_NOT_FOUND));

        mvc.perform(post("/api/battles/invitations/ZZZZ9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATTLE_CODE_NOT_FOUND"));
    }

    @Test
    @DisplayName("이미 참가한 배틀이면 409(ALREADY_JOINED)를 돌려준다")
    void join_409_whenAlreadyJoined() throws Exception {
        when(service.join(OWNER, "ABCD1234"))
                .thenThrow(new CustomException(BattleErrorCode.ALREADY_JOINED));

        mvc.perform(post("/api/battles/invitations/ABCD1234"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_JOINED"));
    }
}
