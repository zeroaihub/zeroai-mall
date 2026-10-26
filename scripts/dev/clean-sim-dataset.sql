-- ============================================================================
-- 仿真数据集清理 —— 只删 SIM 口径，不碰任何真实数据
--
-- 隔离依据（删除条件固定为这两个，绝不使用时间范围或全表 DELETE）：
--   1. 会员：手机号 1990000 开头（simulator warmup 专用号段）
--   2. 商品：id 9900001~9900099  活动：id 990001
--
-- 执行顺序说明：订单相关表必须先删子表再删 shop_order，否则会留下孤儿行，
-- 后续对账 SQL 会把它们算进去，导致「订单数」与「库存流水」永远对不上。
--
-- 用法：mysql -h127.0.0.1 -P3307 -uroot -p zeroai_mall < scripts/dev/clean-sim-dataset.sql
-- ============================================================================

SET NAMES utf8mb4;

-- 用临时表固定住本次要删的 ID 集合，避免每条语句都重复子查询（也避免中途数据变化）
DROP TEMPORARY TABLE IF EXISTS tmp_sim_member;
CREATE TEMPORARY TABLE tmp_sim_member (id INT PRIMARY KEY);
INSERT INTO tmp_sim_member (id)
SELECT id FROM shop_member WHERE mobile LIKE '1990000%';

DROP TEMPORARY TABLE IF EXISTS tmp_sim_order;
CREATE TEMPORARY TABLE tmp_sim_order (id INT PRIMARY KEY, order_sn VARCHAR(63), KEY idx_sn (order_sn));
INSERT INTO tmp_sim_order (id, order_sn)
SELECT id, order_sn FROM shop_order WHERE user_id IN (SELECT id FROM tmp_sim_member);

SELECT '待清理会员' AS item, COUNT(*) AS cnt FROM tmp_sim_member
UNION ALL SELECT '待清理订单', COUNT(*) FROM tmp_sim_order;

-- 1. 订单子表
DELETE FROM shop_order_activity_relation WHERE user_id IN (SELECT id FROM tmp_sim_member)
   OR order_id IN (SELECT id FROM tmp_sim_order);
DELETE FROM shop_order_goods       WHERE order_id IN (SELECT id FROM tmp_sim_order);
DELETE FROM shop_order_status_log  WHERE order_id IN (SELECT id FROM tmp_sim_order);
DELETE FROM shop_payment_flow      WHERE order_id IN (SELECT id FROM tmp_sim_order)
   OR order_sn IN (SELECT order_sn FROM tmp_sim_order);

-- 2. 库存流水按 SIM 商品维度清理（flow_key 是幂等键，必须连同订单一起清，否则重跑会命中幂等）
DELETE FROM shop_inventory_flow WHERE product_id BETWEEN 9900001 AND 9900099;

-- 3. 本地消息表：仿真下单/秒杀的 outbox 记录
DELETE FROM local_message WHERE biz_id IN (SELECT order_sn FROM tmp_sim_order);

-- 4. 订单主表
DELETE FROM shop_order WHERE id IN (SELECT id FROM tmp_sim_order);

-- 5. 会员侧数据
DELETE FROM shop_cart           WHERE user_id IN (SELECT id FROM tmp_sim_member);
DELETE FROM shop_address        WHERE member_id IN (SELECT id FROM tmp_sim_member);
DELETE FROM shop_member_coupon  WHERE user_id IN (SELECT id FROM tmp_sim_member);
DELETE FROM shop_search_history WHERE user_id IN (SELECT id FROM tmp_sim_member);
DELETE FROM shop_member         WHERE id IN (SELECT id FROM tmp_sim_member);

-- 6. 仿真商品与活动
DELETE FROM shop_seckill_sku          WHERE activity_id = 990001;
DELETE FROM shop_seckill_activity     WHERE id = 990001;
DELETE FROM shop_goods_product        WHERE goods_id BETWEEN 9900001 AND 9900099;
DELETE FROM shop_goods_specification  WHERE goods_id BETWEEN 9900001 AND 9900099;
DELETE FROM shop_goods_attribute      WHERE goods_id BETWEEN 9900001 AND 9900099;
DELETE FROM shop_goods                WHERE id BETWEEN 9900001 AND 9900099;

-- 7. 自检：以下应当全部为 0
SELECT '剩余仿真商品'   AS item, COUNT(*) AS cnt FROM shop_goods            WHERE id BETWEEN 9900001 AND 9900099
UNION ALL SELECT '剩余仿真订单', COUNT(*) FROM shop_order WHERE user_id IN (SELECT id FROM tmp_sim_member)
UNION ALL SELECT '剩余仿真会员', COUNT(*) FROM shop_member WHERE mobile LIKE '1990000%';

DROP TEMPORARY TABLE IF EXISTS tmp_sim_member;
DROP TEMPORARY TABLE IF EXISTS tmp_sim_order;
