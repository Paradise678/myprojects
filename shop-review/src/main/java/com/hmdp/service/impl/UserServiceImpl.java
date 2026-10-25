package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
/**
 * UserService 实现类
 *
 * @Slf4j  —— Lombok 注解，编译时自动生成一个 private static final 的日志对象
 *           有了它就可以直接用 log.info() / log.debug() / log.error() 打印日志
 *
 * @Service —— Spring 注解，把当前类注册为 Spring Bean（组件）
 *            相当于告诉 Spring 容器："扫描到我时，请帮我创建一个实例并管理生命周期"
 *            这样其他类就可以用 @Resource / @Autowired 注入这个 Service 了
 *
 * extends ServiceImpl<UserMapper, User> —— 继承 MyBatis-Plus 提供的通用 Service 实现
 *   UserMapper —— 当前 Service 使用的 Mapper 接口（负责数据库访问）
 *   User       —— 操作的实体类（对应 tb_user 表）
 *   继承之后自动拥有了 selectById / save / updateById / removeById 等基础 CRUD 方法
 *
 * implements IUserService —— 实现自定义的业务接口
 *   接口里定义了 sendCode / login / logout 等业务方法签名
 */
@Slf4j      // lombok注解：自动生成日志对象log，用于打印日志输出
@Service    // 将当前类交给Spring容器管理，标记为业务层Service组件
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    // Spring 注解：从容器中自动注入一个 StringRedisTemplate 对象
    // StringRedisTemplate 是 Spring Data Redis 提供的模板类，专门用来操作 Redis 的 String 类型数据
    // 它内部封装了连接管理、序列化等底层细节，我们直接调用方法即可
    // 这个 Bean 是 Spring Boot 自动配置的（pom.xml 引入 spring-boot-starter-data-redis 就会自动有）
    @Resource
    private StringRedisTemplate stringRedisTemplate;


// ===================== sendCode：发送验证码 =====================

    /**
     * 发送手机验证码
     *
     * 执行流程：
     *   ① 校验手机号格式 → ② 生成随机验证码 → ③ 存 Redis（带过期）→ ④ 打日志模拟发短信 → ⑤ 返回成功
     *
     * @param phone   前端传来的手机号（比如 "13800138000"）
     *                前端请求示例：POST /user/code?phone=13800138000
     *                Spring MVC 会自动把请求参数里的 phone 值绑定到这个方法参数上
     *
     * @param session HttpSession 对象（当前这个 Redis 版本里其实没用到，保留参数是为了兼容接口定义）
     *
     * @return Result 统一响应体
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {

        // -------------------- 第 1 步：校验手机号 --------------------

        // RegexUtils 是项目自定义的手机号校验工具类
        // isPhoneInvalid(phone) 内部实现：用正则判断手机号是否"无效"
        // 中国大陆手机号正则一般是：^1[3-9]\d{9}$
        // 返回 true 说明手机号格式不符合规范
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 格式不合法，直接返回失败，后面的代码不再执行
            // Result.fail() 是项目自定义的静态方法，构造一个"失败"响应
            // 前端收到的 JSON 大概是：{ "success": false, "errorMsg": "手机号格式错误！" }
            return Result.fail("手机号格式错误！");
        }

        // -------------------- 第 2 步：生成验证码 --------------------

        // RandomUtil.randomNumbers(6) —— Hutool 工具类
        // 生成一个长度为 6 的【纯数字】随机字符串，比如 "382914"
        // 注意：返回的是 String 类型，不是 int。因为存 Redis 都是字符串，String 类型更方便
        // 为什么选 Hutool？它是国人写的工具库，API 设计非常贴近日常开发场景
        String code = RandomUtil.randomNumbers(6);

        // -------------------- 第 3 步：存 Redis --------------------

        // stringRedisTemplate.opsForValue() —— 获取操作 Redis String 类型的对象
        //   Redis 有 5 大数据结构：String / Hash / List / Set / ZSet
        //   opsForValue() 对应 String 结构，opsForHash() 对应 Hash 结构，以此类推
        //
        // .set(key, value, timeout, timeUnit) —— 存值并设置过期时间
        //   key   = LOGIN_CODE_KEY + phone = "login:code:" + "13800138000"
        //   value = code = "382914"
        //   timeout = LOGIN_CODE_TTL = 2L（2 分钟）
        //   timeUnit = TimeUnit.MINUTES（时间单位：分钟）
        //
        // 这一行等价于 Redis 命令：SETEX login:code:13800138000 120 382914
        // SETEX = SET + EXPIRE（原子性的"存 + 设过期"，Redis 单条命令完成，不会中断）
        //
        // 为什么要设过期？短信验证码不应该永久有效。2 分钟是行业通用做法，
        // 既给用户足够时间输入，又防止验证码被窃取后长期可用
        stringRedisTemplate.opsForValue()
                .set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // -------------------- 第 4 步：模拟发短信 --------------------

        // log.debug() —— Lombok @Slf4j 注解自动生成的 SLF4J Logger 对象
        // 打印 DEBUG 级别的日志，只有当日志级别设为 DEBUG 或更低时才会输出
        // {} 是 SLF4J 的占位符，会被后面的 code 值替换
        //
        // 真实项目中，这行应该替换为调用第三方短信 SDK：
        //   阿里云短信 / 腾讯云短信 / 容联云 等
        //   大概写法：smsClient.sendSms(phone, "【黑马点评】您的验证码是" + code)
        //
        // 这里用 log.debug 模拟，是因为学习阶段没有真实的短信服务
        // 把验证码打到日志里，方便我们在后端控制台看到它来手动输入测试
        log.debug("发送短信验证码成功，手机号：{}，验证码：{}", phone, code);

        // -------------------- 第 5 步：返回成功 --------------------

        // Result.ok() —— 项目自定义的静态方法，构造一个"成功"响应
        // 前端收到的 JSON 大概是：{ "success": true, "data": null, "errorMsg": null }
        // 前端看到 success=true，就可以在页面上提示"验证码已发送"
        return Result.ok();
    }


// ===================== login：登录/注册 =====================

    /**
     * 用户登录（同时承担"首次登录即自动注册"的职责）
     *
     * 执行流程：
     *   ① 校验手机号 → ② 校验 Redis 中的验证码 → ③ 查数据库用户 → ④ 不存在则自动注册
     *   → ⑤ 生成 Token → ⑥ User→UserDTO 脱敏 → ⑦ UserDTO→Map → ⑧ 存 Redis Hash → ⑨ 设置过期 → ⑩ 返回 Token
     *
     * @param loginForm 前端传来的登录表单（JSON），包含 phone 和 code 两个字段
     *                  前端请求示例：POST /user/login
     *                  Body: { "phone": "13800138000", "code": "382914" }
     *                  @RequestBody 注解告诉 Spring 把 JSON 反序列化为 LoginFormDTO 对象
     *
     * @param session HttpSession 对象（当前这个 Redis 版本里没用到，保留参数是为了兼容接口定义）
     *
     * @return Result 成功时 data 是 Token 字符串，失败时是错误提示
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        // -------------------- 第 1 步：校验手机号 --------------------

        // 从 LoginFormDTO 对象中取出手机号
        // LoginFormDTO 是一个 Lombok @Data 类，有 phone 和 code 两个字段
        String phone = loginForm.getPhone();

        // 和 sendCode 一样的手机号格式校验
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        // -------------------- 第 2 步：校验验证码 --------------------

        // stringRedisTemplate.opsForValue().get(key) —— 从 Redis String 中取值
        // 等价于 Redis 命令：GET login:code:13800138000
        // 如果 key 不存在（比如验证码过期了、或者根本没发过），返回 null
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        // 从前端表单中取出用户输入的验证码
        String code = loginForm.getCode();

        // 校验逻辑（OR 判断，任一满足即失败）：
        //   cacheCode == null          —— Redis 里没找到这个 key
        //                                原因可能是：验证码过期了 / 没发过验证码 / 验证码已被使用
        //   !cacheCode.equals(code)    —— 用户输入的和 Redis 存的对不上
        //                                注意用 equals() 比较 String 内容，不能用 ==
        //                                （== 比较的是对象引用地址，不是值）
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        // -------------------- 第 3 步：查用户 --------------------

        // query() —— MyBatis-Plus IService 提供的链式查询入口
        //   内部会创建一个 QueryWrapper<User> 对象
        //   比传统的 new QueryWrapper<User>().eq("phone", phone) 写法更简洁
        //
        // .eq("phone", phone) —— 添加等值条件
        //   等价于 SQL：WHERE phone = '13800138000'
        //   eq = equals，还有 ne（不等于）、gt（大于）、lt（小于）、like（模糊）等条件方法
        //
        // .one() —— 执行查询，返回一条记录
        //   等价于 SQL：SELECT * FROM tb_user WHERE phone = ? LIMIT 1
        //   如果没查到，返回 null
        User user = query().eq("phone", phone).one();

        // -------------------- 第 4 步：不存在则自动注册 --------------------

        if (user == null) {
            // createUserWithPhone() —— 私有方法，根据手机号创建新用户并入库
            // 设计理念：移动端 App 常见的"免注册"设计
            // 用户只要有手机号就能登录，不需要单独走注册流程
            // 大大降低了新用户的使用门槛
            user = createUserWithPhone(phone);
            // 执行完之后，数据库中就有了这条 user 记录，user 对象也带上了自增主键 id
        }

        // -------------------- 第 5 步：生成 Token --------------------

        // UUID.randomUUID() —— Java 自带的 UUID 生成器
        //   生成一个 32 位的随机字符串，比如 "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        //   UUID 的特性是：在分布式环境下几乎不会重复（冲突概率约为 1/(2^122)）
        //
        // .toString(true) —— true 表示去掉中间的 4 个横线，生成 32 位紧凑字符串
        //   去掉横线后："a1b2c3d4e5f67890abcdef1234567890"
        //   为什么去掉？纯字符串传给前端更简洁，少占带宽
        //
        // 这个 Token 就是用户后续请求的"身份证"
        // 前端会把它存到 localStorage，每次请求放到 HTTP Header 的 Authorization 字段里
        String token = UUID.randomUUID().toString(true);

        // -------------------- 第 6 步：User → UserDTO（脱敏） --------------------

        // BeanUtil.copyProperties(user, UserDTO.class) —— Hutool 工具类
        // 把源对象（User）中【同名同类型】的属性复制到目标类（UserDTO）的新实例中
        //
        // 为什么要转 UserDTO？直接存 User 不行吗？
        // ❌ User 实体里有敏感字段：phone（手机号）、password（密码）
        //    把整个 User 存 Redis → 相当于把敏感信息暴露了
        //    如果 Redis 被入侵 → 用户手机号和密码直接泄露
        //
        // ✅ UserDTO 只包含 3 个安全字段：id / nickName / icon
        //    这是经典的 DTO 模式（Data Transfer Object，数据传输对象）
        //    实体类用于数据库映射，DTO 用于网络传输/缓存，各司其职
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        // -------------------- 第 7 步：UserDTO → Map --------------------

        // BeanUtil.beanToMap() —— 把 Java Bean 对象转换成 Map<String, Object>
        // 为什么要转 Map？因为接下来要用 Redis Hash 结构存数据
        // Redis Hash 的每个字段都是 key-value，和 Map 的结构天然匹配
        //
        // 三个参数分别是：
        //   ① userDTO              —— 源对象（要转换的 Java Bean）
        //   ② new HashMap<>()      —— 目标 Map（传入一个空 Map，方法会往里填数据）
        //   ③ CopyOptions.create()  —— 配置选项（链式调用设置规则）
        //
        // 配置选项详解：
        //   .setIgnoreNullValue(true) —— 忽略值为 null 的字段
        //     比如 userDTO.icon 如果是 null，就不存进去
        //     好处：减少 Redis 中的无效字段，节省内存
        //
        //   .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        //     字段值编辑器 —— 对每个字段的值做处理后再放入 Map
        //     这里的逻辑是：所有值都转成 String
        //     为什么？因为 StringRedisTemplate 操作的都是 String 类型
        //     UserDTO.id 是 Long 类型（比如 1L），必须转成 String "1" 才能存
        //
        // 转换后的结果：
        //   userMap = { "id": "1", "nickName": "用户aB3x", "icon": "" }
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        // -------------------- 第 8 步：存 Redis Hash --------------------

        // 构造 Redis Hash 的 key
        // "login:token:" + "a1b2c3d4e5f67890abcdef1234567890"
        String tokenKey = LOGIN_USER_KEY + token;

        // stringRedisTemplate.opsForHash() —— 获取操作 Redis Hash 类型的对象
        // Hash 是 Redis 的一种数据结构，像 Java 的 Map 一样，一个 key 下有多个 field-value
        //
        // .putAll(tokenKey, userMap) —— 一次性把 Map 中所有键值对写入 Redis Hash
        // 等价于 Redis 命令：
        //   HSET login:token:a1b2c3d4 id "1" nickName "用户aB3x" icon ""
        //
        // 为什么用 Hash 存用户信息？
        //     HSET login:token:a1b2c3d4 id "1" nickName "用户aB3x" icon ""
        //     优点：可以单独修改某一个字段，比如只更新昵称：
        //           HSET login:token:a1b2c3d4 nickName "新昵称"
        //           其他字段不受影响，原子操作，性能更好
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

        // -------------------- 第 9 步：设置过期时间 --------------------

        // expire(key, timeout, timeUnit) —— 给指定的 Redis key 设置过期时间
        //   key     = "login:token:a1b2c3d4e5f67890abcdef1234567890"
        //   timeout = LOGIN_USER_TTL = 30L
        //   timeUnit = TimeUnit.MINUTES
        //
        // 等价于 Redis 命令：EXPIRE login:token:a1b2c3d4 1800
        // 1800 秒 = 30 分钟
        //
        // 过期时间的含义：从【这一刻】开始计时，30 分钟后这个 key 自动被 Redis 删除
        // 但是项目中配合了 RefreshTokenInterceptor，用户每次请求都会刷新这个过期时间
        // 所以实际效果是：
        //   用户活跃（30 分钟内有请求）→ Token 续期，不会过期
        //   用户离线超过 30 分钟       → Token 过期，需要重新登录
        //
        // 这就是"滑动过期"机制，提升用户体验的同时保证安全
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // -------------------- 第 10 步：返回 Token --------------------

        // 把 Token 返回给前端！
        // 这是整个登录流程的关键输出
        //
        // 前端拿到 Token 后要做什么？
        //   1. 存到 localStorage：localStorage.setItem("token", "a1b2c3d4...")
        //   2. 后续每次请求都在 Header 里带上：
        //      fetch(url, { headers: { "Authorization": "a1b2c3d4..." } })
        //
        // 后端怎么验证这个 Token？
        //   RefreshTokenInterceptor 里：
        //     String token = request.getHeader("authorization");
        //     String key = "login:token:" + token;
        //     Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //     // 查到了 → 说明 Token 有效，用户已登录
        //     // 查不到（key 不存在）→ 要么 Token 错了，要么过期了
        return Result.ok(token);
    }
    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 根据手机号自动创建新用户（首次登录即注册）
     *
     * @param phone 用户手机号
     * @return 新创建的 User 实体
     */
    private User createUserWithPhone(String phone) {
        User user = new User();

        // 设置手机号（唯一标识）
        user.setPhone(phone);

        // 生成随机昵称："用户"前缀 + 10位随机字符串（如 "用户aB3xK9mN2p"）
        // USER_NICK_NAME_PREFIX 是 SystemConstants 中定义的常量，值为 "用户"
        // RandomUtil.randomString(10) 是 Hutool 工具类，生成 10 位随机字母/数字组合
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        // MyBatis-Plus 的 save 方法，自动执行 INSERT INTO tb_user ... 语句
        save(user);

        // 返回创建好的用户对象（此时数据库已经有这条记录了，主键 id 也已自动回填）
        return user;
    }
}

