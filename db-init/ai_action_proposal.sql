-- AI 写操作提议表（Human-in-the-loop，Phase 6）
-- 用途：Agent 只能生成「待确认提议」，任何订单写入都必须由用户通过独立确认接口触发。
-- 本表不承载订单业务数据：真正的订单仍由 shop_order / local_message 链路负责。
-- 幂等设计：
--   1) uk_user_idem 保证同一用户同一购物车快照只生成一条提议；
--   2) 状态迁移依赖条件 UPDATE（见 AiActionProposalMapper），一个提议只能被成功确认一次。
-- 回滚脚本：db-init/ai_action_proposal_rollback.sql

CREATE TABLE IF NOT EXISTS `ai_action_proposal` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `proposal_no` varchar(40) NOT NULL COMMENT '对外提议编号，随机不可枚举，避免横向探测他人提议',
  `user_id` bigint NOT NULL COMMENT '归属用户ID，来自服务端鉴权，确认时二次校验',
  `action_type` varchar(32) NOT NULL COMMENT '动作类型，当前仅 CREATE_ORDER',
  `status` varchar(16) NOT NULL COMMENT '状态：PENDING/CONFIRMING/CONFIRMED/REJECTED/EXPIRED',
  `idempotency_key` char(43) NOT NULL COMMENT '幂等键，用户+地址+优惠券+购物车快照的 SHA-256 摘要',
  `address_id` bigint NOT NULL COMMENT '收货地址ID，确认时重新校验归属',
  `user_coupon_id` bigint DEFAULT NULL COMMENT '用户优惠券ID，可为空',
  `cart_ids` varchar(512) NOT NULL COMMENT '参与下单的购物车ID列表，逗号分隔',
  `items_snapshot` varchar(4000) NOT NULL COMMENT '商品快照，每行一条：productId/goodsId/unitPrice/number/goodsName（制表符分隔），确认时逐项重校验',
  `goods_price` decimal(10,2) NOT NULL COMMENT '商品总价快照',
  `freight_price` decimal(10,2) NOT NULL COMMENT '运费快照',
  `coupon_price` decimal(10,2) NOT NULL COMMENT '优惠金额快照',
  `actual_price` decimal(10,2) NOT NULL COMMENT '应付金额快照，确认时与实时重算值比对',
  `expire_time` datetime NOT NULL COMMENT '过期时间，过期后禁止确认',
  `confirm_time` datetime DEFAULT NULL COMMENT '确认时间',
  `order_sn` varchar(64) DEFAULT NULL COMMENT '确认成功后生成的订单号',
  `fail_code` varchar(32) DEFAULT NULL COMMENT '确认失败原因码，见 ProposalRejectCode',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_action_proposal_no` (`proposal_no`),
  UNIQUE KEY `uk_ai_action_proposal_idem` (`user_id`, `idempotency_key`),
  KEY `idx_ai_action_proposal_user_status` (`user_id`, `status`, `expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 写操作提议（待人工确认），Agent 不得直接写订单';
