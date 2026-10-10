'use strict'

// Actual 26.2 network actions, with Bukkit-only fixture setup/observation.
const fs = require('node:fs')
const path = require('node:path')
const http = require('node:http')
const zlib = require('node:zlib')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const { connect } = require('./client/bot')

const server = process.env.RCM_E2E_SERVER || '/tmp/recolorfulmap-e2e/server'
const stateFile = path.join(server, 'plugins/RCME2E/state.json')
const reportFile = process.env.RCM_E2E_REPORT || path.join(server, '../gameplay-report.json')
const pluginSha256 = crypto.createHash('sha256').update(fs.readFileSync(path.join(server, 'plugins/ReColorfulMap.jar'))).digest('hex')
const results = []
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const read = () => JSON.parse(fs.readFileSync(stateFile, 'utf8'))
const mapItems = state => state.inventory.filter(item => item.path?.endsWith('/thumb.png'))
const painted = state => state.frames.length === 4 && state.frames.every(frame => frame.item?.path)
const empty = state => state.frames.length === 4 && state.frames.every(frame => frame.item?.type === 'AIR')
const folder = image => path.join(server, 'plugins/ReColorfulMap', image.split('/').slice(0, 2).join('/'))

async function until (predicate, description, timeout = 12000) {
  const end = Date.now() + timeout
  let state
  while (Date.now() < end) {
    state = read()
    if (predicate(state)) return state
    await sleep(100)
  }
  throw new Error(`Timed out: ${description}; phase=${state?.phase} error=${state?.error}`)
}

function passed (name, details = {}) {
  results.push({ name, status: 'passed', ...details })
  console.log(JSON.stringify(results.at(-1)))
}

function png (width = 256, height = 256) {
  const crc = buffer => {
    let value = 0xffffffff
    for (const byte of buffer) {
      value ^= byte
      for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0)
    }
    return (value ^ 0xffffffff) >>> 0
  }
  const chunk = (kind, payload) => {
    const body = Buffer.concat([Buffer.from(kind), payload]); const header = Buffer.alloc(4); const tail = Buffer.alloc(4)
    header.writeUInt32BE(payload.length); tail.writeUInt32BE(crc(body))
    return Buffer.concat([header, body, tail])
  }
  const header = Buffer.alloc(13); header.writeUInt32BE(width, 0); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 6
  const pixels = Buffer.alloc(height * (width * 4 + 1))
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const offset = y * (width * 4 + 1) + 1 + x * 4
    pixels[offset] = x; pixels[offset + 1] = y; pixels[offset + 2] = 100; pixels[offset + 3] = 255
  }
  return Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', header), chunk('IDAT', zlib.deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))])
}

async function main () {
  const image = png()
  const httpServer = http.createServer((request, response) => { response.setHeader('Content-Type', 'image/png'); response.end(image) })
  await new Promise(resolve => httpServer.listen(0, '127.0.0.1', resolve))
  const imageUrl = `http://127.0.0.1:${httpServer.address().port}/gradient.png`
  let bot
  try {
    bot = await connect({ port: Number(process.env.RCM_E2E_PORT || 25576) })
  } catch (error) {
    httpServer.close()
    throw error
  }
  // A restarted server resets its tick counter; let this player's first fresh
  // snapshot replace the previous run's on-disk observation before commands.
  await sleep(700)
  const mapPackets = []
  const messages = []
  bot.on('map', packet => { if (packet.data?.length) mapPackets.push({ id: packet.itemDamage, bytes: packet.data.length, colors: new Set(packet.data).size }) })
  bot.on('packet', (packet, metadata) => {
    if (metadata.name.includes('chat') || ['action_bar', 'set_title_text', 'set_title_subtitle'].includes(metadata.name)) messages.push({ type: metadata.name, packet })
  })
  async function command (value) {
    const before = read().tick
    bot.command(`rcme2e ${value}`)
    const state = await until(state => state.tick > before && state.phase === value.split(' ')[0], value)
    assert.equal(state.error, '', value)
    return state
  }
  async function generate (scale = 1) {
    const old = new Set(mapItems(read()).map(item => item.path))
    bot.command(`tomap ${imageUrl} ${scale}`)
    const state = await until(state => mapItems(state).some(item => !old.has(item.path)), 'tomap generates an image')
    await command('equip')
    return read().hand.path
  }
  async function place () {
    await command('teleport-frame 0')
    bot.interact(read().frames[0].entityId)
    return until(painted, 'place all four frames')
  }
  async function pickup (index, imagePath) {
    await command(`teleport-frame ${index}`)
    bot.attack(read().frames[index].entityId)
    await until(state => empty(state) && (mapItems(state).length || state.drops.some(item => item.path === imagePath)), `whole-art pickup at frame ${index}`)
    if (!mapItems(read()).some(item => item.path === imagePath)) await command(`collect ${imagePath}`)
    await until(state => mapItems(state).some(item => item.path === imagePath), 'natural player pickup')
    assert.ok(fs.existsSync(folder(imagePath)), 'pickup preserves artwork files')
    await command('equip')
  }
  try {
    await command('setup')
    const scaled = await generate(0.5)
    assert.equal(fs.readdirSync(folder(scaled)).filter(name => /^\d+_\d+\.png$/.test(name)).length, 1)
    passed('real scaled generation preserves the scale argument', { scale: 0.5, artwork: scaled })
    await command('setup')
    const artwork = await generate()
    assert.equal(fs.readdirSync(folder(artwork)).filter(name => /^\d+_\d+\.png$/.test(name)).length, 4)
    passed('real command downloads and generates four tiles', { artwork })

    await command('protect 3'); await command('teleport-frame 0')
    bot.interact(read().frames[0].entityId); await sleep(600)
    assert.ok(empty(read())); assert.equal(read().hand.path, artwork)
    passed('placement protection cancellation preserves hand and all frames')
    await command('protect off'); await place()
    assert.equal(new Set(read().frames.map(frame => frame.item.mapId)).size, 4)

    await command('protect-source 3'); await command('teleport-frame 2')
    const beforeProtection = read().frames.map(frame => frame.item.path)
    bot.attack(read().frames[2].entityId); await sleep(600)
    assert.deepEqual(read().frames.map(frame => frame.item.path), beforeProtection)
    assert.ok(read().events.some(event => event.event === 'protection-damage' && event.playerSource && event.type === 'minecraft:player_attack'))
    fs.writeFileSync(path.join(server, '../after-damage-source.json'), JSON.stringify(read(), null, 2))
    passed('real player DamageSource is retained in protected neighbouring frame probe')
    await command('protect-source off')

    await command('cancel-spawn on'); await command('teleport-frame 2')
    bot.attack(read().frames[2].entityId); await sleep(600)
    assert.ok(painted(read())); assert.ok(fs.existsSync(folder(artwork)))
    passed('cancelled recovered-item spawn rolls back all four frames')
    await command('cancel-spawn off')
    await pickup(0, artwork)

    for (const direction of ['SOUTH', 'NORTH', 'EAST', 'WEST']) {
      await command(`wall ${direction}`)
      for (let index = 0; index < 4; index++) {
        await place(); await pickup(index, artwork)
        passed('any tile recovers entire artwork and can be placed again', { direction, index })
      }
    }

    await command('wall SOUTH'); await place(); await command('full'); await command('teleport-frame 3')
    bot.attack(read().frames[3].entityId)
    await until(state => empty(state) && state.drops.some(item => item.path === artwork), 'full inventory leaves recovered map dropped')
    assert.equal(mapItems(read()).length, 0); assert.ok(fs.existsSync(folder(artwork)))
    await command('clear-stone'); await command(`collect ${artwork}`); await until(state => mapItems(state).length, 'collect after inventory frees'); await command('equip')
    passed('full inventory leaves one recoverable whole-map drop without deleting files')

    await command('wall SOUTH'); await command('remove-frame 3'); await command('teleport-frame 0')
    bot.interact(read().frames[0].entityId); await sleep(500)
    assert.equal(read().hand.path, artwork)
    assert.ok(read().frames.filter(frame => frame.item).every(frame => frame.item.type === 'AIR'))
    passed('missing frame rejects placement without consuming the map')

    await command('wall SOUTH'); await command('teleport-frame 0')
    bot.interact(read().frames[0].entityId); bot.hotbar(1)
    await sleep(600)
    assert.ok(empty(read())); assert.ok(mapItems(read()).some(item => item.path === artwork))
    bot.hotbar(0)
    passed('real hand-slot switch during asynchronous placement preserves the map and empty frames')

    for (const cause of ['damage', 'despawn', 'void']) {
      await command('setup'); const imagePath = await generate()
      await command(`drop-mode ${cause}`); bot.dropStack()
      await until(state => !fs.existsSync(folder(imagePath)) && state.events.some(event => event.event === 'remove' && event.path === imagePath), `${cause} removes artwork directory`)
      const removal = read().events.find(event => event.event === 'remove' && event.path === imagePath)
      assert.ok(removal)
      passed('real dropped item destruction deletes artwork', { mechanism: cause, eventCause: removal.cause, imagePath })
    }

    await command('setup'); const retained = await generate()
    await command('drop-mode keep'); bot.dropStack()
    await until(state => state.drops.some(item => item.path === retained), 'dropped map exists')
    await command('unload')
    await until(state => state.events.some(event => event.event === 'remove' && event.cause === 'UNLOAD' && event.path === retained), 'actual chunk unload', 20000)
    assert.ok(fs.existsSync(folder(retained)))
    await command('reload-chunk'); await until(state => state.drops.some(item => item.path === retained), 'saved item reloads')
    await command(`collect ${retained}`); await until(state => mapItems(state).some(item => item.path === retained), 'reloaded map picked up')
    assert.ok(fs.existsSync(folder(retained)))
    passed('actual chunk unload/reload and subsequent pickup preserve artwork files', { imagePath: retained })

    await command('equip'); await command('wall SOUTH'); await place()
    await command('mix-frame 3'); await command('teleport-frame 2')
    const mixed = read().frames.map(frame => frame.item.path)
    bot.attack(read().frames[2].entityId); await sleep(500)
    assert.equal(read().frames[0].item.path, mixed[0]); assert.equal(read().frames[1].item.path, mixed[1]); assert.equal(read().frames[3].item.path, null)
    assert.ok(fs.existsSync(folder(retained)))
    passed('mixed wall does not clear neighbouring frames or delete image data')

    // Leave a real, saved wall and a separate inventory item for the restart phase.
    await command('setup'); const wallArt = await generate(); await place(); const handArt = await generate()
    const checkpoint = { frames: read().frames.map(frame => frame.uuid), wallArt, handArt }
    const mapIds = [...read().frames.map(frame => frame.item.mapId), read().hand.mapId]
    await until(() => mapIds.every(id => bot.maps.get(id)?.received === 16384 && new Set(bot.maps.get(id).pixels).size > 8 && Date.now() - bot.maps.get(id).lastContentAt > 700), 'stable nonblank complete pixels for every wall and hand map', 30000)
    checkpoint.pixelHashes = Object.fromEntries(mapIds.map(id => [id, crypto.createHash('sha256').update(bot.maps.get(id).pixels).digest('hex')]))
    fs.writeFileSync(path.join(server, 'plugins/RCME2E/restart-checkpoint.json'), JSON.stringify(checkpoint, null, 2))
    assert.ok(mapPackets.some(packet => packet.bytes === 16384 && packet.colors > 8), 'client received nonblank image pixels')
    passed('real client receives nonblank 128x128 map pixel packets', { packets: mapPackets.length })
    fs.writeFileSync(reportFile, JSON.stringify({ status: 'passed', pluginSha256, results, mapPackets, messages, state: read(), checkpoint }, null, 2))
  } catch (error) {
    fs.writeFileSync(reportFile, JSON.stringify({ status: 'failed', pluginSha256, error: error.stack, results, mapPackets, messages, state: read() }, null, 2))
    throw error
  } finally {
    bot.close(); httpServer.close()
  }
}

module.exports = { png }
if (require.main === module) main().catch(error => { console.error(error.stack); process.exitCode = 1 })
