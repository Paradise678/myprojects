<template>
  <!--
      首页（行程规划发起页）
      业务流程：
        用户选择目的地城市 → 填写预算和天数 → 点击"开始规划"
        → 跳转到详情页 /detail，并把城市、预算、天数通过 URL query 传过去
        → 详情页拿到参数后调用后端 AI 生成行程
    -->
  <div class="page-container">
    <div class="page-header">
      <van-nav-bar title="智能旅游助手" />
    </div>
    <div class="page-content">
      <!-- 顶部公告栏：介绍产品 -->
      <van-notice-bar
        left-icon="info-o"
        text="基于 AI 的智能景点介绍与行程规划系统"
        style="margin-bottom: 16px"
      />
      <!-- 行程规划表单卡片 -->
      <div class="card search-card">
        <div class="section-title">规划你的旅程</div>
        <!-- 目的地选择：点击弹出城市选择器（只读输入框，不可手动输入） -->
        <van-field
          @click="showCityPicker = true"
          is-link
          readonly
          v-model="formData.city"
          label="目的地"
          placeholder="请选择城市"
          style="background: #f7f8fa; border-radius: 8px; margin-bottom: 12px"
        />
        <!-- 预算输入：数字类型 -->
        <van-field
          v-model="formData.budget"
          type="number"
          label="预算（元）"
          placeholder="请输入预算金额"
          :border="false"
          style="background: #f7f8fa; border-radius: 8px; margin-bottom: 12px"
        />
        <!-- 天数输入：digit 类型（只能输数字） -->
        <van-field
          v-model="formData.days"
          type="digit"
          label="天数"
          placeholder="请输入天数"
          :border="false"
          style="background: #f7f8fa; border-radius: 8px; margin-bottom: 12px"
        />
        <!-- 提交按钮：loading 状态防止重复点击 -->
        <van-button
          type="primary"
          size="large"
          round
          :loading="isLoading"
          @click="handleSubmit"
        >
          开始规划
        </van-button>
      </div>
      <!-- 快捷入口：跳转 AI 对话 / 我的页面 -->
      <div class="card quick-actions">
        <div class="section-title">快捷入口</div>
        <van-grid :column-num="2" :gutter="12">
          <van-grid-item
            @click="goPage('/chat')"
            icon="chat-o"
            text="AI 对话"
          />
          <van-grid-item
            @click="goPage('/profile')"
            icon="user-o"
            text="我的"
          />
        </van-grid>
      </div>
      <!-- 热门目的地：点击直接填入表单，免去手动选择 -->
      <div class="card popular-destinations">
        <div class="section-title">热门目的地</div>
        <van-grid :column-num="4" :gutter="8">
          <van-grid-item
            @click="selectedCity(city)"
            v-for="(city, index) in popularCities"
            :key="index"
          >
            <div class="city-tag" :class="{ active: formData.city === city }">
              {{ city }}
            </div>
          </van-grid-item>
        </van-grid>
      </div>
    </div>
    <!-- 城市选择器弹窗：从底部弹出，确认后回填到表单 -->
    <van-popup round v-model:show="showCityPicker" position="bottom">
      <van-picker
        title="选择目的地"
        :columns="cityColumns"
        @confirm="handleCityConfirm"
        @cancel="showCityPicker = false"
      />
    </van-popup>
  </div>
</template>
<script setup>
import { ref, reactive } from "vue";
import { useRouter } from "vue-router";
import { showToast } from "vant";

// 路由实例，用于页面跳转
const router = useRouter();

// 旅游规划表单数据：目的地城市、预算、天数
const formData = reactive({
  city: "",
  budget: null,
  days: null,
});
// 城市选择器弹窗的显示/隐藏状态
const showCityPicker = ref(false);

// 全部可选城市列表（用于城市选择器）
const allCities = [
  "北京",
  "上海",
  "广州",
  "深圳",
  "成都",
  "杭州",
  "西安",
  "重庆",
  "南京",
  "武汉",
  "苏州",
  "长沙",
  "天津",
  "郑州",
  "济南",
  "青岛",
  "大连",
  "沈阳",
  "哈尔滨",
  "长春",
  "福州",
  "厦门",
  "南昌",
  "合肥",
  "昆明",
  "贵阳",
  "南宁",
  "桂林",
  "海口",
  "三亚",
  "丽江",
  "大理",
  "西安",
  "兰州",
  "乌鲁木齐",
  "拉萨",
  "呼和浩特",
  "太原",
  "石家庄",
];

// 首页快捷展示的热门城市（子集）
const popularCities = [
  "北京",
  "上海",
  "广州",
  "深圳",
  "成都",
  "杭州",
  "西安",
  "重庆",
];

// 把城市数组转成 Vant Picker 需要的格式 [{ text, value }]
const cityColumns = allCities.map((city) => ({ text: city, value: city }));

/**
 * 城市选择器确认回调：把选中的城市回填到表单，并关闭弹窗
 * @param {object} param0 - Vant Picker 确认事件参数，selectedOptions 为选中项数组
 */
const handleCityConfirm = ({ selectedOptions }) => {
  console.log(selectedOptions[0].value);
  formData.city = selectedOptions[0].value;
  showCityPicker.value = false;
};

// 提交按钮的 loading 状态
const isLoading = ref(false);

/**
 * 提交表单：校验参数后跳转到详情页
 * 详情页会根据 URL 上的 query 参数自动请求后端生成行程
 */
const handleSubmit = async () => {
  isLoading.value = true;
  // 校验：目的地必选
  if (!formData.city) {
    showToast("请选择目的地");
    return;
  }
  // 校验：预算不能低于 100 元
  if (!formData.budget || formData.budget < 100) {
    showToast("预算不能低于100元");
    return;
  }
  // 校验：天数必须在 1~30 之间
  if (!formData.days || formData.days < 1 || formData.days > 30) {
    showToast("天数必须在1-30天之间");
    return;
  }
  // 校验通过，携带参数跳转到详情页
  router.push({
    path: "/detail",
    query: {
      city: formData.city,
      budget: formData.budget,
      days: formData.days,
    },
  });
};

/**
 * 跳转到指定路由（快捷入口用）
 * @param {string} path - 目标路由路径
 */
const goPage = (path) => {
  router.push(path);
};

/**
 * 点击热门城市标签：直接把城市填入表单
 * @param {string} city - 城市名
 */
const selectedCity = (city) => {
  formData.city = city;
};
</script>
<style scoped>
.search-card {
  margin-bottom: 16px;
}
.city-tag {
  padding: 8px 12px;
  border-radius: 16px;
  font-size: 14px;
  color: #666;
  background: #f7f8fa;
  transition: all 0.3s;
}
.city-tag.active {
  background: #1989fa;
  color: #fff;
}
</style>
