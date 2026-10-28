const fs=require('fs'),path=require('path'),assert=require('assert');
const modules=process.env.COPY_PROBE_NODE_MODULES||'/workspace/DeerTitle/.local-test/client/node_modules';
const mc=require(path.join(modules,'minecraft-protocol'));
const root=process.argv[2],stateFile=path.join(root,'server/plugins/CopyProbe/state.json');
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const state=()=>{try{return JSON.parse(fs.readFileSync(stateFile));}catch{return null;}};
const client=mc.createClient({host:'127.0.0.1',port:25695,username:'CopyProbe',version:'26.1',auth:'offline'});
client.on('position',p=>client.write('teleport_confirm',{teleportId:p.teleportId}));
async function until(fn,label){for(let i=0;i<200;i++){if(fn())return;await delay(50);}throw Error('Timed out: '+label);}
async function command(action){const previous=state()?.commands||0;client.chat('/copyprobe '+action);await until(()=>state()?.commands>previous&&state()?.action===action,action);await delay(150);}
(async()=>{
 await new Promise((resolve,reject)=>{client.once('playerJoin',resolve);client.once('error',reject);});await until(()=>state(),'fresh probe snapshot');await delay(300);
 await command('command-prepare');const original=state().items[0];assert(original);
 const directories=fs.readdirSync(path.join(root,'server/plugins/ReColorfulMap/maps')).sort();
 await command('pause-copy');client.chat('/mapmanage copy');await delay(200);client.end();await delay(300);
 fs.writeFileSync(path.join(root,'disconnect-report.json'),JSON.stringify({original,directories,faultInjection:'2-second storage monitor delay then real client disconnect'},null,2));
 console.log('CLIENT_DISCONNECTED_DURING_COPY');process.exit(0);
})().catch(async e=>{console.error(e.stack);client.end();await delay(100);process.exit(1);});
