'use strict'

// Reuse the stopped, already-migrated world produced by the actual ColorfulMap
// 2.3 JAR. This driver never creates replacement maps or resets the fixture.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const { connect } = require('./client/bot')

const root = process.env.RCM_ORIGINAL_E2E_ROOT || '/tmp/recolorfulmap-legacy-e2e'
const server = path.join(root, 'server')
const stateFile = path.join(server, 'plugins/RCME2E/state.json')
const reportFile = process.env.RCM_E2E_REPORT || path.join(root, 'optimization-original-upgrade.json')
const checkpoint = JSON.parse(fs.readFileSync(path.join(root, 'replacement-state.json'), 'utf8'))
const results = []
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const hash = data => crypto.createHash('sha256').update(data).digest('hex')
const pluginSha256 = hash(fs.readFileSync(path.join(server, 'plugins/ReColorfulMap.jar')))
const read = () => { try { return JSON.parse(fs.readFileSync(stateFile, 'utf8')) } catch { return null } }

function pngHashes (directory) {
  const hashes = {}
  function visit (current) {
    for (const entry of fs.readdirSync(current, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const target = path.join(current, entry.name)
      if (entry.isDirectory()) visit(target)
      else if (entry.isFile() && entry.name.endsWith('.png')) hashes[path.relative(directory, target)] = hash(fs.readFileSync(target))
    }
  }
  visit(directory)
  return hashes
}

async function until (predicate, description, timeout = 45000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const state = read()
    if (state && predicate(state)) return state
    await sleep(100)
  }
  throw new Error(`Timed out: ${description}; state=${JSON.stringify(read())}`)
}

function passed (name, details = {}) {
  results.push({ name, status: 'passed', ...details })
  console.log(JSON.stringify(results.at(-1)))
}

async function main () {
  assert.ok(fs.existsSync(path.join(server, '.recolorfulmap-e2e.json')), 'marked disposable server required')
  const targetMaps = path.join(server, 'plugins/ReColorfulMap/maps')
  const sourceHashes = pngHashes(path.join(server, 'plugins/ColorfulMap/maps'))
  const beforeHashes = pngHashes(targetMaps)
  const bot = await connect({ port: Number(process.env.RCM_E2E_PORT || 25577) })
  async function command (value) {
    const before = read()?.tick
    bot.command(`rcme2e ${value}`)
    const state = await until(state => state.tick !== before && state.phase === value.split(' ')[0], value)
    assert.equal(state.error, '')
    return state
  }
  const pixelSummary = ids => Object.fromEntries(ids.map(id => {
    const image = bot.maps.get(id)
    return [id, { hash: hash(image.pixels), distinctColors: new Set(image.pixels).size, received: image.received }]
  }))
  let before
  try {
    assert.deepEqual(beforeHashes, sourceHashes, 'migrated PNG bytes must still match the original ColorfulMap data')
    passed('all original ColorfulMap PNG hashes remain unchanged', { files: Object.keys(sourceHashes).length })
    await sleep(700)
    await command(`track ${checkpoint.frames.map(frame => frame.uuid).join(',')}`)
    before = await until(state => state.frames.length === 4 && state.frames.every(frame => frame.item?.path && frame.item.fileExists), 'all original frames loaded')
    const expectedPaths = checkpoint.frames.map(frame => frame.item.path)
    assert.deepEqual(before.frames.map(frame => frame.item.path), expectedPaths)
    const inventoryMap = before.inventory.find(item => item.mapId === 5 && item.path)
    assert.ok(inventoryMap?.fileExists, 'original inventory map ID 5 still resolves')
    assert.ok(before.enderChest.some(item => item.path === inventoryMap.path && item.mapId === 5 && item.fileExists))
    assert.ok(before.inventory.some(item => item.type === 'SHULKER_BOX' && item.containedPaths?.includes(inventoryMap.path)))
    for (const frame of before.frames) assert.ok(frame.item.renderers.includes('cn.lunadeer.reColorfulMap.ImageRenderer'))
    passed('original inventory, ender chest, nested shulker and four frame references load', {
      inventory: before.inventory, enderChest: before.enderChest, frames: before.frames
    })

    await command('equip-map 5')
    const tileIds = before.frames.map(frame => frame.item.mapId)
    const allIds = [...tileIds, 5]
    await until(() => allIds.every(id => {
      const image = bot.maps.get(id)
      return image?.received === 16384 && new Set(image.pixels).size > 8 && Date.now() - image.lastContentAt > 1500
    }), 'natural nonempty client pixels for original four tiles and inventory map')
    const pixelsBefore = pixelSummary(allIds)
    passed('original inventory map and four wall tiles render complete colorful client pixels', { pixels: pixelsBefore })

    const wallArtwork = path.posix.dirname(expectedPaths[0])
    const thumbnail = `${wallArtwork}/thumb.png`
    const oldDrops = new Set(read().drops.map(item => item.uuid))
    await command('teleport-frame 3')
    bot.attack(read().frames[3].entityId)
    await until(state => state.frames.every(frame => frame.item?.type === 'AIR') &&
      state.drops.some(item => item.path === thumbnail && !oldDrops.has(item.uuid)), 'non-origin whole artwork retrieval')
    const recovered = read().drops.find(item => item.path === thumbnail && !oldDrops.has(item.uuid))
    await command(`collect-uuid ${recovered.uuid}`)
    await until(state => state.events.some(event => event.event === 'pickup' && event.uuid === recovered.uuid && !event.cancelled) &&
      !state.drops.some(item => item.uuid === recovered.uuid), 'real pickup of the recovered original painting')
    await command(`equip-map ${recovered.mapId}`)
    assert.equal(read().hand.path, thumbnail)
    await command('teleport-frame 0')
    bot.interact(read().frames[0].entityId)
    await until(state => state.frames.every(frame => frame.item?.path?.startsWith(`${wallArtwork}/`)), 'original painting placed again')
    assert.deepEqual(read().frames.map(frame => frame.item.path), expectedPaths)
    assert.deepEqual(read().frames.map(frame => frame.item.mapId), tileIds, 'existing migrated tile IDs must be reused')
    passed('real non-origin retrieval, UUID-specific pickup and replacement preserve tile IDs', { recovered, tileIds })

    const after = read()
    assert.deepEqual(after.enderChest, before.enderChest, 'unrelated ender chest unchanged')
    assert.deepEqual(after.inventory.filter(item => item.type === 'SHULKER_BOX'), before.inventory.filter(item => item.type === 'SHULKER_BOX'))
    assert.ok(after.inventory.some(item => item.mapId === 5 && item.path === inventoryMap.path))
    const afterHashes = pngHashes(targetMaps)
    assert.deepEqual(afterHashes, beforeHashes)
    passed('placement preserves original PNG bytes and unrelated inventory containers', { pngHashes: afterHashes })
    fs.writeFileSync(reportFile, JSON.stringify({ status: 'passed', pluginSha256, results, before, state: after }, null, 2) + '\n')
  } catch (error) {
    fs.writeFileSync(reportFile, JSON.stringify({ status: 'failed', pluginSha256, error: error.stack, results, before, state: read() }, null, 2) + '\n')
    throw error
  } finally { bot.close() }
}

main().catch(error => { console.error(error.stack); process.exitCode = 1 })
