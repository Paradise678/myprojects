/**
 * 用户状态 Store（Pinia）
 * 职责：
 * 1. 维护登录态：token + username
 * 2. localStorage 持久化，刷新页面不丢登录状态
 * 3. 提供 login / logout 方法，logout 会清理本地会话 ID
 */
import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
    state: () => ({
        // JWT 令牌，登录成功后写入，请求时放入 Authorization 头
        token: localStorage.getItem('token') || '',
        // 当前登录用户名
        username: localStorage.getItem('username') || ''
    }),
    getters: {
        // 是否已登录
        isLoggedIn: (state) => !!state.token
    },
    actions: {
        /**
         * 登录成功：保存 token 与用户名，并持久化到 localStorage
         */
        login(token, username) {
            this.token = token
            this.username = username
            localStorage.setItem('token', token)
            localStorage.setItem('username', username)
        },
        /**
         * 退出登录：清空登录态与会话 ID（会话记忆按 sessionId 隔离，换号需换新会话）
         */
        logout() {
            this.token = ''
            this.username = ''
            localStorage.removeItem('token')
            localStorage.removeItem('username')
            localStorage.removeItem('chatSessionId')
        }
    }
})
