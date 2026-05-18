package com.example.entity.vo.request;

import com.example.entity.alert.AlertMetric;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 告警规则请求 VO 参数校验测试。
 */
class AlertRuleRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    /**
     * 初始化 Jakarta Validator。
     */
    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /**
     * 关闭 ValidatorFactory，释放测试资源。
     */
    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /**
     * 创建请求应接受 AlertMetric 枚举声明的所有指标。
     */
    @Test
    void createRequestShouldAcceptAllAlertMetrics() {
        for (AlertMetric metric : AlertMetric.values()) {
            AlertRuleCreateVO vo = validCreate(metric.getColumn());
            Assertions.assertTrue(validator.validate(vo).isEmpty(),
                    "create VO should accept metric: " + metric.getColumn());
        }
    }

    /**
     * 更新请求应接受 AlertMetric 枚举声明的所有指标。
     */
    @Test
    void updateRequestShouldAcceptAllAlertMetrics() {
        for (AlertMetric metric : AlertMetric.values()) {
            AlertRuleUpdateVO vo = validUpdate(metric.getColumn());
            Assertions.assertTrue(validator.validate(vo).isEmpty(),
                    "update VO should accept metric: " + metric.getColumn());
        }
    }

    /**
     * 创建请求仍应拒绝未知指标。
     */
    @Test
    void createRequestShouldRejectUnknownMetric() {
        AlertRuleCreateVO vo = validCreate("unknown_metric");

        Assertions.assertFalse(validator.validate(vo).isEmpty());
    }

    /**
     * 构造合法创建请求。
     *
     * @param metric 指标名
     * @return 创建请求 VO
     */
    private AlertRuleCreateVO validCreate(String metric) {
        AlertRuleCreateVO vo = new AlertRuleCreateVO();
        vo.setName("metric-" + metric);
        vo.setMetric(metric);
        vo.setOperator("gt");
        vo.setThreshold(80.0);
        vo.setDurationSec(60);
        vo.setLevel("warning");
        vo.setEnabled(true);
        return vo;
    }

    /**
     * 构造合法更新请求。
     *
     * @param metric 指标名
     * @return 更新请求 VO
     */
    private AlertRuleUpdateVO validUpdate(String metric) {
        AlertRuleUpdateVO vo = new AlertRuleUpdateVO();
        vo.setName("metric-" + metric);
        vo.setMetric(metric);
        vo.setOperator("gt");
        vo.setThreshold(80.0);
        vo.setDurationSec(60);
        vo.setLevel("warning");
        vo.setEnabled(true);
        return vo;
    }
}
