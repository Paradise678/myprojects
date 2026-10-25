<template>
  <!--
    聊天气泡组件
    职责：根据消息的 role（user / ai）渲染不同样式的气泡，
    用户消息靠右（蓝色），AI 消息靠左（灰色）。
  -->
  <div class="chat-bubble" :class="messageClass">
    <div class="bubble-content">
      <!-- 用户消息：直接显示文本 -->
      <div class="message-text" v-if="message.role === 'user'">{{ message.content }}</div>
      <!-- AI 消息：流式输出时 content 可能为空，做一下判断 -->
      <div class="message-text ai-message" v-else>
        <template v-if="message.content">{{ message.content }}</template>
      </div>
    </div>
    <!-- 消息时间：有内容且有时间戳时才显示 -->
    <div class="message-time" v-if="showTime">{{ formatTime }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 接收父组件传入的消息对象
const props = defineProps({
  message: {
    type: Object,
    required: true
  }
})

// 根据角色返回气泡样式类名：user → 靠右，ai → 靠左
const messageClass = computed(() => {
  return props.message.role === 'user' ? 'user-message' : 'ai-message'
})

// 是否显示时间：需要同时有时间戳和内容
const showTime = computed(() => {
  return props.message.timestamp && props.message.content
})

// 格式化时间为 HH:mm
const formatTime = computed(() => {
  if (!props.message.timestamp) return ''
  const date = new Date(props.message.timestamp)
  return `${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}`
})
</script>

<style scoped>
.chat-bubble {
  display: flex;
  flex-direction: column;
  max-width: 80%;
}

.user-message {
  align-self: flex-end;
  align-items: flex-end;
}

.ai-message {
  align-self: flex-start;
  align-items: flex-start;
}

.bubble-content {
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 15px;
  line-height: 1.5;
  word-break: break-word;
}

.user-message .bubble-content {
  background: #1989fa;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.ai-message .bubble-content {
  background: #f5f5f5;
  color: #323233;
  border-bottom-left-radius: 4px;
}

.message-time {
  font-size: 11px;
  color: #999;
  margin-top: 4px;
  padding: 0 4px;
}

.typing {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
