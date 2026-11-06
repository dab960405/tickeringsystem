package com.portfolio.ticketing.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      @Qualifier("accessJwtDecoder") JwtDecoder accessJwtDecoder,
      JwtAuthenticationConverter jwtAuthenticationConverter,
      SecurityErrorHandler errorHandler,
      CorsConfigurationSource corsConfigurationSource)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(exceptions ->
            exceptions.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler))
        .authorizeHttpRequests(requests ->
            requests
                .requestMatchers("/error", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                .permitAll()
                .requestMatchers("/actuator/health", "/actuator/prometheus")
                .permitAll()
                .requestMatchers(
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    "/api/v1/payments/webhook")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/events/**", "/api/v1/venues/**")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/venues/**")
                .hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/venues/**")
                .hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/venues/**")
                .hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/events/**")
                .hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/events/**", "/api/v1/ticket-types/**")
                .hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers("/api/v1/reservations/**", "/api/v1/orders/**")
                .hasRole("USER")
                .anyRequest()
                .authenticated())
        .oauth2ResourceServer(resourceServer ->
            resourceServer.jwt(jwt ->
                jwt.decoder(accessJwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthenticationConverter)));
    return http.build();
  }

  @Bean
  SecretKey jwtSecretKey(AuthProperties properties) {
    byte[] secret = Base64.getDecoder().decode(properties.getJwt().getSecret());
    return new SecretKeySpec(secret, "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey secretKey) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
  }

  @Bean("refreshJwtDecoder")
  JwtDecoder refreshJwtDecoder(SecretKey secretKey) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("ticketing-api"));
    return decoder;
  }

  @Bean("accessJwtDecoder")
  JwtDecoder accessJwtDecoder(SecretKey secretKey) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
    OAuth2TokenValidator<Jwt> accessTokenValidator =
        jwt ->
            "access".equals(jwt.getClaimAsString("type"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Only access tokens are accepted", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer("ticketing-api"), accessTokenValidator));
    return decoder;
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setPrincipalClaimName("sub");
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      String role = jwt.getClaimAsString("role");
      if (role == null || role.isBlank()) {
        return List.of();
      }
      return List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role));
    });
    return converter;
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173}") String allowedOrigins) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of(allowedOrigins.split("\\s*,\\s*")));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Signature"));
    configuration.setExposedHeaders(List.of("Location", "X-Correlation-Id"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
