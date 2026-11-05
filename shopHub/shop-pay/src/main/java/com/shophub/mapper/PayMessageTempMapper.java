package com.shophub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shophub.entity.PayMessageTemp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表 Mapper
 */
@Mapper
public interface PayMessageTempMapper extends BaseMapper<PayMessageTemp> {

    /**
     * 查询待发送或需要重试的消息 (Outbox 扫描)
     * 取 100 条，避免一次性拉太多
     */
    @Select("SELECT * FROM tb_pay_message_temp " +
            "WHERE (status = 0 OR (status = 2 AND next_retry_time <= NOW())) " +
            "ORDER BY create_time ASC " +
            "LIMIT 100")
    List<PayMessageTemp> selectPendingMessages();

    /**
     * 标记发送成功
     */
    @Update("UPDATE tb_pay_message_temp SET status = 1, update_time = NOW() WHERE id = #{id}")
    int markSent(@Param("id") Long id);

    /**
     * 标记发送失败 + 递增重试次数 + 计算下次重试时间 (指数退避)
     */
    @Update("UPDATE tb_pay_message_temp " +
            "SET status = 2, " +
            "    retry_count = retry_count + 1, " +
            "    next_retry_time = DATE_ADD(NOW(), INTERVAL #{delaySeconds} SECOND), " +
            "    update_time = NOW() " +
            "WHERE id = #{id}")
    int markFailed(@Param("id") Long id, @Param("delaySeconds") int delaySeconds);
}
