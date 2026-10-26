package org.example.traveljavaserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体：账号密码登录，密码以 BCrypt 哈希存储
 */
@Data
@TableName("tm_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;      // 登录账号（唯一）
    private String password;      // BCrypt 哈希，绝不存明文
    private LocalDateTime createTime;
}
