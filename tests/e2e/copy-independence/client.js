const fs = require('fs');
const path = require('path');
const assert = require('assert');
const crypto = require('crypto');
const modules = process.env.COPY_PROBE_NODE_MODULES || '/workspace/DeerTitle/.local-test/client/node_modules';
const mc = require(path.join(modules, 'minecraft-protocol'));
const nbt = require(path.join(modules, 'prismarine-nbt'));
const root = process.argv[2];
const phase = process.argv[3] || 'before';
const server = path.join(root, 'server');
const stateFile = path.join(server, 'plugins/CopyProbe/state.json');
const reportFile = path.join(root, phase + '-report.json');
const checks = [];
const packets = [];
const messages = [];
const windows = new Map();
const playerSlots = new Map();
const maps = new Map();
let currentWindow = 0;
const client = mc.createClient({host: '127.0.0.1', port: 25695, username: 'CopyProbe', version: '26.1', auth: 'offline'});
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const state = () => {try {return JSON.parse(fs.readFileSync(stateFile, 'utf8'));} catch {return null;}};
function imageFile(ref) {assert.match(ref, /^maps\/[0-9a-f-]{36}\/(?:thumb|[0-9]+_[0-9]+)\.png$/); return path.join(server, 'plugins/ReColorfulMap', ref);}
function pngHashes(ref) {const folder=path.dirname(imageFile(ref));return Object.fromEntries(fs.readdirSync(folder).filter(x=>x.endsWith('.png')).sort().map(x=>[x,crypto.createHash('sha256').update(fs.readFileSync(path.join(folder,x))).digest('hex')]));}
async function until(fn, label, milliseconds=15000) {const start=Date.now();while(Date.now()-start<milliseconds){const value=fn();if(value)return value;if(state()?.action?.startsWith('FAILED'))throw new Error(state().action);await delay(50);}throw new Error('Timed out: '+label);}
async function command(action) {const old=state()?.commands||0;client.chat('/copyprobe '+action);await until(()=>state()?.commands>old && state()?.action===action,action);await delay(200);}
function click(slot,mode=0){const w=windows.get(currentWindow);assert(w,'missing native window state');client.write('window_click',{windowId:currentWindow,stateId:w.stateId,slot,mouseButton:0,mode,changedSlots:[],cursorItem:null});packets.push({packet:'window_click',window:currentWindow,slot,mode});}
client.on('position',p=>client.write('teleport_confirm',{teleportId:p.teleportId}));
client.on('system_chat',p=>{try{messages.push(nbt.simplify(p.content));}catch{}});
client.on('open_window',p=>{currentWindow=p.windowId;packets.push({packet:'open_window',id:p.windowId,type:p.inventoryType});});
client.on('window_items',p=>{windows.set(p.windowId,{stateId:p.stateId,items:p.items});if(p.windowId===0)for(let slot=36;slot<=44;slot++)playerSlots.set(slot-36,p.items[slot]);});
client.on('set_slot',p=>{let w=windows.get(p.windowId);if(w){w.stateId=p.stateId;w.items[p.slot]=p.item;}if(p.windowId===0 && p.slot>=36 && p.slot<=44)playerSlots.set(p.slot-36,p.item);});
client.on('set_player_inventory',p=>playerSlots.set(p.slotId,p.contents));
client.on('map',p=>{if(!p.columns)return;let m=maps.get(p.itemDamage);if(!m){m={pixels:Buffer.alloc(16384),seen:new Uint8Array(16384)};maps.set(p.itemDamage,m);}for(let y=0;y<p.rows;y++)for(let x=0;x<p.columns;x++){const at=(p.y+y)*128+p.x+x;m.pixels[at]=p.data[y*p.columns+x];m.seen[at]=1;}});
const joined = new Promise((resolve,reject)=>{client.once('playerJoin',resolve);client.once('error',reject);client.once('kick_disconnect',p=>reject(new Error(JSON.stringify(p))));});
async function pixels(slot,label){await command('hold '+slot);const item=state().items.find(x=>x.slot===slot);assert(item);const map=await until(()=>{const m=maps.get(item.mapId);return m&&m.seen.every(x=>x===1)&&m.pixels.some(x=>x!==0)&&m;},'nonblank complete map pixels '+label,45000);return {path:item.path,mapId:item.mapId,pixelHash:crypto.createHash('sha256').update(map.pixels).digest('hex')};}
(async()=>{
 await joined;await until(()=>state(),'probe snapshot');await delay(500);
 if(phase==='before'){
  assert.equal(state().items.length,1,'disconnect cancellation must preserve the saved original');
  checks.push({check:'disconnect_cancellation_preserves_original',item:state().items[0]});
  await command('craft-prepare');await until(()=>currentWindow&&windows.get(currentWindow),'workbench');const craftMaterials=state().topAmounts.slice(1);click(0);await delay(600);
  assert.equal(state().items.length,0);assert.deepEqual(state().topAmounts.slice(1),craftMaterials);checks.push({check:'native_workbench_copy_refused_without_consuming_materials'});
  await command('cart-prepare');await until(()=>state().top==='CARTOGRAPHY'&&windows.get(currentWindow),'cartography');const cartMaterials=state().topAmounts;click(2,1);await delay(600);
  assert.equal(state().items.length,0);assert.deepEqual(state().topAmounts,cartMaterials);checks.push({check:'native_cartography_copy_refused_without_consuming_materials'});
  await command('command-prepare');let folders=fs.readdirSync(path.join(server,'plugins/ReColorfulMap/maps')).sort();await command('fill');client.chat('/mapmanage copy');await delay(700);
  assert.equal(state().items.length,1);assert.equal(state().occupiedSlots,36);assert.deepEqual(fs.readdirSync(path.join(server,'plugins/ReColorfulMap/maps')).sort(),folders);checks.push({check:'full_inventory_refuses_copy_without_item_or_file_changes'});
  await command('command-prepare');folders=fs.readdirSync(path.join(server,'plugins/ReColorfulMap/maps')).sort();const heldOriginal=state().items[0];await command('pause-copy');client.chat('/mapmanage copy');await command('hold 8');await delay(2500);
  assert.equal(state().items.length,1);assert.equal(state().items[0].path,heldOriginal.path);assert.deepEqual(fs.readdirSync(path.join(server,'plugins/ReColorfulMap/maps')).sort(),folders);checks.push({check:'held_item_change_cancels_pending_copy',faultInjection:'2-second storage monitor delay'});
  await command('command-prepare');client.chat('/mapmanage copy');await until(()=>state().items.length===2,'independent commanded image copy');
  const copied=state().items;assert.notEqual(copied[0].path,copied[1].path);assert.notEqual(copied[0].mapId,copied[1].mapId);assert(copied.every(x=>x.amount===1));assert.deepEqual(pngHashes(copied[0].path),pngHashes(copied[1].path));checks.push({check:'command_copy_keeps_original_and_creates_independent_files',items:copied});
  const firstPixels=await pixels(0,'original');const secondPixels=await pixels(1,'copy');assert.equal(firstPixels.pixelHash,secondPixels.pixelHash);checks.push({check:'copy_pixels_equal',original:firstPixels,copy:secondPixels});
  const dead=copied[1].path;await command('destroy 1 despawn');await until(()=>state().removals.some(x=>x.path===dead&&x.cause==='DESPAWN'),'actual despawn');await until(()=>!fs.existsSync(path.dirname(imageFile(dead))),'destroyed independent copy directory removed');assert(fs.existsSync(imageFile(copied[0].path)));checks.push({check:'copy_despawn_preserves_original'});
  await command('hold 0');client.chat('/mapmanage copy');await until(()=>state().items.length===2,'second independent copy');const survivor=state().items.find(x=>x.slot===1);await command('destroy 0 death');await until(()=>state().removals.some(x=>x.path===copied[0].path&&x.cause==='DEATH'),'actual original fire death');await until(()=>!fs.existsSync(path.dirname(imageFile(copied[0].path))),'original directory removed');assert(fs.existsSync(imageFile(survivor.path)));const survivorPixels=await pixels(1,'surviving copy');assert.equal(survivorPixels.pixelHash,firstPixels.pixelHash);checks.push({check:'original_fire_death_preserves_copy_and_pixels'});
  await command('legacy-prepare');const legacy=state().items[0];const legacyHashes=pngHashes(legacy.path);await command('destroy 0 despawn');await until(()=>state().removals.some(x=>x.path===legacy.path&&x.cause==='DESPAWN'),'actual legacy despawn');await delay(700);assert.deepEqual(pngHashes(legacy.path),legacyHashes);checks.push({check:'legacy_without_independence_marker_retains_files_after_despawn'});
  await command('tile-prepare');const tiles=state().items;const selected=tiles.find(x=>x.slot===2);const oldTilePixels=await pixels(2,'original tile');client.chat('/mapmanage copy');await until(()=>state().items.length===5,'single tile copy');const tileCopy=state().items.find(x=>x.slot===4);assert.notEqual(tileCopy.path.split('/')[1],selected.path.split('/')[1]);assert(tileCopy.path.endsWith('/0_0.png'));assert.notEqual(tileCopy.mapId,selected.mapId);const tileHash=crypto.createHash('sha256').update(fs.readFileSync(imageFile(selected.path))).digest('hex');assert(Object.values(pngHashes(tileCopy.path)).every(hash=>hash===tileHash));checks.push({check:'tile_copy_is_independent_1x1_artwork',source:selected,copy:tileCopy});
  await command('destroy 2 void');await until(()=>state().removals.some(x=>x.path===selected.path&&x.cause==='OUT_OF_WORLD'),'actual original tile void');await until(()=>!fs.existsSync(imageFile(selected.path)),'destroyed original tile removed');for(const tile of tiles.filter(x=>x.slot!==2))assert(fs.existsSync(imageFile(tile.path)));assert(fs.existsSync(imageFile(tileCopy.path)));const tileCopyPixels=await pixels(4,'tile copy after source destruction');assert.equal(tileCopyPixels.pixelHash,oldTilePixels.pixelHash);checks.push({check:'tile_void_preserves_other_tiles_and_independent_copy'});
  await command('destroy 4 despawn');await until(()=>state().removals.some(x=>x.path===tileCopy.path&&x.cause==='DESPAWN'),'copy tile despawn');await until(()=>!fs.existsSync(path.dirname(imageFile(tileCopy.path))),'last tile cleans its independent directory');for(const tile of tiles.filter(x=>x.slot!==2))assert(fs.existsSync(imageFile(tile.path)));checks.push({check:'last_tile_despawn_cleans_only_its_copy_directory'});
  const checkpoint=await pixels(1,'remaining tile before restart');fs.writeFileSync(path.join(root,'restart-checkpoint.json'),JSON.stringify(checkpoint,null,2));checks.push({check:'restart_checkpoint',...checkpoint});
 }else{
  await command('resume');const checkpoint=JSON.parse(fs.readFileSync(path.join(root,'restart-checkpoint.json')));const current=await pixels(1,'remaining tile after restart');assert.deepEqual(current,checkpoint);checks.push({check:'restart_restores_path_map_id_and_pixels',...current});
 }
 fs.writeFileSync(reportFile,JSON.stringify({ok:true,checks,packets,messages,state:state()},null,2));console.log(JSON.stringify({ok:true,checks:checks.map(x=>x.check)}));client.end();await delay(250);process.exit(0);
})().catch(async error=>{fs.writeFileSync(reportFile,JSON.stringify({ok:false,error:error.stack,checks,packets,messages,state:state()},null,2));console.error(error.stack);client.end();await delay(250);process.exit(1);});
