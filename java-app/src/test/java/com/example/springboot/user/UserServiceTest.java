package com.example.springboot.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository repository;

    @InjectMocks
    private UserService service;

    @Test
    void findAllReturnsAllUsers() {
        when(repository.findAll()).thenReturn(List.of(new User("Ada", "ada@example.com")));

        List<User> result = service.findAll();

        assertThat(result).hasSize(1).first().extracting(User::getName).isEqualTo("Ada");
    }

    @Test
    void findByIdReturnsUserWhenPresent() {
        User user = new User("Ada", "ada@example.com");
        user.setId(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(user));

        assertThat(service.findById(1L)).isSameAs(user);
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void createPersistsNewUser() {
        User input = new User("Ada", "ada@example.com");
        User saved = new User("Ada", "ada@example.com");
        saved.setId(7L);
        when(repository.save(any(User.class))).thenReturn(saved);

        User result = service.create(input);

        assertThat(result.getId()).isEqualTo(7L);
        // The service must clear the input id so a malicious or stale payload
        // can't overwrite an existing row.
        assertThat(input.getId()).isNull();
        verify(repository).save(input);
    }

    @Test
    void deleteRemovesExistingUser() {
        when(repository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(repository).deleteById(1L);
    }

    @Test
    void deleteThrowsWhenMissing() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(UserNotFoundException.class);

        verify(repository, never()).deleteById(any());
    }
}
