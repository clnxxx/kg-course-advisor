package com.clnx.kg_course_advisor.controller;

import com.clnx.kg_course_advisor.dto.AuthResponse;
import com.clnx.kg_course_advisor.entity.mysql.User;
import com.clnx.kg_course_advisor.service.JwtService;
import com.clnx.kg_course_advisor.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private JwtService jwtService;

    private AuthController authController;

    @BeforeEach
    void setUp() {
        authController = new AuthController(userService, jwtService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginReturnsTokenAndUserInfoWhenCredentialsAreValid() {
        User user = User.builder()
                .id(1L)
                .username("alice")
                .password("encoded")
                .email("alice@example.com")
                .realName("Alice")
                .major("Software Engineering")
                .grade(4)
                .role(User.UserRole.STUDENT)
                .build();
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("alice");
        request.setPassword("secret");

        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(userService.validatePassword(user, "secret")).thenReturn(true);
        when(jwtService.generateToken(1L, "alice", "STUDENT")).thenReturn("jwt-token");

        ResponseEntity<AuthResponse> response = authController.login(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getToken()).isEqualTo("jwt-token");
        assertThat(response.getBody().getUser().getUsername()).isEqualTo("alice");
        verify(userService).syncUserToNeo4jQuietly(user);
    }

    @Test
    void getCurrentUserReturnsUnauthorizedWhenNoAuthenticationExists() {
        ResponseEntity<?> response = authController.getCurrentUser();

        assertThat(response.getStatusCode().value()).isEqualTo(401);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("success", false);
    }

    @Test
    void getCurrentUserScheduleReturnsCourseSummary() {
        User user = User.builder()
                .id(1L)
                .username("alice")
                .realName("Alice")
                .role(User.UserRole.STUDENT)
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of())
        );
        when(userService.findById(1L)).thenReturn(Optional.of(user));
        Map<String, Object> firstCourse = new LinkedHashMap<>();
        firstCourse.put("courseId", "C001");
        firstCourse.put("courseName", "数据结构");
        firstCourse.put("timeId", "T1");
        Map<String, Object> secondCourse = new LinkedHashMap<>();
        secondCourse.put("courseId", "C002");
        secondCourse.put("courseName", "离散数学");
        secondCourse.put("timeId", null);
        when(userService.getUserSchedule(1L)).thenReturn(List.of(firstCourse, secondCourse));

        ResponseEntity<?> response = authController.getCurrentUserSchedule();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("userId", 1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) body.get("summary");
        assertThat(summary)
                .containsEntry("totalCourses", 2)
                .containsEntry("scheduledCourses", 1L)
                .containsEntry("unscheduledCourses", 1L);
    }
}
