'use strict'

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const { RcmBot } = require('../client/bot')

const root = process.env.FOLIA_E2E_ROOT || '/tmp/recolorfulmap-folia-e2e'
const stateFile = path.join(root, 'server/plugins/RCMFoliaE2E/state.json')
const reportFile = path.join(root, 'results.json')
const phase = process.argv[2]
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))

function state () {
  try { return JSON.parse(fs.readFileSync(stateFile, 'utf8')) } catch { return null }
}

async function until (condition, description, timeout = 45000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const value = condition()
    if (value) return value
    await pause(100)
  }
  throw new Error(`Timed out: ${description}; state=${JSON.stringify(state())}`)
}

function pngPath (reference) {
  assert.match(reference, /^maps\/[0-9a-f-]{36}\/thumb\.png$/)
  return path.join(root, 'server/plugins/ReColorfulMap', reference)
}

function componentText (tag) {
  if (tag == null) return ''
  if (typeof tag === 'string') return tag
  if (Array.isArray(tag)) return tag.map(componentText).join('')
  if (tag.type && Object.hasOwn(tag, 'value')) return componentText(tag.value)
  return componentText(tag.text) + componentText(tag.extra) + componentText(tag.with)
}

async function main () {
  assert.ok(['before-restart', 'after-restart'].includes(phase), 'Specify before-restart or after-restart')
  const pixels = new Map()
  const messages = []
  const jarSha256 = crypto.createHash('sha256').update(
    fs.readFileSync(path.join(root, 'server/plugins/ReColorfulMap.jar'))).digest('hex')
  const bot = new RcmBot({ port: 25578, username: 'RCMFolia', log: (event, detail) => {
    if (['map', 'chat', 'error', 'kick'].includes(event)) console.log(JSON.stringify({ event, detail }))
  } })
  bot.on('chat', packet => messages.push(componentText(packet.content)))
  bot.on('map', packet => {
    if (!packet.columns || !packet.data?.length) return
    const image = pixels.get(packet.itemDamage) || { bytes: Buffer.alloc(16384), full: false, updates: 0 }
    assert.equal(packet.data.length, packet.columns * packet.rows)
    for (let y = 0; y < packet.rows; y++) for (let x = 0; x < packet.columns; x++) {
      image.bytes[(packet.y + y) * 128 + packet.x + x] = packet.data[y * packet.columns + x]
    }
    if (packet.columns === 128 && packet.rows === 128) image.full = true
    image.updated = Date.now()
    image.updates++
    pixels.set(packet.itemDamage, image)
  })
  const stableImage = async id => {
    const image = await until(() => {
      const value = pixels.get(id)
      return value?.full && Date.now() - value.updated >= 1500 ? value : null
    }, 'complete client pixels including incremental updates')
    return { hash: crypto.createHash('sha256').update(image.bytes).digest('hex'),
      distinctColors: new Set(image.bytes).size, updates: image.updates }
  }
  await bot.ready
  try {
    const joinTime = Date.now()
    await until(() => state()?.time >= joinTime, 'fresh player observation')
    if (phase === 'before-restart') {
      const setupTime = Date.now()
      bot.command('rcmfolia setup')
      await until(() => state()?.time >= setupTime && state()?.inventory.length === 0 &&
        state()?.events.some(row => row.event === 'command' && row.action === 'setup'), 'setup')
      bot.command(`tomap ${process.env.FOLIA_IMAGE_URL || 'http://127.0.0.1:18180/gradient.png'}`)
      const reference = await until(() => state()?.inventory[0], 'downloaded map in player inventory')
      assert.ok(fs.existsSync(pngPath(reference.path)), 'the PDC path must point to a real PNG')
      assert.ok(reference.renderers.includes('cn.lunadeer.reColorfulMap.ImageRenderer'))
      bot.hotbar(reference.slot)
      const image = await stableImage(reference.mapId)
      assert.ok(image.distinctColors > 8, 'the rendered gradient must contain real image colors')
      await until(() => messages.some(message => message.includes('[ReColorfulMap] Generated map, frame size:')),
        'success notification delivered to the real client')
      bot.command('tomap http://127.0.0.1:18180/gradient.png -1')
      await until(() => messages.some(message => message.includes('[ReColorfulMap] Scale must be a finite positive number')),
        'invalid scale warning delivered to the real client')
      bot.command('tomap http://127.0.0.1:18180/rcm-folia-intentionally-missing.png')
      await until(() => messages.some(message => message.includes('[ReColorfulMap] Failed to generate map:')),
        'asynchronous download failure notification delivered to the real client')
      assert.equal(state().inventory.length, 1, 'failed commands must not grant another map')
      bot.dropStack()
      await until(() => state()?.events.some(row => row.event === 'drop'), 'real drop packet')
      assert.ok(fs.existsSync(pngPath(reference.path)), 'dropping is not destruction')
      bot.command('rcmfolia collect')
      await until(() => state()?.inventory.some(row => row.path === reference.path) &&
        state()?.events.some(row => row.event === 'pickup'), 'real pickup')
      assert.ok(fs.existsSync(pngPath(reference.path)), 'pickup preserves artwork')
      const report = { platform: 'Folia 26.2 build 7', jarSha256, phase, reference, image,
        assertions: ['real login', 'real /tomap', 'PDC PNG reference', '128x128 client pixels',
          'success notification received', 'validation warning received', 'download failure notification received',
          'drop preserves files', 'pickup preserves files'], messages, events: state().events }
      fs.writeFileSync(reportFile, JSON.stringify(report, null, 2) + '\n')
      console.log(JSON.stringify({ success: true, phase, reference, image }))
    } else {
      const report = JSON.parse(fs.readFileSync(reportFile, 'utf8'))
      assert.equal(jarSha256, report.jarSha256, 'both phases must test the same plugin artifact')
      const reference = await until(() => state()?.inventory.find(row => row.path === report.reference.path),
        'persisted inventory map after restart')
      assert.equal(reference.mapId, report.reference.mapId)
      assert.ok(reference.renderers.includes('cn.lunadeer.reColorfulMap.ImageRenderer'))
      bot.hotbar(reference.slot)
      const image = await stableImage(reference.mapId)
      assert.equal(image.hash, report.image.hash, 'restart must restore exactly the same image')
      bot.command('rcmfolia drop-mode void')
      await until(() => state()?.events.some(row => row.event === 'command' && row.action === 'drop-mode'), 'void mode')
      bot.dropStack()
      await until(() => state()?.events.some(row => row.event === 'remove' && row.cause === 'OUT_OF_WORLD'),
        'actual Folia OUT_OF_WORLD removal')
      await until(() => !fs.existsSync(path.dirname(pngPath(reference.path))), 'asynchronous artwork directory deletion')
      report.phase = 'complete'
      report.assertions.push('restart preserves PDC/map ID', 'restart restores identical client pixels',
        'OUT_OF_WORLD deletes artwork')
      report.afterRestart = { reference, image, events: state().events }
      fs.writeFileSync(reportFile, JSON.stringify(report, null, 2) + '\n')
      console.log(JSON.stringify({ success: true, phase: 'complete', assertions: report.assertions }))
    }
  } finally {
    bot.close()
  }
}

main().catch(error => { console.error(error.stack); process.exitCode = 1 })
