package io.github.david7777k.seatflow.security.service;

import io.github.david7777k.seatflow.common.error.ConflictException;
import io.github.david7777k.seatflow.security.domain.AppUser;
import io.github.david7777k.seatflow.security.domain.Role;
import io.github.david7777k.seatflow.security.repository.AppUserRepository;
import io.github.david7777k.seatflow.security.web.dto.AuthenticationResponse;
import io.github.david7777k.seatflow.security.web.dto.LoginRequest;
import io.github.david7777k.seatflow.security.web.dto.RegisterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /**
     * A valid BCrypt hash of a value nobody knows, used to spend the same time
     * on an unknown email as on a known one. Without it, a failed lookup
     * returns measurably faster than a wrong password, and that difference is
     * enough to enumerate which addresses have accounts.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.ZfHLUuRZ0bAQPZ9gGDLE6m9vh8cEyHm";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthenticationService(AppUserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Transactional
    public AuthenticationResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();

        if (userRepository.existsByEmailIgnoreCase(email)) {
            // The unique index is the real guarantee; this exists so the
            // ordinary case gets a clear message rather than a constraint error.
            throw new ConflictException("An account with this email already exists");
        }

        AppUser user = userRepository.save(new AppUser(
                email, passwordEncoder.encode(request.password()), Role.USER));

        log.info("Registered user {}", user.getId());

        return toResponse(user);
    }

    /**
     * Verifies credentials and issues a token.
     *
     * <p>A wrong password and an unknown address produce the same message and
     * take the same time. Telling a caller which of the two failed hands an
     * attacker a list of valid accounts.
     */
    @Transactional(readOnly = true)
    public AuthenticationResponse login(LoginRequest request) {
        Optional<AppUser> found = userRepository.findByEmailIgnoreCase(request.email().trim());

        String storedHash = found.map(AppUser::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), storedHash);

        if (found.isEmpty() || !passwordMatches) {
            log.debug("Failed login attempt");
            throw new BadCredentialsException("Invalid email or password");
        }

        return toResponse(found.get());
    }

    private AuthenticationResponse toResponse(AppUser user) {
        TokenService.IssuedToken token = tokenService.issue(user);

        return new AuthenticationResponse(
                token.value(),
                "Bearer",
                token.expiresInSeconds(),
                user.getId(),
                user.getEmail(),
                user.getRole());
    }
}
