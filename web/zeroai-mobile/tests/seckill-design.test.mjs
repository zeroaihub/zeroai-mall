import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const projectRoot = path.resolve(import.meta.dirname, '..')

function readView(relativePath) {
  return fs.readFileSync(path.join(projectRoot, relativePath), 'utf8')
}

test('seckill list follows the Apple-style design tokens from DESIGN.md', () => {
  const source = readView('src/views/seckill/index.vue')

  assert.match(source, /--seckill-primary:\s*#0066cc/)
  assert.match(source, /--seckill-canvas-parchment:\s*#f5f5f7/)
  assert.match(source, /--seckill-tile-dark:\s*#272729/)
  assert.match(source, /font-family:\s*"SF Pro Display"/)
  assert.match(source, /border-radius:\s*999px/)
  assert.doesNotMatch(source, /#ff(?:6a00|5a1f|b55e|bd4a)/i)
})

test('seckill detail keeps product-first Apple tiles and a frosted sticky bar', () => {
  const source = readView('src/views/seckill/detail.vue')

  assert.match(source, /--seckill-primary:\s*#0066cc/)
  assert.match(source, /--seckill-canvas:\s*#ffffff/)
  assert.match(source, /--seckill-hairline:\s*#e0e0e0/)
  assert.match(source, /backdrop-filter:\s*saturate\(180%\)\s*blur\(20px\)/)
  assert.match(source, /transform:\s*scale\(0\.95\)/)
  assert.doesNotMatch(source, /#ff(?:6a00|5a1f|be72|f0e8)/i)
})

test('seckill detail renders productPicUrl in hero and sku cards', () => {
  const source = readView('src/views/seckill/detail.vue')

  assert.match(source, /productPicUrl/)
  assert.match(source, /selectedProductPicUrl/)
  assert.match(source, /detail-hero__image/)
  assert.match(source, /sku-card__thumb-img/)
})

test('home seckill entry renders a real background image layer', () => {
  const source = readView('src/views/home/index.vue')

  assert.match(source, /seckillEntryBg/)
  assert.match(source, /@\/assets\/seckill\.png/)
  assert.match(source, /home__seckill-bg/)
  assert.match(source, /home__seckill-bg-image/)
  assert.match(source, /alt=""/)
  assert.doesNotMatch(source, /home-seckill-entry-bg\.svg/)
})
