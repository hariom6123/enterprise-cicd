package com.example.springboot.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full-stack integration test for {@code /api/users}.
 * <p>
 * Runs under Failsafe (matches the {@code *IT} pattern). The pipeline's
 * {@code integration-tests} job starts a real {@code postgres:16-alpine}
 * service and sets {@code SPRING_DATASOURCE_URL}; if those env vars are
 * absent (e.g. {@code mvn verify} on a developer machine), the
 * {@code test} profile falls back to H2 in-memory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserControllerIT {

    @Autowired
    private UserController controller;

    @Autowired
    private UserRepository repository;

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void createAndFetchRoundTrip() {
        ResponseEntity<User> created = controller.create(new User("Ada", "ada@example.com"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long id = created.getBody().getId();
        assertThat(id).isNotNull();

        User fetched = controller.get(id);
        assertThat(fetched.getName()).isEqualTo("Ada");
        assertThat(fetched.getEmail()).isEqualTo("ada@example.com");
    }

    @Test
    void updateChangesFields() {
        User created = controller.create(new User("Ada", "ada@example.com")).getBody();

        User updated = controller.update(created.getId(),
                new User("Ada Lovelace", "ada@example.com"));

        assertThat(updated.getName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void deleteRemovesUser() {
        User created = controller.create(new User("Ada", "ada@example.com")).getBody();

        controller.delete(created.getId());

        assertThat(repository.findById(created.getId())).isEmpty();
    }
}
