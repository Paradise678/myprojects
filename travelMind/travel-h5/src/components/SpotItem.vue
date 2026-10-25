<template>
  <!--
    景点/活动信息展示组件
    用于行程详情页中每天上午/下午/晚上的安排展示。
    接收一个 data 对象，包含：spot(名称)、duration(时长)、ticket(门票)、transportation(交通)、description(介绍)
  -->
  <div class="spot-item" v-if="data">
    <!-- 景点名称，兼容 spot 和 name 两种字段名，都没有则显示"待定" -->
    <div class="spot-name">{{ data.spot || data.name || '待定' }}</div>
    <!-- 详细信息行：有值才显示，带图标 -->
    <div class="spot-details" v-if="data.duration || data.ticket || data.transportation">
      <div class="detail-row" v-if="data.duration">
        <van-icon name="clock-o" size="14" />
        <span>{{ data.duration }}</span>
      </div>
      <div class="detail-row" v-if="data.ticket">
        <van-icon name="ticket-o" size="14" />
        <span>{{ data.ticket }}</span>
      </div>
      <div class="detail-row" v-if="data.transportation">
        <van-icon name="logistics" size="14" />
        <span>{{ data.transportation }}</span>
      </div>
    </div>
    <!-- 景点介绍描述 -->
    <div class="spot-desc" v-if="data.description">{{ data.description }}</div>
  </div>
  <!-- 没有数据时显示空状态 -->
  <div class="spot-item empty" v-else>
    <van-empty description="暂无安排" :image-size="40" />
  </div>
</template>

<script setup>
// 接收父组件传入的景点数据对象
defineProps({
  data: {
    type: Object,
    default: () => ({})
  }
})
</script>

<style scoped>
.spot-item {
  padding: 8px 0;
}

.spot-item.empty {
  padding: 16px 0;
}

.spot-name {
  font-size: 16px;
  font-weight: 600;
  color: #323233;
  margin-bottom: 8px;
}

.spot-details {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 8px;
}

.detail-row {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #666;
}

.spot-desc {
  font-size: 14px;
  color: #969799;
  line-height: 1.5;
}
</style>
