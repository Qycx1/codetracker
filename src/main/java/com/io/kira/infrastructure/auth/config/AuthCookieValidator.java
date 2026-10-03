package com.io.kira.infrastructure.auth.config;

import com.io.kira.infrastructure.auth.config.properties.DeviceIdCookieProperties;
import com.io.kira.infrastructure.auth.config.properties.JwtCookieProperties;
import com.io.kira.infrastructure.auth.config.properties.RefreshCookieProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class AuthCookieValidator {

    public AuthCookieValidator(JwtCookieProperties jwt, RefreshCookieProperties refresh,
                               DeviceIdCookieProperties device) {
        validate("jwt", jwt.secure(), jwt.sameSite());
        validate("refresh_token", refresh.secure(), refresh.sameSite());
        validate("device_id", device.secure(), device.sameSite());
    }

    static void validate(String name, boolean secure, String sameSite) {
        if (sameSite == null || !Set.of("None", "Lax", "Strict").contains(sameSite)) {
            throw new IllegalArgumentException("APP_COOKIE_SAME_SITE must be None, Lax, or Strict.");
        }
        if ("None".equals(sameSite) && !secure) {
            throw new IllegalArgumentException("The " + name
                    + " cookie uses SameSite=None and must be Secure or browsers will reject it.");
        }
    }
}
