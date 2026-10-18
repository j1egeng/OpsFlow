package com.opsflow.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final String version;

    public SystemController(@Value("${app.version}") String version) {
        this.version = version;
    }

    @GetMapping("/info")
    public ServiceInfo info() {
        return new ServiceInfo("OpsFlow Backend", version);
    }

    public record ServiceInfo(String name, String version) {
    }
}
