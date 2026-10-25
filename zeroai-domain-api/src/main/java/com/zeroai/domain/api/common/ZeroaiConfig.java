package com.zeroai.domain.api.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Zeroai 全局配置静态访问器。
 * 契约层保留历史静态读取方式，配置绑定 Bean 由 common 模块提供，避免 domain-api 直接注册 Spring 组件。
 */
@ConfigurationProperties(prefix = "zeroai")
public class ZeroaiConfig {
    /**
     * 上传路径
     */
    private static String uploadDir;

    /**
     * 项目名称
     */
    private static String name;
    /**
     * 项目版本
     */
    private static String version;
    /**
     * 联系邮件
     */
    private static String email;

    /**
     * 管理后台地址
     */
    private static String adminUrl;
    /**
     * 商城移动端地址
     */
    private static String mobileUrl;

    /**
     * 未支付订单延时取消时间
     */
    private static Integer unpaidOrderCancelDelayTime;

    /**
     * 商城免运费限额
     */
    private static BigDecimal freightLimit;
    /**
     * 商城运费
     */
    private static BigDecimal freightPrice;

    public static String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        ZeroaiConfig.uploadDir = uploadDir;
    }

    public static String getDownloadPath() {
        return getUploadDir() + "/download/";
    }

    public static String getAvatarPath() {
        return getUploadDir() + "/avatar/";
    }

    public static String getName() {
        return name;
    }

    public void setName(String name) {
        ZeroaiConfig.name = name;
    }

    public static String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        ZeroaiConfig.version = version;
    }

    public static String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        ZeroaiConfig.email = email;
    }

    public static String getAdminUrl() {
        return adminUrl;
    }

    public void setAdminUrl(String adminUrl) {
        ZeroaiConfig.adminUrl = adminUrl;
    }

    public static String getMobileUrl() {
        return mobileUrl;
    }

    public void setMobileUrl(String mobileUrl) {
        ZeroaiConfig.mobileUrl = mobileUrl;
    }

    public static BigDecimal getFreightLimit() {
        return freightLimit;
    }

    public void setFreightLimit(BigDecimal freightLimit) {
        ZeroaiConfig.freightLimit = freightLimit;
    }

    public static BigDecimal getFreightPrice() {
        return freightPrice;
    }

    public void setFreightPrice(BigDecimal freightPrice) {
        ZeroaiConfig.freightPrice = freightPrice;
    }

    public static Integer getUnpaidOrderCancelDelayTime() {
        return unpaidOrderCancelDelayTime;
    }

    public void setUnpaidOrderCancelDelayTime(Integer unpaidOrderCancelDelayTime) {
        ZeroaiConfig.unpaidOrderCancelDelayTime = unpaidOrderCancelDelayTime;
    }
}
