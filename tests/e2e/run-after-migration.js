'use strict'

// Run only after the expanded fixture has been migrated while the server was stopped.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const { connect } = require('./client/bot')
const server = process.env.RCM_E2E_SERVER || '/tmp/recolorfulmap-e2e/server'
const helper = path.join(server, 'plugins/RCME2E')
const read = () => JSON.parse(fs.readFileSync(path.join(helper, 'state.json'), 'utf8'))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const reportFile = process.env.RCM_E2E_REPORT || path.join(server, '../migration-gameplay-report.json')
const results = []
const pluginSha256 = crypto.createHash('sha256').update(fs.readFileSync(path.join(server, 'plugins/ReColorfulMap.jar'))).digest('hex')
async function until (predicate, description, timeout = 20000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) { const state = read(); if (predicate(state)) return state; await sleep(100) }
  throw new Error(`Timed out: ${description}`)
}
function passed (name, details = {}) { results.push({ name, status: 'passed', ...details }); console.log(JSON.stringify(results.at(-1))) }
async function main () {
  const bot = await connect({ port: Number(process.env.RCM_E2E_PORT || 25576) })
  await sleep(700)
  async function command (value) {
    const before = read().tick; bot.command(`rcme2e ${value}`)
    const state = await until(state => state.tick > before && state.phase === value.split(' ')[0], value)
    assert.equal(state.error, '')
    return state
  }
  try {
    const fixture = JSON.parse(fs.readFileSync(path.join(helper, 'legacy-fixture.json'), 'utf8'))
    const artwork = `maps/${fixture.artworkUuid}/thumb.png`
    await command('migration-check')
    const observed = JSON.parse(fs.readFileSync(path.join(helper, 'migration-observed.json'), 'utf8'))
    for (const key of ['inventory', 'enderChest', 'chest', 'shulker']) {
      assert.equal(observed[key].path, artwork, key)
      assert.ok(observed[key].fileExists, key)
      assert.ok(observed[key].renderers.includes('cn.lunadeer.reColorfulMap.ImageRenderer'), key)
      assert.match(observed[key].name, /旧版地图 中文 🌈/, key)
    }
    assert.equal(observed.vanilla.path, null)
    assert.equal(observed.vanilla.mapId, fixture.vanillaMapId)
    for (const frame of read().frames) assert.equal(frame.item.path, fixture.maps[frame.item.mapId])
    assert.ok(read().drops.some(item => item.path === artwork && item.fileExists))
    passed('Paper loads migrated inventory, ender chest, chest, nested shulker, frames and dropped item while vanilla maps remain unbound', { observed })

    const baseline = JSON.parse(fs.readFileSync(process.env.RCM_E2E_BASELINE || path.join(server, '../gameplay-report.json'), 'utf8'))
    const hashesByTile = Object.fromEntries(baseline.state.frames.map(frame => [path.basename(frame.item.path), baseline.checkpoint.pixelHashes[frame.item.mapId]]))
    const expected = Object.fromEntries(read().frames.map(frame => [frame.item.mapId, hashesByTile[path.basename(frame.item.path)]]))
    await until(() => Object.entries(expected).every(([id, hash]) => bot.maps.get(Number(id))?.received === 16384
      && crypto.createHash('sha256').update(bot.maps.get(Number(id)).pixels).digest('hex') === hash), 'migrated frame pixels match deterministic image')
    passed('all four migrated frame maps render exact expected 128x128 network pixels', { pixelHashes: expected })

    const oldDrops = new Set(read().drops.map(item => item.uuid))
    await command('teleport-frame 3'); bot.attack(read().frames[3].entityId)
    await until(state => state.frames.every(frame => frame.item?.type === 'AIR') && state.drops.some(item => item.path === artwork && !oldDrops.has(item.uuid)), 'non-origin legacy tile recovers entire artwork')
    const recovered = read().drops.find(item => item.path === artwork && !oldDrops.has(item.uuid))
    // The fixture also contains another persistent copy. Target the newly
    // recovered entity by UUID; MapView reuse deliberately shares IDs with copies.
    await command(`collect-uuid ${recovered.uuid}`)
    await until(state => state.events.some(event => event.event === 'pickup' && event.uuid === recovered.uuid && !event.cancelled) && !state.drops.some(item => item.uuid === recovered.uuid), 'real player pickup of recovered legacy artwork')
    await command(`equip-map ${recovered.mapId}`); assert.equal(read().hand.mapId, recovered.mapId)
    await command('teleport-frame 0'); bot.interact(read().frames[0].entityId)
    await until(state => state.frames.every(frame => frame.item?.path?.startsWith(`maps/${fixture.artworkUuid}/`) && !frame.item.path.endsWith('/thumb.png')), 'recovered legacy artwork placed again')
    assert.ok(fs.existsSync(path.join(server, 'plugins/ReColorfulMap/maps', fixture.artworkUuid)))
    passed('real client attack on non-origin migrated tile recovers the full artwork and can place it again')
    fs.writeFileSync(reportFile, JSON.stringify({ status: 'passed', pluginSha256, results, state: read() }, null, 2))
  } catch (error) {
    fs.writeFileSync(reportFile, JSON.stringify({ status: 'failed', pluginSha256, error: error.stack, results, state: read() }, null, 2))
    throw error
  } finally { bot.close() }
}
main().catch(error => { console.error(error.stack); process.exitCode = 1 })
