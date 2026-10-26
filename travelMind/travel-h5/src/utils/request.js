/**
 * HTTP 请求封装模块
 * 职责：
 * 1. 基于 axios 创建统一的请求实例，配置后端地址、超时时间、请求头
 * 2. 通过拦截器统一处理响应数据（自动解包后端 Result 包装的 data 字段）
 * 3. 对外暴露 get / post 普通请求方法，以及 fetchStream 流式请求方法（用于 AI 对话 SSE）
 *
 * 后端接口约定：
 *   普通接口返回结构：{ success, code, message, data, error }
 *   响应拦截器会自动取出 data 字段返回给业务层，业务层直接拿到业务数据。
 */
import axios from 'axios'
import router from '../router'

// 创建 axios 实例（业务接口）
const request = axios.create({
    baseURL: '/api/travel',  // 相对路径，开发环境由 Vite 代理、生产环境由 Nginx 反向代理到后端
    timeout: 300000,                               // 请求超时时间（毫秒），AI 生成较慢故设为 5 分钟
    headers: {
        'Content-Type': 'application/json'          // 统一以 JSON 格式发送请求体
    }
})

// 认证接口实例（登录/注册走 /api/auth 前缀，无需携带 Token）
const authRequest = axios.create({
    baseURL: '/api/auth',
    timeout: 30000,
    headers: {
        'Content-Type': 'application/json'
    }
})

// ===== 请求拦截器：自动携带 JWT Token =====
request.interceptors.request.use(
    config => {
        const token = localStorage.getItem('token')
        if (token) {
            config.headers.Authorization = `Bearer ${token}`
        }
        return config
    },
    error => {
        return Promise.reject(error)
    }
)

/**
 * 401 统一处理：Token 缺失/过期 → 清空登录态并跳转登录页
 */
const handleUnauthorized = () => {
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    const current = router.currentRoute.value
    // 已在登录页时不重复跳转，避免循环
    if (current.name !== 'Login') {
        router.replace({ path: '/login', query: { redirect: current.fullPath } })
    }
}

// ===== 响应拦截器：统一处理后端返回的数据 =====
// 后端返回结构为 { success, code, message, data, error }，
// 这里自动取出 response.data.data（即业务数据）返回，业务层无需手动解包。
// HTTP 401 表示未登录/Token 失效，统一清登录态并跳登录页；
// 业务失败（success=false）时按 message/error 提示并 reject。
const onResponse = response => {
    const body = response.data
    // 业务失败（如密码错误）：抛出带后端提示信息的错误，由调用方 showToast
    if (body && body.success === false) {
        return Promise.reject(new Error(body.message || body.error || '请求失败'))
    }
    return body.data   // 直接返回业务数据，抛掉外层的 Result 包装
}
const onResponseError = error => {
    if (error.response && error.response.status === 401) {
        handleUnauthorized()
    }
    return Promise.reject(error)
}

request.interceptors.response.use(onResponse, onResponseError)
authRequest.interceptors.response.use(onResponse, onResponseError)

/**
 * 发送 POST 请求
 * @param {string} url - 接口路径（会拼接到 baseURL 后面）
 * @param {object} data - 请求体参数
 * @returns {Promise} 后端返回的业务数据（已被响应拦截器解包）
 */
export function post(url, data) {
    return request.post(url, data)
}

/**
 * 发送 GET 请求
 * @param {string} url - 接口路径
 * @param {object} params - URL 查询参数
 * @returns {Promise} 后端返回的业务数据
 */
export function get(url, params) {
    return request.get(url, { params })
}

/**
 * 发送认证相关 POST 请求（登录/注册，/api/auth 前缀）
 * @param {string} url - 接口路径（login / register）
 * @param {object} data - 请求体参数 { username, password }
 * @returns {Promise} 后端返回的业务数据（{ token, username }）
 */
export function authPost(url, data) {
    return authRequest.post(url, data)
}

// ===== 处理流式接口（SSE）：用于 AI 对话，边生成边显示 =====

/**
 * 发起流式 POST 请求，逐块接收 AI 返回的内容
 * 与普通 post 不同，这里使用原生 fetch + ReadableStream 实现，
 * 因为 axios 对 SSE 流式响应的支持不够直接。
 *
 * @param {string} url - 接口路径
 * @param {object} data - 请求体（如 { message: '用户问题' }）
 * @param {function} onChunk - 每收到一块内容时的回调，参数为该块文本
 * @param {function} onComplete - 流结束时的回调
 * @param {function} onError - 出错时的回调，参数为错误信息
 */
export async function fetchStream(url, data, onChunk, onComplete, onError) {
    // 中止控制器，用于在需要时主动中断请求
    const controller = new AbortController()

    try {
        // 发起请求（注意：流式接口地址与普通接口一致，都走后端 /api/travel 前缀）
        // 手动携带 JWT Token（fetch 不走 axios 拦截器）
        const headers = {
            'Content-Type': 'application/json'
        }
        const token = localStorage.getItem('token')
        if (token) {
            headers.Authorization = `Bearer ${token}`
        }
        const response = await fetch(`/api/travel/${url}`, {
            method: 'POST',
            headers,
            body: JSON.stringify(data),
            signal: controller.signal   // 绑定中止信号
        })

        // Token 失效/未登录：清空登录态并跳登录页
        if (response.status === 401) {
            handleUnauthorized()
            onError('登录已过期，请重新登录')
            return
        }

        // 获取响应体的可读流读取器，用于逐块读取数据
        const reader = response.body.getReader()
        console.log(reader)
        console.log(await reader.read(), 'read')
        // 文本解码器，把二进制流解码为字符串
        const decoder = new TextDecoder()

        // 循环读取流，直到流结束（done === true）
        while (true) {
            // done 表示流是否结束，value 表示当前读取到的二进制数据块
            const { done, value } = await reader.read()
            if (done) break
            // 将二进制块解码为字符串，stream:true 表示允许跨块拼接
            const chunk = decoder.decode(value, { stream: true })

            // SSE 协议按行分隔，每行格式为 "data: {json}"
            const lines = chunk.split('\n').filter(line => line.trim())

            for (const line of lines) {
                // data: {"type":"chunk","content":"你好"}
                console.log(line)
                try {
                    if (line.startsWith('data:')) {
                        // 去掉 "data:" 前缀，取出 JSON 字符串
                        const jsonStr = line.substring(5)
                        if (jsonStr) {
                             const jsonData = JSON.parse(jsonStr)
                            // 根据后端推送的消息类型分发处理
                            if (jsonData.type === 'chunk') {
                                // 普通内容分片：累加到页面上
                                onChunk(jsonData.content)
                            } else if (jsonData.done) {
                                // 流结束标记：通知调用方完成
                                onComplete()
                            } else if (jsonData.error) {
                                // 后端推送的错误：通知调用方出错
                                onError(jsonData.error)
                            }
                        }
                    }
                } catch (error) {
                    onError('流式数据解析异常')
                }
            }
        }
        // 读取完毕，主动中止控制器释放资源
        return controller.abort()
    } catch (error) {
        // 网络等异常，走错误回调
        onError(error.message)
    }
}
