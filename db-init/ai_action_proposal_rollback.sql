-- AI 写操作提议表回滚脚本（Phase 6）
-- 说明：ai_action_proposal 只记录「待确认提议」，不参与订单/库存/支付业务数据，
-- 因此可独立删除；删除前请确认没有处于 CONFIRMING 状态的在途确认。
-- 回滚后 Agent 侧提议接口会因缺表报错，需要同步关闭 zeroai.ai.action.enabled。

DROP TABLE IF EXISTS `ai_action_proposal`;
