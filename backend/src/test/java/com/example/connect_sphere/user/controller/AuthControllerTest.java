package com.example.connect_sphere.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * D19 stages 4-6, end to end through the real filter chain: login, the token it
 * returns actually opening a protected endpoint, refresh rotation, reuse
 * detection and logout. Accounts come from DevUserSeeder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

    private static final String PASSWORD = "123456";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    private JsonNode login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new java.util.LinkedHashMap<>(java.util.Map.of(
                                        "username", username, "password", password)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    @Test
    void loginReturnsTokensAndEchoesTheVerifiedAccount() throws Exception {
        JsonNode response = login("eo1", PASSWORD);

        assertThat(response.get("accessToken").asString()).isNotBlank();
        assertThat(response.get("refreshToken").asString()).isNotBlank();
        assertThat(response.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(response.get("expiresIn").asLong()).isPositive();
        assertThat(response.get("username").asString()).isEqualTo("eo1");
        // Lowercase, matching the database label — the ROLE_/uppercase shape is
        // Spring Security's convention and is applied by JwtAuthenticationConverter.
        assertThat(response.get("role").asString()).isEqualTo("eo");
        assertThat(response.get("organisation").asString()).isEqualTo("Acme Pte Ltd");
    }

    @Test
    void theTokenLoginReturnsOpensAProtectedEndpoint() throws Exception {
        String accessToken = login("eo1", PASSWORD).get("accessToken").asString();

        mvc.perform(get("/api/event-requests").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    /**
     * Wrong password, unknown username and blank input must be indistinguishable:
     * a difference between them turns this endpoint into a way to discover which
     * accounts exist, one request at a time.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "eo1|wrong-password",
            "no-such-user|123456",
            "|",
            "eo1|",
    })
    void everyFailedLoginAnswersIdentically(String credentials) throws Exception {
        String[] parts = credentials.split("\\|", -1);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + parts[0] + "\",\"password\":\"" + parts[1] + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void refreshRotatesAndTheOldTokenStopsWorking() throws Exception {
        String first = login("eo1", PASSWORD).get("refreshToken").asString();

        String body = refresh(first).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = objectMapper.readTree(body).get("refreshToken").asString();
        assertThat(second).isNotEqualTo(first);

        refresh(first).andExpect(status().isUnauthorized());
    }

    @Test
    void reusingARotatedTokenAlsoKillsItsSuccessor() throws Exception {
        String first = login("eo1", PASSWORD).get("refreshToken").asString();
        String second = objectMapper
                .readTree(refresh(first).andReturn().getResponse().getContentAsString())
                .get("refreshToken").asString();

        refresh(first).andExpect(status().isUnauthorized());

        // The whole family is gone, not just the replayed token — otherwise a
        // thief who rotated once would keep the session and only the real user
        // would be locked out.
        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsTheSessionAndIsIdempotent() throws Exception {
        String refreshToken = login("eo1", PASSWORD).get("refreshToken").asString();

        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isNoContent());

        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownRefreshTokenIsRejected() throws Exception {
        refresh("never-issued").andExpect(status().isUnauthorized());
    }

    @Test
    void theAuthEndpointsNeedNoTokenOfTheirOwn() throws Exception {
        // permitAll, and reached without an Authorization header — the closed
        // loop SecurityConfig's comment describes.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"eo1\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        String accessToken = login("eo1", PASSWORD).get("accessToken").asString();

        // The FIRST character of the signature, not the last. An HS256 signature
        // is 32 bytes, which base64url-encodes to 43 characters carrying 258
        // bits — so the final character has two bits that decode to nothing.
        // Editing it can leave the decoded signature byte-identical and the
        // token still valid, which makes "change one character" a misleading
        // way to test this.
        String[] parts = accessToken.split("\\.");
        String signature = parts[2];
        String tampered = parts[0] + "." + parts[1] + "."
                + (signature.charAt(0) == 'A' ? 'B' : 'A') + signature.substring(1);

        mvc.perform(get("/api/event-requests").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }
}
