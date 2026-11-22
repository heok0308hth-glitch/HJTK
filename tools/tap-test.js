const fs=require('fs');
global.window=globalThis; global.location={pathname:'/',href:'https://chat.deepseek.com/a/chat/s/abc'};
global.document={querySelector:()=>null,querySelectorAll:()=>[],addEventListener(){},documentElement:{innerHTML:''},readyState:'complete',body:{innerText:''}};
global.navigator={};
const got=[];let ended=null,opened=null;
window.HJTKTap={open:(i,u,c,s)=>opened=[i,u,c,s],chunk:(i,t)=>got.push(t),end:(i,w)=>ended=w,clip(){},href(){}};
const parts=['data: {"v":"Hel"}\n\n','data: {"v":"lo"}\n\n','data: [DONE]\n\n'];
window.fetch=async()=>new Response(new ReadableStream({start(c){for(const p of parts)c.enqueue(new TextEncoder().encode(p));c.close();}}),{status:200,headers:{'content-type':'text/event-stream'}});
global.XMLHttpRequest=function(){};XMLHttpRequest.prototype.open=function(){};XMLHttpRequest.prototype.send=function(){};
eval(fs.readFileSync(process.argv[2],'utf8'));
(async()=>{
 const r=await window.fetch('https://chat.deepseek.com/api/v0/chat/completion',{method:'POST'});
 const page=await r.text();
 await new Promise(s=>setTimeout(s,100));
 console.log('page ok:',page===parts.join(''),'| tap ok:',got.join('')===parts.join(''),'| end:',ended,'| status',r.status,'| open',!!opened);
 location.pathname='/sign_in'; console.log('state on /sign_in:',window.__hjtkState());
 location.pathname='/'; document.body.innerText='登录 DeepSeek'; console.log('state login text:',window.__hjtkState());
 document.querySelector=(q)=>q==='textarea'?{}:null; console.log('state with box:',window.__hjtkState());
})();
