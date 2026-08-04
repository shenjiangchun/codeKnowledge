package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.User;
import com.huawei.hisi.repository.UserRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepo;

    public UserController(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    @GetMapping
    public ApiResponse<List<User>> list() {
        return ApiResponse.success(userRepo.findAll());
    }

    @PutMapping("/{id}/role")
    public ApiResponse<Void> changeRole(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String role = body.get("role");
        if (!"ADMIN".equals(role) && !"MEMBER".equals(role)) {
            return ApiResponse.error(400, "角色必须是 ADMIN 或 MEMBER");
        }
        boolean ok = userRepo.updateRole(id, role);
        if (!ok) {
            return ApiResponse.error(404, "用户不存在");
        }
        return ApiResponse.success(null);
    }
}
