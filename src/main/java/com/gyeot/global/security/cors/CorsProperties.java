package com.gyeot.global.security.cors;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "gyeot.security.cors")
public class CorsProperties {

    // 설정이 없을 때의 기본값: FE 로컬 개발 서버(Vite) 주소. application.yaml 기본값과 같게 유지
    private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173"));

    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
}
