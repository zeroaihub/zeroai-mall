import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

import {
  buildSeckillPayRoute,
  buildSeckillSubmitErrorMessage,
} from '../src/views/seckill/seckill-payment-flow.mjs'

const projectRoot = path.resolve(import.meta.dirname, '..')

test('seckill success builds the order payment route', () => {
  assert.deepEqual(buildSeckillPayRoute({
    orderSn: 'SK202606060001',
    actualPrice: 19,
    fallbackPrice: 20,
    status: 'SUCCESS',
  }), {
    name: 'OrderPay',
    query: {
      orderSn: 'SK202606060001',
      actualPrice: 19,
    },
  })
})

test('seckill success falls back to estimated price when actual price is missing', () => {
  assert.deepEqual(buildSeckillPayRoute({
    orderSn: 'SK202606060002',
    actualPrice: 0,
    fallbackPrice: '19.00',
    status: 'SUCCESS',
  }), {
    name: 'OrderPay',
    query: {
      orderSn: 'SK202606060002',
      actualPrice: '19.00',
    },
  })
})

test('seckill non-success status does not build a payment route', () => {
  assert.equal(buildSeckillPayRoute({
    orderSn: 'SK202606060003',
    actualPrice: 19,
    fallbackPrice: 19,
    status: 'PROCESSING',
  }), null)
})

test('seckill detail uses automatic payment redirect after success', () => {
  const source = fs.readFileSync(path.join(projectRoot, 'src/views/seckill/detail.vue'), 'utf8')

  assert.match(source, /buildSeckillPayRoute/)
  assert.match(source, /redirectToPayIfSuccess/)
})

test('seckill duplicate purchase error is converted to a user-facing toast message', () => {
  assert.equal(
    buildSeckillSubmitErrorMessage(new Error('请勿重复购买该活动商品')),
    '请勿重复购买该活动商品'
  )
})

test('seckill detail catches submit errors instead of leaking an unhandled promise', () => {
  const source = fs.readFileSync(path.join(projectRoot, 'src/views/seckill/detail.vue'), 'utf8')

  assert.match(source, /buildSeckillSubmitErrorMessage/)
  assert.match(source, /let submitError = null/)
  assert.match(source, /catch\s*\(error\)/)
  assert.match(source, /submitError = error/)
  assert.match(source, /if\s*\(submitError\)\s*\{[\s\S]*showToast\(\{\s*type:\s*'fail',\s*message:\s*buildSeckillSubmitErrorMessage\(submitError\)\s*\}\)[\s\S]*\}/)
})

test('seckill detail shows and closes a loading toast while submitting', () => {
  const source = fs.readFileSync(path.join(projectRoot, 'src/views/seckill/detail.vue'), 'utf8')

  assert.match(source, /showLoadingToast/)
  assert.doesNotMatch(source, /closeToast/)
  assert.match(source, /const loadingToast = showLoadingToast\(\{\s*message:\s*'正在提交秒杀订单'/)
  assert.match(source, /forbidClick:\s*true/)
  assert.match(source, /duration:\s*0/)
  assert.match(source, /finally\s*\{[\s\S]*loadingToast\.close\(\)[\s\S]*submitLoading\.value = false[\s\S]*if\s*\(submitError\)/)
})
