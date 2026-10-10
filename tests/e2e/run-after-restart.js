'use strict'

const fs = require('node:fs')
const path = require('node:path')
const http = require('node:http')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const { connect } = require('./client/bot')
const { png } = require('./run-scenarios')
const server = process.env.RCM_E2E_SERVER || '/tmp/recolorfulmap-e2e/server'
const pluginSha256 = crypto.createHash('sha256').update(fs.readFileSync(path.join(server, 'plugins/ReColorfulMap.jar'))).digest('hex')
const root = path.join(server, 'plugins/RCME2E')
const read = () => JSON.parse(fs.readFileSync(path.join(root, 'state.json'), 'utf8'))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const folder = image => path.join(server, 'plugins/ReColorfulMap', image.split('/').slice(0, 2).join('/'))
const results = []
async function until (predicate, description, timeout = 15000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { const state = read(); if (predicate(state)) return state; await sleep(100) }
  throw new Error(`Timed out: ${description}`)
}
function passed (name, details = {}) { results.push({ name, status: 'passed', ...details }); console.log(JSON.stringify(results.at(-1))) }

async function main () {
  const pngBytes = png()
  const imageServer = http.createServer((request, response) => { response.setHeader('Content-Type', 'image/png'); response.end(pngBytes) })
  await new Promise(resolve => imageServer.listen(0, '127.0.0.1', resolve))
  let bot
  try { bot = await connect({ port: Number(process.env.RCM_E2E_PORT || 25576) }) } catch (error) { imageServer.close(); throw error }
  const messages = []
  bot.on('packet', (packet, metadata) => { if (metadata.name.includes('chat')) messages.push({ type: metadata.name, packet }) })
  await sleep(700)
  async function command (value) {
    const tick = read().tick; bot.command(`rcme2e ${value}`)
    const state = await until(state => state.tick > tick && state.phase === value.split(' ')[0], value)
    assert.equal(state.error, '')
    return state
  }
  const tomap = () => bot.command(`tomap http://127.0.0.1:${imageServer.address().port}/gradient.png`)
  async function generate () { tomap(); await until(state => state.hand.path, 'new image in hand'); return read().hand.path }
  try {
    const checkpoint = JSON.parse(fs.readFileSync(path.join(root, 'restart-checkpoint.json'), 'utf8'))
    await command(`track ${checkpoint.frames.join(',')}`)
    await until(state => state.frames.every(frame => frame.item?.path) && state.hand.path === checkpoint.handArt, 'saved frame and inventory PDC restoration')
    const expectedIds = Object.keys(checkpoint.pixelHashes).map(Number)
    // Frames can first send their saved blank canvas before the asynchronous PNG
    // load finishes; require the expected content, then compare the full hashes.
    await until(() => expectedIds.every(id => bot.maps.get(id)?.received === 16384
      && crypto.createHash('sha256').update(bot.maps.get(id).pixels).digest('hex') === checkpoint.pixelHashes[id]), 'all restored client pixel hashes', 20000)
    const actualHashes = Object.fromEntries(expectedIds.map(id => [id, crypto.createHash('sha256').update(bot.maps.get(id).pixels).digest('hex')]))
    assert.deepEqual(actualHashes, checkpoint.pixelHashes)
    for (const item of [read().hand, ...read().frames.map(frame => frame.item)]) {
      assert.ok(item.renderers.includes('cn.lunadeer.reColorfulMap.ImageRenderer')); assert.ok(item.fileExists)
    }
    passed('cold restart restores frame and inventory PDC with identical accumulated network pixels', { pixelHashes: actualHashes })

    await command('setup'); await command('full'); await command('cancel-spawn on')
    const mapsDir = path.join(server, 'plugins/ReColorfulMap/maps')
    const oldDirs = new Set(fs.readdirSync(mapsDir))
    const messageStart = messages.length
    tomap()
    await until(() => fs.readdirSync(mapsDir).some(name => !oldDirs.has(name) && !name.startsWith('.')), 'cancelled generation retains persisted PNG data')
    await sleep(1500)
    const newDirs = fs.readdirSync(mapsDir).filter(name => !oldDirs.has(name) && !name.startsWith('.'))
    assert.equal(newDirs.length, 1)
    assert.ok(!read().inventory.some(item => item.path))
    assert.ok(!read().drops.some(item => item.path?.includes(newDirs[0])))
    const deliveryMessages = JSON.stringify(messages.slice(messageStart))
    assert.match(deliveryMessages, /Failed to generate map/)
    assert.doesNotMatch(deliveryMessages, /Generated map, frame size/)
    passed('full inventory plus cancelled generated-item spawn reports failure with no success or lost PNG data', { artwork: newDirs[0], messages: messages.slice(messageStart) })
    await command('cancel-spawn off')
    const beforeSuccess = new Set(fs.readdirSync(mapsDir)); const successMessageStart = messages.length; tomap()
    await until(state => state.drops.some(item => item.path && !beforeSuccess.has(item.path.split('/')[1])), 'normal full inventory delivery drops map')
    await until(() => /Generated map, frame size/.test(JSON.stringify(messages.slice(successMessageStart))), 'successful generation notification')
    passed('normal full inventory generation delivers a real dropped map and reports success', { messages: messages.slice(successMessageStart) })

    await command('setup'); const mergePath = await generate(); await command('merge-items')
    await until(state => state.events.some(event => event.event === 'remove' && event.cause === 'MERGE' && event.path === mergePath), 'native item merge')
    await until(state => state.drops.some(item => item.path === mergePath && item.amount === 2), 'merged stack has both copies')
    assert.ok(fs.existsSync(folder(mergePath)))
    passed('native duplicate item MERGE preserves artwork data', { imagePath: mergePath })

    await command('setup'); const protectedPath = await generate()
    await command('cancel-despawn on'); await command('drop-mode despawn'); bot.dropStack()
    await until(state => state.events.some(event => event.event === 'despawn' && event.cancelled && event.path === protectedPath), 'cancelled natural despawn')
    assert.ok(fs.existsSync(folder(protectedPath))); assert.ok(read().drops.some(item => item.path === protectedPath))
    await command('cancel-despawn off'); await command('age-drop')
    await until(state => !fs.existsSync(folder(protectedPath)) && state.events.some(event => event.event === 'remove' && event.cause === 'DESPAWN' && event.path === protectedPath), 'actual despawn after cancellation removed')
    passed('cancelled despawn preserves item/files; later actual despawn deletes data', { imagePath: protectedPath })

    await command('setup'); await command('legacy-fixture')
    passed('expanded legacy-format fixture persisted through real Paper APIs for offline migration')
    fs.writeFileSync(path.join(server, '../restart-extended-report.json'), JSON.stringify({ status: 'passed', pluginSha256, results, state: read(), messages }, null, 2))
  } catch (error) {
    fs.writeFileSync(path.join(server, '../restart-extended-report.json'), JSON.stringify({ status: 'failed', pluginSha256, error: error.stack, results, state: read(), messages }, null, 2))
    throw error
  } finally { bot.close(); imageServer.close() }
}

main().catch(error => { console.error(error.stack); process.exitCode = 1 })
