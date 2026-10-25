<template>
  <!--
    预算明细表格组件
    展示 AI 生成的预算分配：住宿、餐饮、交通、门票、其他，以及总计。
    父组件传入 data（各项金额）和 total（总预算）。
  -->
  <div class="budget-table">
    <van-cell-group :border="false">
      <!-- 遍历预算各项，渲染为列表行 -->
      <van-cell
        v-for="(value, key) in budgetItems"
        :key="key"
        :title="getLabel(key)"
        :value="`¥${value}`"
        :border="false"
      />
    </van-cell-group>
    <!-- 总计行 -->
    <div class="budget-total">
      <span>总计</span>
      <span class="total-amount">¥{{ total }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 接收父组件传入的预算明细数据和总预算
const props = defineProps({
  data: {
    type: Object,
    default: () => ({})
  },
  total: {
    type: [Number, String],
    default: 0
  }
})

// 计算属性：整理出五项预算，缺失的默认为 0，保证渲染稳定
const budgetItems = computed(() => {
  return {
    accommodation: props.data.accommodation || 0,
    food: props.data.food || 0,
    transportation: props.data.transportation || 0,
    tickets: props.data.tickets || 0,
    other: props.data.other || 0
  }
})

// 英文字段名 → 中文标签的映射
const labelMap = {
  accommodation: '住宿',
  food: '餐饮',
  transportation: '交通',
  tickets: '门票',
  other: '其他'
}

// 根据字段名取中文标签，取不到则原样返回
const getLabel = (key) => {
  return labelMap[key] || key
}
</script>

<style scoped>
.budget-table {
  margin-top: 8px;
}

.budget-total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: #f7f8fa;
  border-radius: 8px;
  margin-top: 8px;
  font-size: 16px;
  font-weight: 600;
}

.total-amount {
  color: #ee0a24;
  font-size: 18px;
}
</style>
