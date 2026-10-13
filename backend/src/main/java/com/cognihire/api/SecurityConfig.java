package com.cognihire.api;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Configuration
class SecurityConfig {
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
  @Bean SecretKey jwtKey(@Value("${app.jwt-secret}") String secret) {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
    return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }
  @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, JwtFilter jwtFilter, @Value("${app.frontend-origin}") String origin) throws Exception {
    return http.csrf(c -> c.disable()).cors(c -> c.configurationSource(req -> {
      var config = new org.springframework.web.cors.CorsConfiguration(); config.setAllowedOrigins(List.of(origin)); config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS")); config.setAllowedHeaders(List.of("Authorization","Content-Type")); config.setAllowCredentials(true); return config;
    })).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(a -> a.requestMatchers("/api/v1/auth/**","/actuator/health").permitAll().anyRequest().authenticated())
      .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class).build();
  }
}

@org.springframework.stereotype.Component
class JwtService {
  private final SecretKey key; private final long ttl;
  JwtService(SecretKey key, @Value("${app.jwt-expiration-minutes}") long ttl) { this.key=key; this.ttl=ttl; }
  String issue(UserAccount user) { var now=Instant.now(); return Jwts.builder().subject(user.id.toString()).claim("role", user.role.name()).issuedAt(java.util.Date.from(now)).expiration(java.util.Date.from(now.plusSeconds(ttl*60))).signWith(key).compact(); }
  io.jsonwebtoken.Claims parse(String token) { return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload(); }
}

@org.springframework.stereotype.Component
class JwtFilter extends OncePerRequestFilter {
  private final JwtService jwt;
  JwtFilter(JwtService jwt) { this.jwt=jwt; }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    var header=request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith("Bearer ")) try {
      var claims=jwt.parse(header.substring(7)); var authorities=List.of(new SimpleGrantedAuthority("ROLE_"+claims.get("role",String.class)));
      var auth=new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities); org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    } catch (RuntimeException ignored) { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    chain.doFilter(request,response);
  }
}
