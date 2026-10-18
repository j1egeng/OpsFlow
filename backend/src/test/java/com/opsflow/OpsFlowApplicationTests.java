package com.opsflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsFlowApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void returnsServiceInformationOverHttp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/system/info", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("OpsFlow Backend", "0.0.1-SNAPSHOT");
    }

    @Test
    void healthCheckIncludesAWorkingTestDatabase() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).doesNotContain("components", "jdbc:");
    }

    @Test
    void acceptsFrontendCorsPreflight() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:5173");
        headers.setAccessControlRequestMethod(HttpMethod.GET);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/system/info", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");
    }

    @Test
    void rejectsUnconfiguredCorsOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://unconfigured.example");
        headers.setAccessControlRequestMethod(HttpMethod.GET);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/system/info", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }
}
