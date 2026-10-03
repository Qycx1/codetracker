package com.io.kira.infrastructure.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthConfigurationTest {
    @Test
    void insecureSameSiteNoneCookiesFailBeforeDeploymentAcceptsLogins() {
        for (String cookie : List.of("jwt", "refresh_token", "device_id")) {
            assertThrows(IllegalArgumentException.class, () -> AuthCookieValidator.validate(cookie, false, "None"));
        }
        assertDoesNotThrow(() -> AuthCookieValidator.validate("jwt", true, "None"));
        assertDoesNotThrow(() -> AuthCookieValidator.validate("jwt", false, "Lax"));
    }

    @Test
    void cookieSameSiteSettingMustBeRecognized() {
        assertThrows(IllegalArgumentException.class, () -> AuthCookieValidator.validate("jwt", true, "none"));
        assertThrows(IllegalArgumentException.class, () -> AuthCookieValidator.validate("jwt", true, null));
    }

    @Test
    void corsSupportsSeveralExactFrontendOrigins() {
        CorsConfig config = new CorsConfig("https://frontend.example, https://preview.example,https://frontend.example");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/profile");
        var cors = config.corsConfigurationSource().getCorsConfiguration(request);
        assertNotNull(cors);
        assertEquals(List.of("https://frontend.example", "https://preview.example"), cors.getAllowedOrigins());
        assertEquals(Boolean.TRUE, cors.getAllowCredentials());
    }

    @Test
    void corsRejectsWildcardAndMalformedOrigins() {
        for (String origin : List.of("*", "https://frontend.example/", "https://frontend.example/dashboard", "https://frontend.example?value=1")) {
            assertThrows(IllegalArgumentException.class, () -> new CorsConfig(origin));
        }
    }
}
