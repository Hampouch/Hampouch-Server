package Hampouch.server.global.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final AuthEntryPoint authEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/email/send",
                                "/api/auth/email/verify",
                                "/api/auth/nickname/check",
                                "/api/auth/signup",
                                "/api/auth/login",
                                "/api/auth/social",
                                "/api/auth/refresh",
                                "/api/auth/password/reset",
                                "/actuator/health"
                        ).permitAll()
                        // TODO(임시): challenge, mini-challenge 도메인이  @LoginUserId 인증 적용 전이라 당장 authenticated()로 막으면 기존 테스트/API가 전부 깨짐.
                        // TODO: 인증 적용 완료하고 아래 두 줄 제거하고 다시 anyRequest().authenticated()만 남겨야 함
                        .requestMatchers("/api/challenges/**", "/api/mini-challenges/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(handler ->
                        handler.authenticationEntryPoint(authEntryPoint)
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}