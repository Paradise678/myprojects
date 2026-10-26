/**
 * 前端路由配置
 * 定义了整个 H5 应用的页面与 URL 的映射关系。
 * 使用 createWebHistory（History 模式），URL 更干净，无 # 号。
 * 所有页面组件采用懒加载（() => import(...)），按需加载，减小首屏体积。
 */
import { createRouter, createWebHistory } from 'vue-router'

// 路由表：path 对应 URL，component 对应该 URL 渲染的页面组件
const routes = [
    {
        path: '/',           // 首页：用户填写目的地、预算、天数，发起行程规划
        name: 'Home',
        component: () => import('../views/Home.vue')
    },
    {
        path: '/chat',       // AI 对话页：与大模型实时流式聊天，咨询旅游问题
        name: 'Chat',
        component: () => import('../views/Chat.vue')
    },
    {
        path: '/profile',    // 我的页：个人中心（收藏、历史、设置等占位功能）
        name: 'Profile',
        component: () => import('../views/Profile.vue')
    },
    {
        path: '/detail',     // 行程详情页：展示 AI 生成的多日行程规划结果
        name: 'Detail',
        component: () => import('../views/Detail.vue')
    },
    {
        path: '/login',      // 登录/注册页：账号密码登录，新用户可切换注册
        name: 'Login',
        component: () => import('../views/Login.vue')
    },
    {
        path: '/history',    // 历史行程页：展示当前账号的历史行程规划记录
        name: 'History',
        component: () => import('../views/History.vue')
    }
]

// 创建路由实例
const router = createRouter({
    history: createWebHistory(),  // 使用浏览器 History API，URL 不带 #
    routes                        // 挂载上面定义的路由表
})

// ===== 全局前置守卫：未登录访问受保护页面时重定向到登录页 =====
// 登录后通过 redirect 参数跳回原来的目标页面
router.beforeEach((to) => {
    const token = localStorage.getItem('token')
    if (!token && to.name !== 'Login') {
        return { path: '/login', query: { redirect: to.fullPath } }
    }
    return true
})

export default router
