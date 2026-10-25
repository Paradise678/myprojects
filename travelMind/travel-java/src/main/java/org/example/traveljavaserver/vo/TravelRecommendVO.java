package org.example.traveljavaserver.vo;

import java.util.List;

import lombok.Data;

/**
 * 行程规划结果 VO（View Object）
 * 对应大模型返回的 JSON 结构，前端 Detail.vue 直接用这个对象渲染页面。
 *
 * 字段与大模型 Prompt 中要求输出的 JSON 结构一一对应。
 */
@Data
public class TravelRecommendVO {
    private Boolean success;          // 业务是否成功
    private String city;              // 目的地城市
    private Integer days;             // 旅行天数
    private Double totalBudget;       // 总预算
    private List<DailyItinerary> dailyItinerary;  // 每日行程列表
    private BudgetBreakdown budgetBreakdown;      // 预算明细
    private List<String> tips;        // 温馨提示列表
    private List<String> warnings;    // 注意事项列表
    private String error;             // 错误信息（失败时填充）
    private String rawResponse;       // 大模型原始回复（解析失败时保留，便于排查）

    /** 单日行程：包含上午、下午、晚上三个时段的安排 */
    @Data
    public static class DailyItinerary {
        private Integer day;        // 第几天
        private String date;        // 日期描述（如"第1天"）
        private Timeslot morning;   // 上午安排
        private Timeslot afternoon; // 下午安排
        private Timeslot evening;   // 晚上安排
    }

    /** 单个时段的景点/活动信息 */
    @Data
    public static class Timeslot {
        private String spot;            // 景点/活动名称
        private String duration;        // 游览/活动时长
        private String transportation;  // 交通方式
        private String description;     // 景点介绍
        private String ticket;          // 门票/费用
    }

    /** 预算明细：各项费用分配 */
    @Data
    public static class BudgetBreakdown {
        private Double accommodation;  // 住宿费用
        private Double food;           // 餐饮费用
        private Double transportation; // 交通费用
        private Double tickets;        // 门票费用
        private Double other;          // 其他费用

    }

}
