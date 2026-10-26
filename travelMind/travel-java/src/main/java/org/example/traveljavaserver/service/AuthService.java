package org.example.traveljavaserver.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.entity.User;
import org.example.traveljavaserver.mapper.UserMapper;
import org.example.traveljavaserver.utils.JwtUtils;
import org.example.traveljavaserver.vo.Result;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证服务：账号密码注册 / 登录，签发 JWT
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtils jwtUtils;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 注册：用户名唯一校验 → BCrypt 哈希存库 → 直接签发 Token 免登录
     */
    public Result<Map<String, Object>> register(String username, String password) {
        Long count = userMapper.selectCount(new QueryWrapper<User>().eq("username", username));
        if (count != null && count > 0) {
            return Result.fail(400, "用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setCreateTime(LocalDateTime.now());
        userMapper.insert(user);
        return Result.ok(tokenBody(user));
    }

    /**
     * 登录：校验用户名存在 + BCrypt 密码匹配 → 签发 Token
     */
    public Result<Map<String, Object>> login(String username, String password) {
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", username));
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            // 不区分"用户不存在"和"密码错误"，避免账号枚举
            return Result.fail(400, "用户名或密码错误");
        }
        return Result.ok(tokenBody(user));
    }

    private Map<String, Object> tokenBody(User user) {
        Map<String, Object> body = new HashMap<>();
        body.put("token", jwtUtils.generate(user.getId(), user.getUsername()));
        body.put("username", user.getUsername());
        return body;
    }
}
