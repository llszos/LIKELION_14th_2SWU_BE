package com.gyeot.global.security.cookie;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gyeot.security.cookie")
public class AuthCookieProperties {

    private String refreshTokenName = "refresh_token";
    private String path = "/api/auth";
    private String domain = "";
    private boolean secure;
    private String sameSite = "Lax";

    public String getRefreshTokenName() { return refreshTokenName; }
    public void setRefreshTokenName(String refreshTokenName) { this.refreshTokenName = refreshTokenName; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public boolean isSecure() { return secure; }
    public void setSecure(boolean secure) { this.secure = secure; }
    public String getSameSite() { return sameSite; }
    public void setSameSite(String sameSite) { this.sameSite = sameSite; }
}
