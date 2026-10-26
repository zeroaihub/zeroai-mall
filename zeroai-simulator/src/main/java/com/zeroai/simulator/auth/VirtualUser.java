package com.zeroai.simulator.auth;

/**
 * 一个虚拟用户。
 * <p>
 * 只保留跨请求需要复用的身份信息，不持有任何连接或上下文对象，
 * 这样它可以在工作线程之间安全轮转，线程数变化不会影响用户态。
 *
 * @param mobile    手机号，同时作为该用户在本地缓存里的唯一键
 * @param token     移动端登录态（裸 token，放 Authorization 头）
 * @param addressId 默认收货地址 ID
 */
public record VirtualUser(String mobile, String token, long addressId) {
}
