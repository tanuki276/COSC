// Executes the ORIGINAL CO-DUG update/draw/collision functions in an isolated VM.
// Only the test harness exports closure functions; the source checkout stays untouched.
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const zlib=require('node:zlib');
const {spawn}=require('node:child_process'),readline=require('node:readline');
let Canvas;
try {Canvas=require('@napi-rs/canvas').Canvas;} catch {
 Canvas=require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/@oai/artifact-tool/node_modules/skia-canvas').Canvas;
}
const gameRoot=process.argv[2],build=process.argv[3];
const overlays=['intro','upgradeOverlay','gameoverOverlay','historyOverlay','garageOverlay','sharedOverlay','pauseOverlay','rankingOverlay','journalOverlay'];
function world(seed,w=360,h=780){
 let rng=seed; const rand=()=>{rng=(Math.imul(rng,1664525)+1013904223)>>>0;return rng/4294967296;};
 class Element{
  constructor(id=''){this.id=id;this.children=[];this.value=id==='pilotName'?'採掘者':'';this.textContent='';this.style={setProperty(){}};this.hidden=false;this.className='';this._classes=new Set(overlays.includes(id)&&id!=='intro'?['hidden']:[]);
   this.classList={contains:x=>this._classes.has(x),add:x=>this._classes.add(x),remove:x=>this._classes.delete(x),toggle:(x,v)=>{if(v===undefined)v=!this._classes.has(x);if(v)this._classes.add(x);else this._classes.delete(x);return v;}};}
  set innerHTML(v){this.children=[];this._html=v;} get innerHTML(){return this._html||'';}
  appendChild(e){this.children.push(e);return e;} append(...els){this.children.push(...els);}
  replaceChildren(...els){this.children=els;} addEventListener(){} setAttribute(){} focus(){} insertBefore(e){this.children.unshift(e);}
  remove(){this.classList.add("hidden");} querySelector(){return new Element();} querySelectorAll(){return [];} closest(){return null;}
 }
 const nodes=new Map();const get=id=>{if(!nodes.has(id))nodes.set(id,new Element(id));return nodes.get(id);};
 const canvas=new Canvas(w,h);nodes.set('game',Object.assign(canvas,{addEventListener(){},setAttribute(){},focus(){}}));
 const document={getElementById:get,createElement:()=>new Element(),body:new Element(),head:new Element(),addEventListener(){},activeElement:new Element(),
 querySelector(q){if(q.includes('.overlay'))return overlays.map(get).find(e=>!e.classList.contains('hidden'))||null;if(q==='.hudButtons')return get('hudButtons');return null;},
 querySelectorAll(q){return q==='.overlay'?overlays.map(get):[];}};
 const storage=new Map(); const math=Object.create(Math);math.random=rand;
 const sandbox={console,Math:math,document,innerWidth:w,innerHeight:h,devicePixelRatio:1,
 localStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,String(v))},
 location:{origin:'null',search:'',hash:''},navigator:{vibrate(){}},matchMedia:()=>({matches:false}),
 addEventListener(){},requestAnimationFrame(){},setTimeout(){return 0;},clearTimeout(){},
 MutationObserver:class{observe(){}},performance:{now:()=>0},URL,URLSearchParams,Response,AbortSignal,Date,crypto:require('node:crypto').webcrypto};
 vm.createContext(sandbox);
 for(const file of ['offline-adapter.js','journal.js','balance.js','expedition.js'])vm.runInContext(fs.readFileSync(path.join(gameRoot,'app/src/main/assets',file),'utf8'),sandbox);
 let source=fs.readdirSync(path.join(gameRoot,'source-parts')).filter(f=>/game\.\d+\.js\.part/.test(f)).sort().map(f=>fs.readFileSync(path.join(gameRoot,'source-parts',f),'utf8')).join('');
 const exports=`globalThis.testGame={start,update,moveLane,activateDrill,drawRows,drawDrill,field,
 state:()=>({depth,hp,maxHp,gameover,paused,currentLane,drill,drillHeat,drillCooldown,tipReach,heatGain,heatCoolRate,drillCooldownMax,runSeed,rocks:Object.keys(journal.counts).filter(k=>TYPES[k]?.kind==='rock').reduce((n,k)=>n+(journal.counts[k]||0),0)}),
 render,
 upgrade:()=>{if(upgradeOverlay.classList.contains('hidden'))return false;const scores=t=>t.includes('冷却フィン')?100:t.includes('高速クラッチ')?95:t.includes('現地修理')?90:t.includes('増設フレーム')?80:t.includes('ロングビット')?70:t.includes('補給パック')?60:t.includes('高トルクモーター')?50:t.includes('鉱石粉砕ヘッド')?40:30;
 const buttons=upgradeChoices.children;buttons.sort((a,b)=>scores(b.innerHTML)-scores(a.innerHTML));buttons[0].onclick();return true;},
 fixture:(id,reach=0,d=0,centerY=328)=>{tipReach=reach;depth=d;rows.length=0;rows.push({depth:depth+(centerY-playerY())/3.25,cells:[null,id,null],resolved:[false,false,false]});render();},threats:()=>rows.filter(r=>r.cells[currentLane]&&!r.resolved[currentLane]&&rowY(r)<playerY()+170&&rowY(r)>playerY()-30).map(r=>({y:rowY(r),id:r.cells[currentLane],lane:currentLane})),ready:()=>runPromise};`;
 source=source.replace(/\}\)\(\);\s*$/,exports+'})();');
 vm.runInContext(source,sandbox,{filename:'original-game.js'});
 canvas.width=180;canvas.height=390;canvas.getContext("2d").scale(.5,.5);
 return {g:sandbox.testGame,canvas,sandbox};
}
async function main(){
 if(!build){const {g}=world(1);g.start();await g.ready();console.log(g.state());return;}
 const proc=spawn('java',['-cp',build,'com.tanuki276.cosc.ReplayBridge']);proc.stderr.pipe(process.stderr);
 const lines=readline.createInterface({input:proc.stdout});const iterator=lines[Symbol.asyncIterator]();
 async function ask(line){proc.stdin.write(line+'\n');return (await iterator.next()).value;}
 const results=[];fs.mkdirSync('replay-output',{recursive:true});
 if(process.argv[4]==='fixtures'){
  const {g,canvas}=world(1);g.start();await g.ready();
  for(const id of ['rock','shale','granite','basalt','crystalRock','magmaRock','voidRock','errorRock','iron','copper','silver','gold','amethyst','infernite','voidCrystal','errorShard','point','repair','battery','shield','cache','prototype']){
   await ask('RESET');
   g.fixture(id,0,0,542);
   const primeRgba=canvas.getContext('2d').getImageData(0,0,180,390).data;
   const primeBgra=Buffer.from(primeRgba);for(let i=0;i<primeBgra.length;i+=4){primeBgra[i]=primeRgba[i+2];primeBgra[i+2]=primeRgba[i];}
   for(let i=0;i<2;i++)await ask(`180 390 1 READY 21 0 0 ${10000+i*83} 0.5 ${zlib.deflateSync(primeBgra,{level:1}).toString('base64')} 0`);
   g.fixture(id,0,65,331);const rgba=canvas.getContext('2d').getImageData(0,0,180,390).data;
   const bgra=Buffer.from(rgba);for(let i=0;i<bgra.length;i+=4){bgra[i]=rgba[i+2];bgra[i+2]=rgba[i];}
   const answer=await ask(`180 390 1 READY 21 65 0 10167 0.5 ${zlib.deflateSync(bgra,{level:1}).toString('base64')} 0`);
   console.log(id,answer);
   const pickup=['point','repair','battery','shield','cache','prototype'].includes(id);
   if(answer.split(' ')[0] !== (pickup?'NONE':'DRILL'))throw Error('Renderer fixture failed: '+id+' '+answer);
   await fs.promises.writeFile(`replay-output/fixture-${id}.png`,await canvas.toBuffer('image/png'));
  }
  for(const reach of [0,8,16,24,32,34]){
   g.fixture(null,reach,10000);const rgba=canvas.getContext('2d').getImageData(0,0,180,390).data;
   const bgra=Buffer.from(rgba);for(let i=0;i<bgra.length;i+=4){bgra[i]=rgba[i+2];bgra[i+2]=rgba[i];}
   const answer=await ask(`180 390 1 READY 21 10000 ${reach} 10000 0.5 ${zlib.deflateSync(bgra,{level:1}).toString('base64')} 0.5`);
   console.log('empty reach='+reach,answer);
   if(answer.split(' ')[0]!=='NONE'||!answer.includes('y=Infinity'))throw Error('Own drill became obstacle at reach='+reach);
  }
  proc.stdin.end();return;
 }
 for(const seed of (process.env.REPLAY_SEEDS||'1,42,276').split(',').map(Number)){
  const {g,canvas}=world(seed);g.start();await g.ready();await ask('RESET');
  let trace=[],lastHp=3,queued=[],estimatedLane=1;
  let time=10000,lastDrill=0,lastMove=0,moves=0,drills=0,cancels=0,steps=0;
  const dt=1/12;
  while(g.state().depth<100100&&!g.state().gameover&&steps<60000){
   if(g.upgrade()){};
   let s=g.state();if(steps===0)g.render();
   const rgba=canvas.getContext('2d').getImageData(0,0,180,390).data;
   const bgra=Buffer.alloc(rgba.length);
   for(let i=0;i<rgba.length;i+=4){bgra[i]=rgba[i+2];bgra[i+1]=rgba[i+1];bgra[i+2]=rgba[i];bgra[i+3]=255;}
   const mode=s.drill>0?'ACTIVE':s.drillCooldown>0?'COOLING':'READY';
   const stage=s.drillHeat<.22?21:s.drillHeat<.48?47:s.drillHeat<.72?71:99;
   const heat=s.drill>0?Math.round(s.drillHeat*100):stage;
   const answer=await ask(`180 390 ${estimatedLane} ${mode} ${heat} ${Math.floor(s.depth)} ${s.tipReach} ${Math.round(time)} 0.5 ${zlib.deflateSync(bgra,{level:1}).toString('base64')} ${s.drillCooldown}`);
   if(!answer)throw Error('bridge exited');
   trace.push({depth:Math.round(s.depth),mode,heat,answer,threats:g.threats()});if(trace.length>12)trace.shift();
   if(s.hp<lastHp)console.log('DAMAGE '+JSON.stringify({seed,trace}));lastHp=s.hp;
   const action=answer.split(' ')[0];
   const observedLane=Number(answer.split(' ')[1]);
   if(observedLane>=0&&time-lastMove>280)estimatedLane=observedLane;
   if(time-lastDrill>=160){
    if(action==='DRILL'||action==='CANCEL'){
      queued.push({at:time+65,action});lastDrill=time;action==='DRILL'?drills++:cancels++;
    }
    if((action==='LEFT'||action==='RIGHT')&&time-lastMove>=150){
      queued.push({at:time+65,action});estimatedLane=Math.max(0,Math.min(2,estimatedLane+(action==='LEFT'?-1:1)));lastMove=time;moves++;
    }
   }
   if(steps%1000===0 && steps<2000) console.log(JSON.stringify({seed,steps,action,answer,...s}));
   // Advance in original 60 Hz collision/heat steps, not one oversized simulation tick.
   for(let j=0;j<5;j++){
     g.update(1/60);g.render();
     while(queued.length&&queued[0].at<=time+(j+1)*1000/60){
       const command=queued.shift().action;
       if(command==='DRILL'||command==='CANCEL')g.activateDrill();
       else g.moveLane(g.state().currentLane+(command==='LEFT'?-1:1));
     }
   }
   time+=dt*1000;steps++;
   if(steps%5000===0)console.log(JSON.stringify({seed,step:steps,...g.state()}));
  }
  const result={seed,...g.state(),drills,cancels,moves,frames:steps,simulatedSeconds:Math.round((time-10000)/1000)};results.push(result);
  console.log('RESULT '+JSON.stringify(result));
  await fs.promises.writeFile(`replay-output/seed-${seed}.png`,await canvas.toBuffer('image/png'));
 }
 proc.stdin.end();fs.writeFileSync('replay-output/results.json',JSON.stringify(results,null,2));
 if(results.some(r=>r.depth<100000||r.rocks<100))process.exitCode=1;
}
main().catch(e=>{console.error(e);process.exit(1);});
