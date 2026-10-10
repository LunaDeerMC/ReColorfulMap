'use strict'

// Network-driven optimization regressions. The helper only prepares fixtures and observes state.
const fs = require('node:fs')
const path = require('node:path')
const http = require('node:http')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const { connect } = require('./client/bot')
const { png } = require('./run-scenarios')
const server = process.env.RCM_E2E_SERVER || '/tmp/recolorfulmap-e2e/server'
const phase = process.argv[2]
assert.ok(['before-restart', 'after-restart'].includes(phase), 'Specify before-restart or after-restart')
const helper = path.join(server, 'plugins/RCME2E')
const data = path.join(server, 'plugins/ReColorfulMap')
const configFile = path.join(data, 'config.yml')
const reportFile = process.env.RCM_E2E_REPORT || path.join(server, '../optimization-features-report.json')
const sha256 = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
const pluginSha256 = sha256(path.join(server, 'plugins/ReColorfulMap.jar'))
const read = () => JSON.parse(fs.readFileSync(path.join(helper, 'state.json'), 'utf8'))
const economy = () => JSON.parse(fs.readFileSync(path.join(server, 'plugins/Vault/economy.json'), 'utf8'))
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const resumeContainers = process.env.RCM_E2E_RESUME_CONTAINERS === '1'
const resumed = resumeContainers ? JSON.parse(fs.readFileSync(reportFile, 'utf8')) : null
if (resumed) { assert.equal(resumed.pluginSha256, pluginSha256); assert.equal(resumed.phase, 'before-restart') }
const results = resumed ? [...resumed.results] : []
const segments = resumed ? [...(resumed.segments || []), { resumedAt: 'container matrix', previousError: resumed.error, economy: resumed.economy, messages: resumed.messages }] : []
const mapItems = state => state.inventory.filter(item => item.path?.endsWith('/thumb.png'))
const imageFolder = reference => path.join(data, reference.split('/').slice(0, 2).join('/'))
const receipts = () => {
  const folder = path.join(data, 'deliveries')
  if (!fs.existsSync(folder)) return []
  return fs.readdirSync(folder).filter(name => name.endsWith('.properties')).map(name => Object.fromEntries(
    fs.readFileSync(path.join(folder, name), 'utf8').split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => {
      const split = line.indexOf('='); return [line.slice(0, split), line.slice(split + 1)]
    })))
}
async function until (condition, description, timeout = 20000) {
  const end = Date.now() + timeout
  while (Date.now() < end) { const state = read(); if (condition(state)) return state; await sleep(100) }
  throw new Error(`Timed out: ${description}; phase=${read().phase}, error=${read().error}`)
}
function passed (name, details = {}) {
  results.push({ name, status: 'passed', ...details })
  const entry = results.at(-1)
  console.log(JSON.stringify(entry.pixelHashes ? { ...entry, pixelHashes: `${Object.keys(entry.pixelHashes).length} full canvas hashes recorded in report` } : entry))
}
function text (tag) {
  if (tag == null) return ''
  if (typeof tag === 'string') return tag
  if (Array.isArray(tag)) return tag.map(text).join('')
  if (tag.type && Object.hasOwn(tag, 'value')) return text(tag.value)
  return text(tag.text) + text(tag.extra) + text(tag.with)
}
const validConfig = (overrides = '') => `version: 3\nmax-frame-x: 32\nmax-frame-y: 18\nlanguage: en_us\neconomy:\n  enable: true\n  cost-per-map: 10.5\naddress-white-list: []\ndebug: false\n${overrides}`

async function main () {
  const small = png(); const large = png(17 * 128, 8 * 128)
  let delayedRequested = false
  const imageServer = http.createServer((request, response) => {
    if (request.url === '/missing.png') { response.writeHead(404); response.end(); return }
    const respond = () => { response.setHeader('Content-Type', 'image/png'); response.end(request.url === '/large.png' ? large : small) }
    if (request.url === '/slow.png') { delayedRequested = true; setTimeout(respond, 2500) } else respond()
  })
  await new Promise(resolve => imageServer.listen(0, '127.0.0.1', resolve))
  const url = name => `http://127.0.0.1:${imageServer.address().port}/${name || 'small.png'}`
  let bot
  try { bot = await connect({ port: Number(process.env.RCM_E2E_PORT || 25576) }) } catch (error) { imageServer.close(); throw error }
  const messages = []
  bot.on('chat', packet => messages.push(text(packet.content)))
  await sleep(700)
  async function command (value) {
    const before = read().tick; bot.command(`rcme2e ${value}`)
    const state = await until(state => state.tick > before && state.phase === value.split(' ')[0], value)
    assert.equal(state.error, ''); return state
  }
  async function vault (value) {
    const before = economy().updated; bot.command(`rcmvault ${value}`)
    await until(() => economy().updated > before, `Vault ${value}`)
  }
  async function reload (contents, success = true) {
    fs.writeFileSync(configFile, contents)
    const before = messages.length; bot.command('reloadcolorfulmap')
    await until(() => messages.slice(before).some(message => message.includes(success ? 'Configuration file reloaded successfully' : 'Failed to load configuration')), 'configuration reload response')
  }
  async function generate (filename = 'small.png') {
    const old = new Set(receipts().map(receipt => receipt.artwork))
    bot.command(`tomap ${url(filename)}`)
    await until(state => mapItems(state).some(item => !old.has(item.path.split('/')[1])), 'generated artwork delivered', 60000)
    const item = mapItems(read()).find(item => !old.has(item.path.split('/')[1])); await command('equip')
    await until(() => receipts().some(receipt => receipt.artwork === item.path.split('/')[1] && receipt.delivery === 'DELIVERED'), 'delivery receipt committed')
    return item
  }
  async function place (count = 4) {
    await command('teleport-frame 0'); bot.interact(read().frames[0].entityId)
    return until(state => state.frames.length === count && state.frames.every(frame => frame.item?.path), 'complete wall placement', 60000)
  }
  async function recover (index, reference) {
    const old = new Set(read().drops.map(item => item.uuid))
    await command(`teleport-frame ${index}`); bot.attack(read().frames[index].entityId)
    await until(state => state.frames.every(frame => frame.item?.type === 'AIR') && state.drops.some(item => item.path === reference && !old.has(item.uuid)), 'whole painting recovered')
    const drop = read().drops.find(item => item.path === reference && !old.has(item.uuid))
    await command(`collect-uuid ${drop.uuid}`)
    await until(state => state.events.some(event => event.event === 'pickup' && event.uuid === drop.uuid && !event.cancelled) && !state.drops.some(item => item.uuid === drop.uuid), 'actual recovered entity pickup')
    await command('equip'); return read().hand
  }
  try {
    if (phase === 'after-restart') {
      const before = JSON.parse(fs.readFileSync(reportFile, 'utf8'))
      assert.equal(before.pluginSha256, pluginSha256)
      const pending = receipts().find(receipt => receipt.artwork === before.pendingArtwork)
      assert.equal(pending.delivery, 'PENDING'); assert.equal(pending.payment, 'UNPAID')
      assert.equal(read().configuration.economyEnable, false, 'new generation charging disabled at restart')
      const initialTransactions = economy().transactions.length
      const start = messages.length; bot.command('mapmanage list')
      await until(() => messages.slice(start).some(message => message.includes(pending.artwork)), 'pending artwork listed after restart')
      bot.command(`mapmanage claim ${pending.artwork}`)
      await until(state => mapItems(state).some(item => item.path.includes(pending.artwork)), 'claim offline-completed map after restart')
      await until(() => receipts().find(receipt => receipt.artwork === pending.artwork)?.delivery === 'DELIVERED', 'claim receipt persisted')
      assert.deepEqual(economy().transactions.slice(initialTransactions).map(tx => [tx.kind, tx.amount, tx.success]), [['withdrawPlayer', 42, true]])
      const inventoryCount = mapItems(read()).filter(item => item.path.includes(pending.artwork)).reduce((count, item) => count + item.amount, 0)
      bot.command(`mapmanage claim ${pending.artwork}`); bot.command(`mapmanage claim ${pending.artwork}`)
      await sleep(900)
      assert.equal(economy().transactions.length, initialTransactions + 1)
      assert.equal(mapItems(read()).filter(item => item.path.includes(pending.artwork)).reduce((count, item) => count + item.amount, 0), inventoryCount)
      passed('offline-completed artwork survives cold restart; list/claim deliver and charge once; repeated claim gives no duplicate', { artwork: pending.artwork, amount: 42 })
      await command('equip')
      const hand = read().hand
      await until(() => bot.maps.get(hand.mapId)?.received === 16384 && new Set(bot.maps.get(hand.mapId).pixels).size > 8, 'claimed artwork client pixels')
      passed('claimed artwork restores nonblank 128x128 network pixels', { mapId: hand.mapId })
      const free = await generate()
      assert.equal(economy().transactions.length, initialTransactions + 1)
      assert.equal(Number(receipts().find(receipt => receipt.artwork === free.path.split('/')[1]).amount), 0)
      passed('after restart with charging disabled, old pending quote is charged once while newly generated artwork is free')
      const original = fs.readFileSync(path.join(helper, 'optimization-original-config.yml'), 'utf8')
      await reload(original)
      fs.writeFileSync(reportFile, JSON.stringify({ ...before, status: 'passed', phase: 'complete', results: [...before.results, ...results], afterRestart: { economy: economy(), state: read(), messages } }, null, 2))
      return
    }

    if (!resumeContainers) {
      fs.copyFileSync(configFile, path.join(helper, 'optimization-original-config.yml'))
      await reload(validConfig()); await command('setup'); await vault('reset 1000000')
      const generated = await generate()
      assert.deepEqual(economy().transactions.map(tx => [tx.kind, tx.amount, tx.success]), [['withdrawPlayer', 42, true]])
      const first = await place(); const ids = first.frames.map(frame => frame.item.mapId)
      for (let cycle = 0; cycle < 3; cycle++) {
        const hand = await recover(3, generated.path); assert.equal(hand.mapId, generated.mapId)
        const placed = await place(); assert.deepEqual(placed.frames.map(frame => frame.item.mapId), ids)
      }
      assert.equal(economy().transactions.length, 1)
      passed('three real whole-art pickup/replacement cycles reuse every tile and thumbnail map ID and are free', { mapIds: ids, thumbnailId: generated.mapId, generationCharge: 42 })

      const previous = read().configuration
      const broken = 'version: 3\nmax-frame-x: [\n'
      await reload(broken, false); assert.equal(fs.readFileSync(configFile, 'utf8'), broken); assert.deepEqual(read().configuration, previous)
      const wrongType = 'version: 3\nmax-frame-x: 7\ndebug: true\neconomy:\n  cost-per-map: incorrect\n'
      await reload(wrongType, false); assert.equal(fs.readFileSync(configFile, 'utf8'), wrongType); assert.deepEqual(read().configuration, previous)
      await reload(validConfig().replace('debug: false', 'debug: true'))
      await until(state => state.configuration.debug && state.configuration.loggerDebug, 'debug setting and logger update together')
      await reload(validConfig()); await until(state => !state.configuration.debug && !state.configuration.loggerDebug, 'debug disable applies')
      passed('syntax/type-invalid YAML preserves exact file bytes and the complete previous snapshot; valid reload changes debug immediately')

      await command('setup'); const beforeFailed = economy().transactions.length; const msg = messages.length
      bot.command(`tomap ${url('missing.png')}`)
      await until(() => messages.slice(msg).some(message => message.includes('Failed to generate map')), 'download failure notification')
      assert.equal(economy().transactions.length, beforeFailed)
      passed('failed download is not charged')

      await command('full'); await command('cancel-spawn on')
      const beforeReceipts = new Set(receipts().map(receipt => receipt.artwork)); const beforeMoney = economy().balance; const beforeDeliveryFailure = messages.length
      bot.command(`tomap ${url()}`)
      await until(() => receipts().some(receipt => !beforeReceipts.has(receipt.artwork) && receipt.payment === 'PAID' && receipt.delivery === 'DELIVERING'), 'ambiguous delivery receipt is frozen')
      await until(() => messages.slice(beforeDeliveryFailure).some(message => message.includes('Failed to generate map')), 'ambiguous delivery failure response')
      const uncertain = receipts().find(receipt => !beforeReceipts.has(receipt.artwork))
      assert.equal(uncertain.delivery, 'DELIVERING'); assert.equal(economy().balance, beforeMoney - 42)
      const txCount = economy().transactions.length
      bot.command(`mapmanage claim ${uncertain.artwork}`); await sleep(700)
      assert.equal(economy().transactions.length, txCount)
      assert.equal(receipts().find(receipt => receipt.artwork === uncertain.artwork).delivery, 'DELIVERING')
      assert.ok(fs.existsSync(path.join(data, 'maps', uncertain.artwork, 'thumb.png')))
      passed('cancelled delivery freezes its single paid charge, retains PNG and prevents ambiguous reissue or charging again', { artwork: uncertain.artwork, transactions: economy().transactions.slice(-1) })

      await command('setup'); await vault('reset 0')
      const beforePoor = new Set(receipts().map(receipt => receipt.artwork)); bot.command(`tomap ${url()}`)
      await until(() => receipts().some(receipt => !beforePoor.has(receipt.artwork)), 'insufficient-funds receipt')
      await sleep(800)
      const poor = receipts().find(receipt => !beforePoor.has(receipt.artwork))
      assert.equal(poor.delivery, 'PENDING'); assert.equal(poor.payment, 'UNPAID'); assert.equal(mapItems(read()).length, 0); assert.equal(economy().transactions.length, 0)
      await vault('reset 1000000'); bot.command(`mapmanage claim ${poor.artwork}`)
      await until(() => receipts().find(receipt => receipt.artwork === poor.artwork)?.delivery === 'DELIVERED', 'funded claim succeeds')
      assert.equal(economy().transactions.length, 1); assert.equal(economy().transactions[0].amount, 42)
      passed('insufficient funds preserves an unpaid pending artwork, later claim charges exactly once')

      await command('setup'); await command('wall SOUTH 17 8'); const big = await generate('large.png')
      assert.equal(fs.readdirSync(imageFolder(big.path)).filter(name => /^\d+_\d+\.png$/.test(name)).length, 136)
      const bigPlaced = await place(136); const bigIds = bigPlaced.frames.map(frame => frame.item.mapId)
      await until(() => bigIds.every(id => bot.maps.get(id)?.received === 16384 && new Set(bot.maps.get(id).pixels).size > 8), 'all 136 maps render beyond the 128-image cache capacity', 90000)
      const hashes = Object.fromEntries(bigIds.map(id => [id, crypto.createHash('sha256').update(bot.maps.get(id).pixels).digest('hex')]))
      await recover(135, big.path); await place(136)
      assert.deepEqual(read().frames.map(frame => frame.item.mapId), bigIds)
      assert.equal(economy().transactions.at(-1).amount, 136 * 10.5)
      passed('136-tile wall places, fully renders over network, recovers from its last tile and reuses all map IDs', { tiles: 136, pixelHashes: hashes })

      for (const material of ['ITEM_FRAME', 'GLOW_ITEM_FRAME']) {
        await command('setup'); const image = await generate()
        if (material === 'GLOW_ITEM_FRAME') await command('wall SOUTH 2 2 glow')
        await place()
        const support = read().frames[3]
        const coordinates = `${Math.floor(support.x)} ${Math.floor(support.y)} ${Math.floor(support.z) - 1}`
        const breakSupport = async () => {
          const tick = read().tick
          bot.command(`setblock ${coordinates} air destroy`)
          await until(state => state.events.some(event => event.event === 'hanging-break' && event.tick > tick && event.cause === 'PHYSICS'), 'native physics hanging-break event', 15000)
        }
        await command('protect 2'); await breakSupport(); await sleep(500)
        assert.ok(read().frames.every(frame => frame.item?.path))
        bot.command(`setblock ${coordinates} stone`); await sleep(400); await command('protect off')
        passed('native support-block break respects neighbouring frame protection and keeps the complete wall', { material })
        await command('cancel-spawn on'); await breakSupport(); await sleep(500)
        assert.ok(read().frames.every(frame => frame.item?.path)); assert.ok(fs.existsSync(imageFolder(image.path)))
        bot.command(`setblock ${coordinates} stone`); await sleep(400); await command('cancel-spawn off')
        passed('cancelled recovery drop during native hanging-break rolls back every painted frame', { material })
        const startTick = read().tick; await breakSupport()
        await until(state => !state.frames[3].item && state.frames.filter((_, index) => index !== 3).every(frame => frame.item?.type === 'AIR') &&
          (state.drops.some(item => item.path === image.path) || mapItems(state).some(item => item.path === image.path)), 'support break recovers whole artwork and removes clicked frame')
        assert.ok(read().events.some(event => event.event === 'frame-drop' && event.tick > startTick && event.type === material))
        assert.ok(fs.existsSync(imageFolder(image.path)))
        passed('native support-block break recovers whole artwork and drops the correct empty-frame material', { material })
      }

    }
    for (const wrapper of ['shulker', 'bundle', 'bundle,shulker']) for (const mode of ['despawn', 'void', 'damage']) {
      const expectedCause = { despawn: 'DESPAWN', void: 'OUT_OF_WORLD', damage: 'DEATH' }[mode]
      if (results.some(result => result.wrapper === wrapper && result.cause === expectedCause)) continue
      await command('setup'); const item = await generate(); await command(`wrap ${wrapper}`)
      const old = new Set(read().drops.map(drop => drop.uuid)); await command(`drop-mode ${mode}`); bot.dropStack()
      const cause = { despawn: 'DESPAWN', void: 'OUT_OF_WORLD', damage: 'DEATH' }[mode]
      await until(state => state.events.some(event => event.event === 'remove' && event.path === item.path && event.cause === cause), 'real container removal')
      if (mode === 'damage') {
        await until(state => state.drops.some(drop => drop.containedPaths?.includes(item.path) && !old.has(drop.uuid)) || state.inventory.some(held => held.containedPaths?.includes(item.path)), 'vanilla releases surviving container contents')
        await sleep(500); assert.ok(fs.existsSync(imageFolder(item.path)))
      } else await until(() => !fs.existsSync(imageFolder(item.path)), 'nested artwork deleted after true container loss')
      passed(mode === 'damage' ? 'container destruction releases surviving contents and preserves artwork' : 'true container loss deletes nested artwork', { wrapper, cause, artwork: item.path })
    }

    await command('setup'); delayedRequested = false
    const beforeCancel = new Set(receipts().map(receipt => receipt.artwork)); const txBeforeCancel = economy().transactions.length
    bot.command(`tomap ${url('slow.png')}`); await until(() => delayedRequested, 'slow download begins')
    bot.command('mapmanage status'); bot.command('mapmanage cancel')
    await sleep(3200)
    assert.deepEqual(new Set(receipts().map(receipt => receipt.artwork)), beforeCancel)
    assert.equal(economy().transactions.length, txBeforeCancel); assert.equal(mapItems(read()).length, 0)
    passed('real player cancellation stops a slow download without image delivery, receipt or charge')

    delayedRequested = false
    const old = new Set(receipts().map(receipt => receipt.artwork)); const txBeforeOffline = economy().transactions.length
    bot.command(`tomap ${url('slow.png')}`); await until(() => delayedRequested, 'offline fixture download begins')
    bot.close()
    await until(() => receipts().some(receipt => !old.has(receipt.artwork)), 'offline completed PNG receipt', 20000)
    await sleep(600)
    const pending = receipts().find(receipt => !old.has(receipt.artwork))
    assert.equal(pending.delivery, 'PENDING'); assert.equal(pending.payment, 'UNPAID'); assert.equal(economy().transactions.length, txBeforeOffline)
    assert.ok(fs.existsSync(path.join(data, 'maps', pending.artwork, 'thumb.png')))
    passed('player disconnect during generation preserves PNG plus durable unpaid pending delivery', { artwork: pending.artwork })
    // The next boot disables new generation charges; this pending quote must remain payable.
    fs.writeFileSync(configFile, validConfig().replace('enable: true', 'enable: false'))
    fs.writeFileSync(reportFile, JSON.stringify({ status: 'passed', phase, pluginSha256, results, segments, pendingArtwork: pending.artwork, economy: economy(), messages }, null, 2))
  } catch (error) {
    fs.writeFileSync(reportFile, JSON.stringify({ status: 'failed', phase, pluginSha256, error: error.stack, results, segments, economy: economy(), state: read(), messages }, null, 2))
    throw error
  } finally { bot.close(); imageServer.close() }
}
main().catch(error => { console.error(error.stack); process.exitCode = 1 })
