package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * 【登录拦截器】—— 第二层拦截器（只拦截需要登录的路径）
 *
 * ⭐ 这个拦截器的核心职责：【强制登录校验】
 *
 * 和 RefreshTokenInterceptor 的关系（两层拦截器配合）：
 *
 *   ┌────────────────────────────────────────────────────────────┐
 *   │  第一层 RefreshTokenInterceptor（后勤保障）                 │
 *   │    拦截所有路径 → 查 Redis → 存 ThreadLocal → 刷新 Token    │
 *   │    即使没 Token 也 return true 放行，不做拦截决策            │
 *   ├────────────────────────────────────────────────────────────┤
 *   │  第二层 LoginInterceptor（守门拦截）⭐ 当前这个类            │
 *   │    只拦截需要登录的路径 → 查 ThreadLocal 有没有用户           │
 *   │    没有 → 401 + return false 拦截！                        │
 *   │    有   → return true 放行                                 │
 *   └────────────────────────────────────────────────────────────┘
 *
 * 为什么 LoginInterceptor 不直接查 Redis？
 *   ① 第一层已经查过了，ThreadLocal 里有就代表 Redis 里有
 *      再查一遍 Redis 完全是多余的 IO 开销
 *   ② 职责分离：第一层负责"查 + 存"，第二层负责"校验 + 拦截"
 *   ③ ThreadLocal 是内存操作，比查 Redis 快得多
 *
 * 为什么这个类没有 @Resource 注入？
 *   因为它不需要任何 Bean！它只调用 UserHolder.getUser()（静态方法）
 *   所以 MvcConfig 里可以直接：new LoginInterceptor() 不传任何参数
 *   对比 RefreshTokenInterceptor 需要 stringRedisTemplate，所以用构造器注入
 *
 * implements HandlerInterceptor —— Spring MVC 拦截器接口
 *   本类只覆写了 preHandle（前置处理）
 *   postHandle 和 afterCompletion 都没写（因为不需要）
 */
public class LoginInterceptor implements HandlerInterceptor {

    // ==================== preHandle：请求进入 Controller 之前执行 ====================
    // 返回 true  → 放行，继续往下走（到达 Controller）
    // 返回 false → 中断，请求到此为止
    //
    // 参数说明：
    //   request  —— HTTP 请求对象，可以获取 Header、Cookie、参数、Session 等
    //   response —— HTTP 响应对象，可以设置状态码、Header、响应体等
    //   handler  —— 当前请求要执行的处理器（一般是 Controller 方法的封装对象）
    //               可以用它拿到 Controller 方法的注解、方法名等信息
    //               本类没用上，所以忽略

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // ==================== 第 1 步：从 ThreadLocal 拿用户 ====================

        // UserHolder.getUser() —— 项目自定义的 ThreadLocal 工具类
        // 内部做的事：return threadLocal.get();
        //
        // 这里能拿到数据的前提：RefreshTokenInterceptor 一定先执行过！
        // 两层拦截器的执行顺序由 MvcConfig.addInterceptors() 的注册顺序决定：
        //   先注册 RefreshTokenInterceptor → 先执行 preHandle
        //   后注册 LoginInterceptor        → 后执行 preHandle
        //
        // 所以到 LoginInterceptor 这里时：
        //   ✅ ThreadLocal 有用户 → 说明第一层查到了合法 Token，Redis 里也有数据
        //   ❌ ThreadLocal 没用户 → 两种可能：
        //       ① 用户根本没带 Token（没登录）
        //       ② 用户带了 Token 但 Redis 里查不到（过期/无效）
        UserDTO user = UserHolder.getUser();

        // ==================== 第 2 步：判断是否为空 ====================

        if (user == null) {
            // 没登录！拦截请求

            // response.setStatus(401) —— 设置 HTTP 响应状态码
            // 401 = Unauthorized（未授权）
            // 这是 HTTP 协议专门用来表示"需要身份认证"的状态码
            //
            // 常见 HTTP 状态码对比：
            //   200 OK         —— 请求成功
            //   302 Found      —— 重定向
            //   400 Bad Request —— 请求参数错误
            //   401 Unauthorized —— 未登录/Token 无效 ⭐ 本场景
            //   403 Forbidden   —— 已登录但没权限（比如普通用户访问管理员接口）
            //   404 Not Found   —— 资源不存在
            //   500 Internal Server Error —— 服务器内部异常
            //
            // 前端收到 401 后应该做什么？
            //   ① 清除本地 Token：localStorage.removeItem("token")
            //   ② 跳转登录页：window.location.href = "/login.html"
            response.setStatus(401);

            // return false —— 中断请求流程
            // 不会到达 Controller
            // 也不会执行后续的拦截器（如果有的话）
            // 直接进入 DispatcherServlet 的 afterCompletion 阶段
            return false;
        }

        // ==================== 第 3 步：放行 ====================

        // ThreadLocal 里有用户 → 第一层已经验过 Token 的有效性
        // 可以放心放行，请求到达 Controller
        // Controller 里可以直接 UserHolder.getUser() 拿到用户信息
        return true;
    }

    // ❓ 为什么没有 afterCompletion 来清理 ThreadLocal？
    //
    // 因为 RefreshTokenInterceptor.afterCompletion 已经清理过了！
    // 两层拦截器都有 afterCompletion，都会执行：
    //   请求正常走完 → RefreshTokenInterceptor.afterCompletion() 清理 ThreadLocal
    //                 → LoginInterceptor 也没有 afterCompletion，没关系
    //   或者你可以加上，双重清理也没坏处（只是多余）
    //
    // 如果 RefreshTokenInterceptor preHandle 返回 false（没 Token 直接放行），
    // 那 ThreadLocal 本来就是空的，也不需要清理
}