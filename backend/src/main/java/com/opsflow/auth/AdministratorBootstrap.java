package com.opsflow.auth;

import com.opsflow.users.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.bootstrap.admin.enabled", havingValue = "true")
public class AdministratorBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdministratorBootstrap.class);
    private final UserService users;
    private final String username;
    private final String email;
    private final String displayName;
    private final String password;

    public AdministratorBootstrap(UserService users,
                                  @Value("${app.bootstrap.admin.username}") String username,
                                  @Value("${app.bootstrap.admin.email}") String email,
                                  @Value("${app.bootstrap.admin.display-name}") String displayName,
                                  @Value("${app.bootstrap.admin.password}") String password) {
        this.users = users;
        this.username = username;
        this.email = email;
        this.displayName = displayName;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.bootstrapAdministrator(username, email, displayName, password)) {
            log.info("Initial administrator created from explicit local configuration.");
        } else {
            log.info("Administrator bootstrap skipped: an enabled administrator already exists.");
        }
    }
}
