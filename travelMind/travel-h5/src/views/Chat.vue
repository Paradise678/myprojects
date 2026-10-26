<template>
  <!--
      AI 对话页
      业务流程：
        1. 用户输入问题（或点击常见问题标签），发送给后端 /chat 接口
        2. 后端以 SSE 流式方式逐字返回 AI 回复
        3. 前端边接收边显示，实现"打字机"效果
        4. 从详情页跳转过来时，会自动带上城市作为初始问题
    -->
  <div class="page-container chat-page">
    <div class="page-header">
      <van-nav-bar
        title="AI 旅游助手"
        left-arrow
        left-text="返回"
        fixed
        @click-left="onBack"
      />
    </div>
    <!-- 消息列表容器 -->
    <div class="chat-container" ref="chatContainer">
      <!-- 空状态：还没有任何消息时，显示引导 + 常见问题 -->
      <div v-if="messages.length === 0" class="chat-empty">
        <van-empty description="开始和 AI 助手对话吧！" />
        <div class="quick-questions">
          <div class="quick-title">常见问题</div>
          <!-- 点击标签直接发送该问题 -->
          <van-tag
            @click="handleClickTag(q)"
            v-for="q in quickQuestions"
            :key="q"
            size="large"
            mark
            class="quick-tag"
          >
            {{ q }}
          </van-tag>
        </div>
      </div>
      <!-- 消息列表：渲染所有历史消息气泡 -->
      <div v-else class="message-list">
        <ChatBubble v-for="msg in messages" :key="msg.id" :message="msg" />
        <!-- AI 正在流式输出时显示"思考中"提示 -->
        <div class="streaming-indicator" v-if="isStreaming">
          <van-loading type="spinner" size="20px" />
          <span>AI 正在思考中...</span>
        </div>
      </div>
    </div>
    <!-- 底部输入框区域（固定定位） -->
    <div class="chat-input-area">
      <van-field
        v-model="inputMessage"
        placeholder="输入您的问题..."
        :disabled="isStreaming"
        @keyup.enter="sendMessage"
      >
        <template #button>
          <van-button
            @click="sendMessage"
            type="primary"
            size="small"
            :disabled="!inputMessage.trim()"
            >发送</van-button
          >
        </template>
      </van-field>
    </div>
  </div>
</template>
<script setup>
import { ref, onMounted } from "vue";
import { useRouter, useRoute } from "vue-router";
import { fetchStream } from "../utils/request";
import { showToast } from "vant";
import ChatBubble from "../components/ChatBubble.vue";

// 消息列表容器的 DOM 引用，用于滚动到底部
const chatContainer = ref(null);
// 滚动到底部：新消息或流式内容更新后调用，保证最新内容可见
const scrollToBottom = () => {
  if (chatContainer.value) {
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
  }
};

const router = useRouter();
// 预置的常见问题，点击可直接发送
const quickQuestions = ref([
  "北京有哪些必去的景点？",
  "上海美食推荐",
  "成都三日游攻略",
  "如何选择旅行保险？",
]);
/**
 * 点击常见问题标签：把问题填入输入框并立即发送
 * @param {string} q - 问题文本
 */
const handleClickTag = (q) => {
  inputMessage.value = q;
  sendMessage();
};

// 会话消息列表，每条消息结构：{ id, role('user'|'ai'), content, timestamp }
const messages = ref([]);

// 返回上一页
const onBack = () => {
  router.back();
};
// 输入框绑定的文本
const inputMessage = ref("");
/**
 * 发送消息主流程：
 *   1. 校验输入非空且当前不在流式接收中
 *   2. 把用户消息加入列表
 *   3. 清空输入框
 *   4. 发起流式请求获取 AI 回复
 */
const sendMessage = () => {
  const msg = inputMessage.value.trim();
  if (!msg || isStreaming.value) {
    return;
  }
  addUserMessage(msg);
  inputMessage.value = "";
  // 进行流式请求
  fetchAIResponse(msg);
};

/**
 * 发起流式请求获取 AI 回复
 * 先在列表末尾追加一条空的 AI 消息占位，
 * 然后通过 fetchStream 逐块接收内容，实时更新这条消息的 content，
 * 从而实现边生成边显示的打字机效果。
 * @param {string} userMsg - 用户发送的问题
 */
const fetchAIResponse = (userMsg) => {
  isStreaming.value = true;
  // 先追加一条空的 AI 消息占位，后续流式内容会不断填充它
  messages.value.push({
    id: Date.now() + 1,
    role: "ai",
    content: "",
    timestamp: new Date().toISOString(),
  });

  let fullResponse = "";

  // 调用流式接口：三个回调分别处理"收到分片"、"流结束"、"出错"
  // 携带 sessionId：后端按会话隔离 Redis 中的历史记忆，实现多轮对话上下文
  fetchStream(
    "chat",
    { message: userMsg, sessionId: getSessionId() },
    (chunk) => {
      // 每收到一块内容，累加到 fullResponse 并更新到最后一条 AI 消息上
      fullResponse += chunk;
      const lastMsg = messages.value[messages.value.length - 1];
      if (lastMsg && lastMsg.role === "ai") {
        lastMsg.content = fullResponse;
      }
      scrollToBottom();
    },
    () => {
      // AI 返回完成：关闭流式状态
      isStreaming.value = false;
      scrollToBottom();
    },
    (errMsg) => {
      // 出错：在 AI 消息位置显示错误提示
      const lastMsg = messages.value[messages.value.length - 1];
      if (lastMsg && lastMsg.role === "ai") {
        lastMsg.content = `抱歉，AI发生错误：${errMsg}`;
      }
      isStreaming.value = false;
      showToast("ai回复失败！");
      scrollToBottom();
    }
  );
};
// 是否正在接收 AI 流式回复（true 时禁用输入框，防止重复发送）
const isStreaming = ref(false);

/**
 * 把用户消息加入消息列表
 * @param {string} content - 用户输入的文本
 */
const addUserMessage = (content) => {
  messages.value.push({
    id: Date.now(),
    role: "user",
    content,
    timestamp: new Date().toISOString(),
  });
};
const route = useRoute();

/**
 * 获取当前会话 ID：首次访问时生成 UUID 并存入 localStorage，
 * 后续对话复用同一 ID，后端据此从 Redis 加载该会话的历史消息（多轮记忆）。
 * 退出登录时会清空，换新账号/重新登录后自动开启新会话。
 */
const getSessionId = () => {
  let sid = localStorage.getItem("chatSessionId");
  if (!sid) {
    sid = crypto.randomUUID();
    localStorage.setItem("chatSessionId", sid);
  }
  return sid;
};

// 页面挂载时：如果是从详情页跳转过来（scene=detail），自动填入城市相关问题
onMounted(() => {
  if (route.query.scene === "detail" && route.query.city) {
    inputMessage.value = `我想了解一下${route.query.city}的旅游景点`;
  }
});
</script>

<style scoped>
.page-header {
  height: 46px;
}
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding-bottom: 0px !important;
}

.chat-container {
  /* flex: 1; */
  height: 650px;
  overflow-y: auto;
  padding: 16px;
  padding-bottom: 60px;
}

.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.quick-questions {
  margin-top: 32px;
  text-align: center;
}

.quick-title {
  font-size: 14px;
  color: #999;
  margin-bottom: 16px;
}

.quick-tag {
  margin: 8px;
  cursor: pointer;
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.streaming-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  color: #999;
  font-size: 14px;
}

.chat-input-area {
  position: fixed;
  bottom: 50px;
  left: 0;
  right: 0;
  background: #fff;
  padding: 8px 16px;
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.05);
  max-width: 750px;
  margin: 0 auto;
}

.chat-input-area :deep(.van-field) {
  background: #f7f8fa;
  border-radius: 20px;
  padding: 8px 16px;
}
</style>
