package org.example.traveljavaserver.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行程规划记录：recommend 成功后落库，planJson 保存完整行程 VO 的 JSON
 */
@Data
@TableName("tm_travel_plan")
public class TravelPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;          // 归属用户（JWT 解析得到）
    private String city;
    private Integer days;
    private Double totalBudget;
    private String planJson;      // TravelRecommendVO 序列化结果
    private LocalDateTime createTime;
}
