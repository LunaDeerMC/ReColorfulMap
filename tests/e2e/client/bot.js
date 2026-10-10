'use strict'

const { EventEmitter } = require('node:events')
const mc = require('minecraft-protocol')
const readline = require('node:readline')

// Real Minecraft protocol client. No Bukkit APIs or fake network events.
// minecraft-protocol handles login, compression, configuration and keep-alive.
class RcmBot extends EventEmitter {
  constructor (options = {}) {
    super()
    this.position = { x: 0, y: 0, z: 0, yaw: 0, pitch: 0 }
    this.inventory = []
    this.maps = new Map()
    this.packets = new Map()
    this.entityId = null
    this.sequence = 0
    this.closed = false
    this.spawned = false
    this.log = options.log || (() => {})
    this.client = mc.createClient({
      host: '127.0.0.1', port: 25576, username: 'RCMTester',
      auth: 'offline', version: '26.2', keepAlive: true,
      disableChatSigning: true, ...options,
      clientSettings: { viewDistance: 2, ...(options.clientSettings || {}) }
    })
    const c = this.client
    this.ready = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('No initial teleport received within 30s')), 30000)
      this.once('spawn', () => { clearTimeout(timer); resolve(this) })
      c.once('error', error => { clearTimeout(timer); reject(error) })
      c.once('end', reason => { clearTimeout(timer); reject(new Error(`Connection ended before spawn: ${reason}`)) })
    })
    c.on('error', error => { this.log('error', error.stack || error.message); this.emit('clientError', error) })
    c.on('end', reason => { this.closed = true; clearInterval(this.tick); this.log('end', reason); this.emit('end', reason) })
    c.on('disconnect', packet => this.log('disconnect', packet))
    c.on('kick_disconnect', packet => this.log('kick', packet))
    c.on('packet', (packet, meta) => {
      this.packets.set(meta.name, (this.packets.get(meta.name) || 0) + 1)
      this.emit('packet', packet, meta)
    })
    c.on('login', packet => { this.entityId = packet.entityId; this.log('login', { entityId: this.entityId }); this.emit('login', packet) })
    c.on('position', packet => {
      for (const field of ['x', 'y', 'z', 'yaw', 'pitch']) {
        this.position[field] = (packet.flags[field] ? this.position[field] : 0) + packet[field]
      }
      c.write('teleport_confirm', { teleportId: packet.teleportId })
      this.sendPosition()
      c.write('player_loaded', {})
      this.log('teleport', { ...this.position, teleportId: packet.teleportId })
      this.emit('teleport', { ...this.position })
      if (!this.spawned) {
        this.spawned = true
        this.emit('spawn')
      }
    })
    c.on('player_rotation', packet => {
      this.position.yaw = (packet.relativeYaw ? this.position.yaw : 0) + packet.yaw
      this.position.pitch = (packet.relativePitch ? this.position.pitch : 0) + packet.pitch
      if (this.spawned) this.sendPosition()
    })
    c.on('ping', packet => c.write('pong', { id: packet.id }))
    c.on('chunk_batch_finished', () => c.write('chunk_batch_received', { chunksPerTick: 8 }))
    c.on('update_health', packet => {
      if (packet.health > 0) c.write('player_loaded', {})
      this.emit('health', packet)
    })
    c.on('window_items', packet => {
      if (packet.windowId === 0) this.inventory = packet.items
      this.emit('inventory', packet)
    })
    c.on('set_slot', packet => {
      if (packet.windowId === 0 || packet.windowId === -2) this.inventory[packet.slot] = packet.item
      this.emit('inventory', packet)
    })
    c.on('set_player_inventory', packet => {
      this.inventory[packet.slotId] = packet.contents
      this.emit('inventory', packet)
    })
    c.on('map', packet => {
      const old = this.maps.get(packet.itemDamage)
      const pixels = old?.pixels || Buffer.alloc(128 * 128)
      const seen = old?.seen || Buffer.alloc(128 * 128)
      let received = old?.received || 0
      if (packet.data?.length && packet.columns) {
        for (let row = 0; row < packet.rows; row++) for (let column = 0; column < packet.columns; column++) {
          const offset = (packet.y + row) * 128 + packet.x + column
          pixels[offset] = packet.data[row * packet.columns + column]
          if (!seen[offset]) { seen[offset] = 1; received++ }
        }
      }
      this.maps.set(packet.itemDamage, {
        packet, pixels, seen, received, updates: (old?.updates || 0) + 1,
        lastContentAt: packet.data?.length ? Date.now() : old?.lastContentAt || 0
      })
      this.log('map', { id: packet.itemDamage, columns: packet.columns, bytes: packet.data?.length || 0 })
      this.emit('map', packet)
    })
    c.on('system_chat', packet => { this.log('chat', packet); this.emit('chat', packet) })
    this.tick = setInterval(() => {
      if (!this.spawned || this.closed || c.state !== 'play') return
      c.write('flying', { flags: { onGround: false, hasHorizontalCollision: false } })
      c.write('tick_end', {})
    }, 50)
  }

  sendPosition () {
    this.client.write('position_look', {
      ...this.position, flags: { onGround: false, hasHorizontalCollision: false }
    })
  }

  command (command) {
    this.client.write('chat_command', { command: command.replace(/^\//, '') })
  }

  chat (message) {
    if (message.startsWith('/')) this.command(message)
    else this.client.chat(message)
  }

  interact (entityId, location = { x: 0, y: 0, z: 0 }, sneaking = false) {
    this.client.write('use_entity', { target: entityId, hand: 'main_hand', location, sneaking })
  }

  attack (entityId) {
    this.client.write('attack', { entityId })
  }

  dropStack () {
    this.client.write('block_dig', {
      status: 3, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: this.sequence++
    })
  }

  dropOne () {
    this.client.write('block_dig', {
      status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: this.sequence++
    })
  }

  hotbar (slotId) { this.client.write('held_item_slot', { slotId }) }
  respawn () { this.client.write('client_command', { actionId: 'perform_respawn' }) }
  close () { clearInterval(this.tick); this.client.end('E2E completed') }
}

async function connect (options) {
  const bot = new RcmBot(options)
  await bot.ready
  return bot
}

module.exports = { RcmBot, connect }

if (require.main === module) {
  const stringify = value => JSON.stringify(value, (key, value) => typeof value === 'bigint' ? String(value) : value)
  connect({ log: (event, detail) => console.log(stringify({ event, detail })), port: Number(process.env.PORT || 25576) })
    .then(bot => {
      console.log(stringify({ event: 'ready', position: bot.position }))
      const lines = readline.createInterface({ input: process.stdin })
      lines.on('line', line => {
        try {
          const action = JSON.parse(line)
          if (action.command) bot.command(action.command)
          if (action.chat) bot.chat(action.chat)
          if (action.interact != null) bot.interact(action.interact, action.location, action.sneaking)
          if (action.attack != null) bot.attack(action.attack)
          if (action.dropStack) bot.dropStack()
          if (action.dropOne) bot.dropOne()
          if (action.hotbar != null) bot.hotbar(action.hotbar)
          if (action.state) console.log(stringify({ event: 'state', position: bot.position, maps: [...bot.maps.keys()], inventory: bot.inventory, packets: Object.fromEntries(bot.packets) }))
          if (action.close) { bot.close(); lines.close() }
        } catch (error) { console.error(error.stack) }
      })
      bot.once('end', () => { lines.close(); process.exitCode = 0 })
    })
    .catch(error => { console.error(error.stack); process.exit(1) })
}
