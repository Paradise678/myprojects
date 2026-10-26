package org.example.traveljavaserver.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类：HS256 签名，有效期 7 天
 * Token 载荷：sub=username, uid=用户ID
 */
@Component
public class JwtUtils {

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtils(@Value("${jwt.secret}") String secret,
                    @Value("${jwt.expire-days:7}") long expireDays) {
        // HS256 要求密钥 ≥ 256bit
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireDays * 24 * 3600 * 1000;
    }

    /** 生成 Token：sub=用户名，uid=用户ID，7 天有效期 */
    public String generate(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析并校验 Token，失败（过期/篡改）抛异常 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
