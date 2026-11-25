const v=require('gltf-validator');const fs=require('fs');const path=require('path');
(async()=>{for(const f of process.argv.slice(2)){const r=await v.validateBytes(new Uint8Array(fs.readFileSync(f)));
console.log(path.basename(f).padEnd(22),'errors',r.issues.numErrors,'warnings',r.issues.numWarnings,'infos',r.issues.numInfos, r.issues.messages.filter(m=>m.severity<2).slice(0,4).map(m=>m.code+':'+m.pointer).join(' '));}})();
