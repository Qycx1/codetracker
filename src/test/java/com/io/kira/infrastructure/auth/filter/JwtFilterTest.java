package com.io.kira.infrastructure.auth.filter;

import com.io.kira.adapter.auth.out.security.CustomUserDetailsService;
import com.io.kira.adapter.auth.out.service.JwtService;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtFilterTest {
    private final JwtService jwt = mock(JwtService.class);
    private final CustomUserDetailsService users = mock(CustomUserDetailsService.class);
    private final JwtFilter filter = new JwtFilter(jwt, users);

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    private MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setCookies(new Cookie("jwt", token));
        return request;
    }

    @Test
    void anExpiredCookieDoesNotPreventThePublicAuthCheckFromRunning() throws Exception {
        when(jwt.extractAuthId("expired")).thenThrow(new ExpiredJwtException(null, null, "expired"));
        AtomicBoolean reachedController = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/auth/check", "expired"), response,
                (req, res) -> reachedController.set(true));
        assertTrue(reachedController.get());
        assertEquals(200, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void publicLoginRefreshAndLogoutRoutesIgnoreStaleJwtCookies() throws Exception {
        for (String path : List.of("/api/oauth/github/authorize", "/api/oauth/github/callback",
                "/api/auth/refresh", "/api/auth/logout")) {
            AtomicBoolean reachedController = new AtomicBoolean();
            filter.doFilter(request(path, "old-deployment-token"), new MockHttpServletResponse(),
                    (req, res) -> reachedController.set(true));
            assertTrue(reachedController.get(), path);
        }
        verifyNoInteractions(jwt, users);
    }

    @Test
    void aValidCookieStillAuthenticatesProtectedRequests() throws Exception {
        String authId = UUID.randomUUID().toString();
        UserDetails principal = mock(UserDetails.class);
        when(jwt.extractAuthId("valid")).thenReturn(authId);
        when(users.loadUserByUsername(authId)).thenReturn(principal);
        when(jwt.isTokenValid("valid", principal)).thenReturn(true);
        filter.doFilter(request("/api/users/profile", "valid"), new MockHttpServletResponse(),
                (req, res) -> assertSame(principal, SecurityContextHolder.getContext().getAuthentication().getPrincipal()));
        assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
    }

    @Test
    void aPreMigrationUserTokenIsUnauthenticatedRatherThanAServerError() throws Exception {
        when(jwt.extractAuthId("old")).thenReturn("old-auth-id");
        when(users.loadUserByUsername("old-auth-id")).thenThrow(new UsernameNotFoundException("missing user"));
        AtomicBoolean chainRan = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("/api/users/profile", "old"), response, (req, res) -> chainRan.set(true));
        assertTrue(chainRan.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotEquals(500, response.getStatus());
    }

    @Test
    void infrastructureFailuresAreNotSwallowedAsTokenErrors() {
        when(jwt.extractAuthId("valid")).thenReturn("auth-id");
        when(users.loadUserByUsername("auth-id")).thenThrow(new IllegalStateException("database offline"));
        assertThrows(IllegalStateException.class, () -> filter.doFilter(
                request("/api/users/profile", "valid"), new MockHttpServletResponse(), (req, res) -> {}));
    }
}
