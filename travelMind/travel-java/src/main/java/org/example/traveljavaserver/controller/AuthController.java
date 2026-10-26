package org.example.traveljavaserver.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.dto.AuthRequestDTO;
import org.example.traveljavaserver.service.AuthService;
import org.example.traveljavaserver.vo.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口：注册 / 登录，统一前缀 /api/auth
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** POST /api/auth/register 注册并直接返回 Token */
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Valid @RequestBody AuthRequestDTO dto) {
        return authService.register(dto.getUsername(), dto.getPassword());
    }

    /** POST /api/auth/login 登录返回 Token */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody AuthRequestDTO dto) {
        return authService.login(dto.getUsername(), dto.getPassword());
    }
}
