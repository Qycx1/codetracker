package com.io.kira.adapter.auth.in.rest;

import com.io.kira.application.auth.command.LogoutCommand;
import com.io.kira.application.auth.port.in.LogoutUseCase;
import com.io.kira.application.auth.result.LogoutResult;
import com.io.kira.infrastructure.auth.config.properties.DeviceIdCookieProperties;
import com.io.kira.infrastructure.auth.config.properties.JwtCookieProperties;
import com.io.kira.infrastructure.auth.config.properties.RefreshCookieProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AuthControllerTest {
    @Test
    void logoutCanUseTheBackendDeviceCookieWithoutAFrontendReadableDeviceId() {
        LogoutUseCase logout = mock(LogoutUseCase.class);
        when(logout.execute(new LogoutCommand("backend-device", "refresh-secret"))).thenReturn(LogoutResult.SUCCESS);
        AuthController controller = new AuthController(logout,
                new JwtCookieProperties(true, true, "", "/", "None"),
                new RefreshCookieProperties(true, true, "", "/", "None"),
                new DeviceIdCookieProperties(true, false, "", "/", "None"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("device_id", "backend-device"), new Cookie("refresh_token", "refresh-secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertEquals(200, controller.logout(null, request, response).getStatusCode().value());
        verify(logout).execute(new LogoutCommand("backend-device", "refresh-secret"));
        assertEquals(3, response.getHeaders("Set-Cookie").size());
    }
}
