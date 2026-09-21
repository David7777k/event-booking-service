package io.github.david7777k.seatflow.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.github.david7777k.seatflow.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unlike the rest of the suite, this class uses real tokens rather than mocked
 * authentication: it is testing the issuing and verification themselves.
 */
@AutoConfigureMockMvc
class AuthenticationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // --- registration ---------------------------------------------------------

    @Test
    void registersUserAndReturnsUsableToken() throws Exception {
        String body = register("newcomer@example.com", "a-perfectly-fine-password");

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(response.get("role").asText()).isEqualTo("USER");
        assertThat(response.get("accessToken").asText()).isNotBlank();

        // The token works on an endpoint that requires authentication.
        mockMvc.perform(get("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(body)))
                .andExpect(status().isOk());
    }

    @Test
    void neverStoresThePasswordItself() throws Exception {
        register("stored@example.com", "a-perfectly-fine-password");

        String hash = jdbcTemplate.queryForObject(
                "select password_hash from app_user where email = ?", String.class, "stored@example.com");

        assertThat(hash)
                .doesNotContain("a-perfectly-fine-password")
                .startsWith("$2");
    }

    @Test
    void refusesDuplicateEmailRegardlessOfCase() throws Exception {
        register("dup@example.com", "a-perfectly-fine-password");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "DUP@example.com", "password": "another-fine-password"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsShortPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "short@example.com", "password": "too-short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void rejectsMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "password": "a-perfectly-fine-password"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
    }

    // --- login ----------------------------------------------------------------

    @Test
    void logsInWithCorrectPassword() throws Exception {
        register("login@example.com", "a-perfectly-fine-password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "login@example.com", "password": "a-perfectly-fine-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void wrongPasswordAndUnknownEmailAnswerIdentically() throws Exception {
        register("real@example.com", "a-perfectly-fine-password");

        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "real@example.com", "password": "wrong-password-entirely"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownEmail = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody@example.com", "password": "wrong-password-entirely"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Identical responses: a difference here is a way to discover which
        // addresses have accounts.
        assertThat(wrongPassword).isEqualTo(unknownEmail);
    }

    // --- token handling -------------------------------------------------------

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/bookings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsGarbageToken() throws Exception {
        mockMvc.perform(get("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenWhoseSignatureDoesNotMatchItsPayload() throws Exception {
        String valid = token(register("tamper@example.com", "a-perfectly-fine-password"));

        // Re-encode the payload with an elevated role, keeping the original
        // signature. The signature no longer matches, which is exactly what it
        // is for.
        String[] parts = valid.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        String elevated = payload.replace("\"USER\"", "\"ADMIN\"");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(elevated.getBytes())
                + "." + parts[2];

        mockMvc.perform(get("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenCarriesNoSecrets() throws Exception {
        String valid = token(register("claims@example.com", "a-perfectly-fine-password"));

        String payload = new String(Base64.getUrlDecoder().decode(valid.split("\\.")[1]));

        // A JWT is signed, not encrypted - anyone holding it can read this.
        assertThat(payload)
                .contains("claims@example.com")
                .doesNotContain("a-perfectly-fine-password")
                .doesNotContain("$2");
    }

    // --- authorisation --------------------------------------------------------

    @Test
    void ordinaryUserCannotManageTheCatalogue() throws Exception {
        long userId = createUser("plain@example.com");

        mockMvc.perform(post("/api/v1/venues").with(asUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Sneaky Hall", "address": "Kyiv"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorCanManageTheCatalogue() throws Exception {
        mockMvc.perform(post("/api/v1/venues").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Proper Hall", "address": "Kyiv"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void browsingTheCatalogueNeedsNoAccount() throws Exception {
        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk());
    }

    // --- helpers --------------------------------------------------------------

    private String register(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String token(String authenticationResponse) throws Exception {
        return objectMapper.readTree(authenticationResponse).get("accessToken").asText();
    }
}
