package com.example.springboot.user;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD operations on {@link User}. Mutating methods run in a writable
 * transaction; reads inherit the class-level {@code readOnly = true} default
 * so Hibernate skips dirty-checking and the JDBC driver can optimize.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public List<User> findAll() {
        return repository.findAll();
    }

    public User findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @Transactional
    public User create(User user) {
        // No existsByEmail pre-check: the users.email column declares
        // unique = true, so the DB rejects duplicates via
        // DataIntegrityViolationException (mapped to HTTP 409 in
        // GlobalExceptionHandler). Pre-checking would race and waste a
        // round-trip.
        user.setId(null);
        return repository.save(user);
    }

    @Transactional
    public User update(Long id, User patch) {
        User existing = findById(id);
        existing.setName(patch.getName());
        existing.setEmail(patch.getEmail());
        return existing;  // managed entity — dirty checking flushes on commit
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new UserNotFoundException(id);
        }
        repository.deleteById(id);
    }
}
