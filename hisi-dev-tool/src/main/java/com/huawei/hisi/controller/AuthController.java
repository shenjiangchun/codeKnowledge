package com.huawei.hisi.controller;

import com.huawei.hisi.config.JwtTokenProvider;
import com.huawei.hisi.config.SecurityContext;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.User;
import com.huawei.hisi.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthController(UserRepository userRepo, PasswordEncoder passwordEncoder, JwtTokenProvider tokenProvider) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ApiResponse.error(400, "用户名和密码不能为空");
        }

        var userOpt = userRepo.findByUsername(username);
        if (userOpt.isEmpty() || !passwordEncoder.matches(password, userOpt.get().password())) {
            return ApiResponse.error(401, "用户名或密码错误");
        }

        var user = userOpt.get();
        String token = tokenProvider.generateToken(user.username(), user.role());
        return ApiResponse.success(Map.of(
                "token", token,
                "username", user.username(),
                "role", user.role()
        ));
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ApiResponse.error(400, "用户名和密码不能为空");
        }
        if (username.length() < 2 || username.length() > 32) {
            return ApiResponse.error(400, "用户名长度需在2-32之间");
        }
        if (password.length() < 4) {
            return ApiResponse.error(400, "密码长度不能少于4位");
        }
        if (userRepo.existsByUsername(username)) {
            return ApiResponse.error(409, "用户名已存在");
        }

        String encoded = passwordEncoder.encode(password);
        User user = userRepo.save(username, encoded, "MEMBER");
        String token = tokenProvider.generateToken(user.username(), user.role());
        return ApiResponse.success(Map.of(
                "token", token,
                "username", user.username(),
                "role", user.role()
        ));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, String>> me(HttpServletRequest request) {
        SecurityContext ctx = (SecurityContext) request.getAttribute(SecurityContext.ATTR_NAME);
        if (ctx == null) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(Map.of("username", ctx.username(), "role", ctx.role()));
    }
}
