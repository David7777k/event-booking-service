package io.github.david7777k.seatflow.security.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
@EnableMethodSecurity
public class SecurityConfiguration {

    /**
     * BCrypt, not a plain hash.
     *
     * <p>SHA-256 is designed to be fast, which is exactly wrong for passwords:
     * speed is what an attacker with a stolen table wants. BCrypt is
     * deliberately slow, salts every hash so identical passwords do not produce
     * identical output, and lets the cost be raised as hardware improves.
     *
     * <p>The cost is configurable so tests can lower it. Each step doubles the
     * work: 12 is a few hundred milliseconds per login, which is fine for a
     * human and painful to brute force, but would add minutes to a test suite
     * that creates users constantly.
     */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${seatflow.security.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        return new SecretKeySpec(
                properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Maps the {@code roles} claim onto Spring Security authorities.
     *
     * <p>The default converter reads OAuth2 scopes and prefixes them with
     * {@code SCOPE_}. This service thinks in roles, so the claim is named
     * {@code roles} and prefixed with {@code ROLE_}, which is what
     * {@code hasRole} expects.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {

        http
                // No cookies, no server-side session: every request carries its
                // own proof. CSRF protection defends against a browser attaching
                // ambient credentials automatically, which cannot happen when the
                // credential is a header the caller must set deliberately.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(requests -> requests
                        // API documentation and the health endpoint are open, so the
                        // service can be explored and probed without credentials.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                        // Registration and login must be reachable without a token.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()

                        // The catalogue is public: browsing events is not an
                        // account-holder privilege.
                        .requestMatchers(HttpMethod.GET, "/api/v1/events/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/venues/**").permitAll()

                        // Changing the catalogue is.
                        .requestMatchers("/api/v1/venues/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/events/**").hasRole("ADMIN")

                        // Everything else, bookings included, needs a valid token.
                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))

                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults()))

                // No login form and no browser prompt: this is an API, and a
                // redirect to a login page would be a confusing answer to a
                // programmatic caller.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        return http.build();
    }
}
