package com.clnx.kg_course_advisor.controller;

import com.clnx.kg_course_advisor.dto.AuthResponse;
import com.clnx.kg_course_advisor.entity.mysql.User;
import com.clnx.kg_course_advisor.service.JwtService;
import com.clnx.kg_course_advisor.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        try {
            User user = userService.register(
                    request.getUsername(),
                    request.getPassword(),
                    request.getEmail(),
                    request.getStudentId(),
                    request.getRealName(),
                    request.getMajor(),
                    request.getGrade()
            );

            String token = jwtService.generateToken(
                    user.getId(),
                    user.getUsername(),
                    user.getRole().name()
            );

            log.info("User {} registered successfully", user.getUsername());
            return ResponseEntity.ok(AuthResponse.success("注册成功", token, buildUserInfo(user)));
        } catch (Exception e) {
            log.error("Register failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(AuthResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        try {
            User user = userService.findByUsername(request.getUsername())
                    .orElseThrow(() -> new RuntimeException("用户不存在"));

            if (!userService.validatePassword(user, request.getPassword())) {
                throw new RuntimeException("密码错误");
            }

            // Login should not fail just because Neo4j is temporarily unavailable.
            userService.syncUserToNeo4jQuietly(user);

            String token = jwtService.generateToken(
                    user.getId(),
                    user.getUsername(),
                    user.getRole().name()
            );

            log.info("User {} logged in successfully", user.getUsername());
            return ResponseEntity.ok(AuthResponse.success("登录成功", token, buildUserInfo(user)));
        } catch (Exception e) {
            log.error("Login failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(AuthResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        Long userId = extractCurrentUserId();
        if (userId == null) {
            return unauthorized("未登录或登录已过期");
        }

        return userService.findById(userId)
                .map(user -> ResponseEntity.ok(buildProfileResponse(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/me/schedule")
    public ResponseEntity<?> getCurrentUserSchedule() {
        Long userId = extractCurrentUserId();
        if (userId == null) {
            return unauthorized("未登录或登录已过期");
        }

        return userService.findById(userId)
                .map(user -> {
                    List<Map<String, Object>> courses = userService.getUserSchedule(userId);
                    long scheduledCourses = courses.stream().filter(item -> item.get("timeId") != null).count();

                    Map<String, Object> response = new HashMap<>();
                    response.put("userId", user.getId());
                    response.put("displayName", user.getRealName() != null ? user.getRealName() : user.getUsername());
                    response.put("courses", courses);
                    response.put("summary", Map.of(
                            "totalCourses", courses.size(),
                            "scheduledCourses", scheduledCourses,
                            "unscheduledCourses", courses.size() - scheduledCourses
                    ));
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/me")
    public ResponseEntity<?> updateCurrentUser(@Valid @RequestBody UpdateProfileRequest request) {
        Long userId = extractCurrentUserId();
        if (userId == null) {
            return unauthorized("未登录或登录已过期");
        }

        try {
            User updatedUser = userService.updateProfile(
                    userId,
                    request.getEmail()
            );
            return ResponseEntity.ok(buildProfileResponse(updatedUser));
        } catch (RuntimeException e) {
            log.error("Update profile failed: userId={}, message={}", userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/profile/{userId}")
    public ResponseEntity<?> getProfile(@PathVariable Long userId) {
        if (extractCurrentUserId() == null) {
            return unauthorized("未登录或登录已过期");
        }

        return userService.findById(userId)
                .map(user -> ResponseEntity.ok(buildProfileResponse(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    private Long extractCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        return principal instanceof Long userId ? userId : null;
    }

    private ResponseEntity<Map<String, Object>> unauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "success", false,
                "message", message
        ));
    }

    private AuthResponse.UserInfo buildUserInfo(User user) {
        return AuthResponse.UserInfo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .studentId(user.getStudentId())
                .realName(user.getRealName())
                .major(user.getMajor())
                .grade(user.getGrade())
                .role(user.getRole().name())
                .build();
    }

    private Map<String, Object> buildProfileResponse(User user) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", user.getId());
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("studentId", user.getStudentId());
        response.put("realName", user.getRealName());
        response.put("major", user.getMajor());
        response.put("grade", user.getGrade());
        response.put("role", user.getRole().name());
        return response;
    }

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;

        @NotBlank(message = "密码不能为空")
        private String password;

        @Email(message = "邮箱格式不正确")
        private String email;

        private String studentId;
        private String realName;
        private String major;

        @Min(value = 1, message = "年级不能小于 1")
        @Max(value = 6, message = "年级不能大于 6")
        private Integer grade;
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;

        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @Data
    public static class UpdateProfileRequest {
        @Email(message = "邮箱格式不正确")
        private String email;
    }
}
