-- ============================================================================
-- 仿真数据集（SIM）—— 压测 / 仿真用户专用，可重复执行
--
-- 设计要点：
-- 1. 全部使用高位固定 ID（商品 9900001+，活动 990001），与真实数据 11xxxxx 段完全隔离，
--    既能被 clean-sim-dataset.sql 精确删除，也不会误伤任何存量商品。
-- 2. 三段 "SIM" 前缀：goods_sn / name / activity.name。搜索时用 keyword=SIM 即可
--    只命中仿真商品，不会把真实商品混进压测口径。
-- 3. 秒杀库存 available_stock = 1000，且必须同步预热到 Redis
--    （key: zeroai:seckill:stock:9900001，值为裸整数），否则 submit 会返回
--    「秒杀库存未预热」(-2)。预热由 seed-sim-dataset.sh 完成，SQL 本身做不到。
-- 4. 本脚本不建会员、不建地址：会员由 simulator 的 warmup 子命令通过真实登录链路创建
--    （199 号段 + 自动注册），保证走的是真实代码路径而不是手工插库。
--
-- 用法：mysql -h127.0.0.1 -P3307 -uroot -p zeroai_mall < scripts/dev/seed-sim-dataset.sql
-- 或用包装脚本：bash scripts/dev/seed-sim-dataset.sh
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 0. 清理旧数据（保证脚本可重复执行；顺序：子表 → 主表）
-- ---------------------------------------------------------------------------
DELETE FROM shop_seckill_sku      WHERE activity_id = 990001;
DELETE FROM shop_seckill_activity WHERE id = 990001;
DELETE FROM shop_goods_product    WHERE goods_id BETWEEN 9900001 AND 9900099;
DELETE FROM shop_goods_specification WHERE goods_id BETWEEN 9900001 AND 9900099;
DELETE FROM shop_goods_attribute  WHERE goods_id BETWEEN 9900001 AND 9900099;
DELETE FROM shop_goods            WHERE id BETWEEN 9900001 AND 9900099;

-- ---------------------------------------------------------------------------
-- 1. 仿真商品（3 个，覆盖「普通下单」与「秒杀」两种链路）
--    category_id 复用现有叶子分类 1008008，保证分类页/首页聚合不报错
-- ---------------------------------------------------------------------------
INSERT INTO shop_goods
(id, goods_sn, name, category_id, brand_id, gallery, keywords, brief,
 is_on_sale, sort, pic_url, share_url, is_new, is_hot, unit,
 counter_price, retail_price, actual_sales, virtual_sales, detail,
 create_time, update_time, del_flag)
VALUES
(9900001, 'SIM-0001', 'SIM-仿真测试手机 Pro', 1008008, 0, NULL, 'SIM,仿真,手机,压测',
 '仿真数据集商品，仅用于压测', 1, 100, NULL, NULL, 1, 1, '件',
 2999.00, 1999.00, 0, 0, '<p>SIM 仿真商品，请勿用于真实交易</p>', NOW(), NOW(), 0),
(9900002, 'SIM-0002', 'SIM-仿真测试耳机', 1008008, 0, NULL, 'SIM,仿真,耳机,压测',
 '仿真数据集商品，仅用于压测', 1, 101, NULL, NULL, 1, 0, '件',
 399.00, 199.00, 0, 0, '<p>SIM 仿真商品，请勿用于真实交易</p>', NOW(), NOW(), 0),
(9900003, 'SIM-0003', 'SIM-仿真测试秒杀款', 1008008, 0, NULL, 'SIM,仿真,秒杀,压测',
 '仿真数据集商品，仅用于秒杀压测', 1, 102, NULL, NULL, 1, 1, '件',
 999.00, 599.00, 0, 0, '<p>SIM 仿真商品，请勿用于真实交易</p>', NOW(), NOW(), 0);

-- ---------------------------------------------------------------------------
-- 2. 仿真 SKU（每个商品 1 个规格，id 与商品一一对应便于排查）
--    number 是下单扣减的真实库存；locked_stock 由下单/取消流程维护，初始必须为 0
-- ---------------------------------------------------------------------------
INSERT INTO shop_goods_product
(id, goods_id, specifications, price, number, locked_stock, default_selected, url,
 create_time, update_time, del_flag)
VALUES
(9900001, 9900001, '["SIM-标准版"]', 1999.00, 100000, 0, 1, NULL, NOW(), NOW(), 0),
(9900002, 9900002, '["SIM-白色"]',      199.00, 100000, 0, 1, NULL, NOW(), NOW(), 0),
(9900003, 9900003, '["SIM-秒杀款"]',    599.00, 100000, 0, 1, NULL, NOW(), NOW(), 0);

-- ---------------------------------------------------------------------------
-- 3. 仿真秒杀活动（status=1 已发布；时间窗覆盖「现在」，否则 submit 直接拒绝）
-- ---------------------------------------------------------------------------
INSERT INTO shop_seckill_activity
(id, name, brief, start_time, end_time, status, sort_order, create_time, update_time, del_flag)
VALUES
(990001, 'SIM-仿真秒杀活动', '仿真数据集专用秒杀活动', DATE_SUB(NOW(), INTERVAL 1 DAY),
 DATE_ADD(NOW(), INTERVAL 30 DAY), 1, 100, NOW(), NOW(), 0);

-- ---------------------------------------------------------------------------
-- 4. 仿真秒杀 SKU（limit_count=1：单用户限购 1 件，配合活动级重复购买校验）
--    available_stock 必须与 Redis 预热值一致，否则压完对账会对不上
-- ---------------------------------------------------------------------------
INSERT INTO shop_seckill_sku
(id, activity_id, goods_id, product_id, seckill_price, available_stock, locked_stock,
 sold_stock, limit_count, status, create_time, update_time, del_flag)
VALUES
(9900001, 990001, 9900003, 9900003, 99.00, 1000, 0, 0, 1, 1, NOW(), NOW(), 0);

-- ---------------------------------------------------------------------------
-- 5. 自检输出
-- ---------------------------------------------------------------------------
SELECT 'goods'    AS item, COUNT(*) AS cnt FROM shop_goods            WHERE id BETWEEN 9900001 AND 9900099;
SELECT 'product'  AS item, COUNT(*) AS cnt FROM shop_goods_product    WHERE goods_id BETWEEN 9900001 AND 9900099;
SELECT 'activity' AS item, COUNT(*) AS cnt FROM shop_seckill_activity WHERE id = 990001;
SELECT 'seckill'  AS item, id, activity_id, goods_id, product_id, seckill_price, available_stock, limit_count, status
  FROM shop_seckill_sku WHERE activity_id = 990001;
