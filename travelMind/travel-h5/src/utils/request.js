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

// 创建 axios 实例
const request = axios.create({
    baseURL: '/api/travel',  // 相对路径，开发环境由 Vite 代理、生产环境由 Nginx 反向代理到后端
    timeout: 300000,                               // 请求超时时间（毫秒），AI 生成较慢故设为 5 分钟
    headers: {
        'Content-Type': 'application/json'          // 统一以 JSON 格式发送请求体
    }
})


// ===== 请求拦截器：在请求发出前做统一处理（如加 token），目前直接放行 =====
request.interceptors.request.use(
    config => {
        return config
    },
    error => {
        return Promise.reject(error)
    }
)

// ===== 响应拦截器：统一处理后端返回的数据 =====
// 后端返回结构为 { success, code, message, data, error }，
// 这里自动取出 response.data.data（即业务数据）返回，业务层无需手动解包。
request.interceptors.response.use(
    response => {
        return response.data.data   // 直接返回业务数据，抛掉外层的 Result 包装
    },
    error => {
        return Promise.reject(error)
    }
)

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
        const response = await fetch(`/api/travel/${url}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(data),
            signal: controller.signal   // 绑定中止信号
        })

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
