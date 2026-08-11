package Hampouch.server.global.jwt;

import Hampouch.server.domain.user.entity.UserRole;
import Hampouch.server.global.common.exception.CustomException;
import Hampouch.server.global.common.exception.domain.AuthErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;

@Component
public class JwtProvider {

    private static final String CLAIM_ROLE = "role";
    //access token과 refresh token을 구분하는 필드를 토큰 안에 생성
    private static final String CLAIM_TYPE = "type";
    private static final String TOKEN_TYPE_ACCESS = "ACCESS";
    private static final String TOKEN_TYPE_REFRESH = "REFRESH";

    private final SecretKey key;
    private final Clock clock;

    @Getter
    private final long accessTokenExpiresInMs;

    @Getter
    private final long refreshTokenExpiresInMs;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expires-in-ms}") long accessTokenExpiresInMs,
            @Value("${jwt.refresh-token-expires-in-ms}") long refreshTokenExpiresInMs,
            Clock clock
    ) {
        //문자열 비밀키를 바이트 배열로 바꾸고, HMAC-SHA 알고리즘을 사용하기 위해 SecretKey 객체로 감싸줌.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiresInMs = accessTokenExpiresInMs;
        this.refreshTokenExpiresInMs = refreshTokenExpiresInMs;
        this.clock = clock;
    }

    public String createAccessToken(Long userId, UserRole role) {
        Date now = Date.from(clock.instant());
        Date expiry = new Date(now.getTime() + accessTokenExpiresInMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_TYPE, TOKEN_TYPE_ACCESS) //access token 발급 시 type=ACCESS 표시
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public String createRefreshToken(Long userId) {
        Date now = Date.from(clock.instant());
        Date expiry = new Date(now.getTime() + refreshTokenExpiresInMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TOKEN_TYPE_REFRESH) // refresh token 발급 시 type=REFRESH 표시
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * Access token에서 userId 추출 -> 인증 필터에서 사용
     * 모든 실패를 AUTH_UNAUTHORIZED로 통일
     */
    public Long getUserIdFromAccessToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new CustomException(AuthErrorCode.AUTH_UNAUTHORIZED);
        }
    }

    public UserRole getRoleFromAccessToken(String token) {
        Claims claims = parseClaims(token);
        return UserRole.valueOf(claims.get(CLAIM_ROLE, String.class));
    }

    //해당 토큰이 access token인지 명시적으로 확인
    //role 클레임 유무와 무관하게 type 값만으로 access/refresh를 구분하기 위함
    public boolean isAccessToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return TOKEN_TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    //해당 토큰이 refresh token인지 명시적으로 확인
    public boolean isRefreshToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return TOKEN_TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Refresh token에서 userId 추출 -> 토큰 재발급/로그아웃에서 사용
     * 만료와 그 외 무효 사유를 구분해서 EXPIRED/INVALID 에러코드를 각각 던짐.
     */
    public Long getUserIdFromRefreshToken(String token) {
        try {
            Claims claims = parseClaims(token);
            if (!TOKEN_TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
                throw new CustomException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
            }
            return Long.valueOf(claims.getSubject());
        } catch (ExpiredJwtException e) {
            throw new CustomException(AuthErrorCode.AUTH_REFRESH_TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new CustomException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token) //서명 검증+만료 체크
                .getPayload();
    }
}