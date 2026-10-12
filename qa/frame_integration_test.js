'use strict';
const fs = require('fs');
const vm = require('vm');
const base = __dirname + '/generated_js/';
const script = name => fs.readFileSync(base + name + '.js', 'utf8');
const assert = (condition, message) => { if (!condition) throw new Error(message); };

class CSS {
  constructor() { this.props = {}; }
  get cssText() { return Object.entries(this.props).map(([k,v])=>k+':'+v).join(';'); }
  set cssText(value) {
    this.props = {};
    for (const item of String(value).split(';')) { const i = item.indexOf(':'); if(i>0)this.props[item.slice(0,i)]=item.slice(i+1); }
  }
  setProperty(name, value) { this.props[name]=String(value); }
  get position() { return this.props.position||''; }
}
class Node {
  constructor(tag, w=900, h=500) {
    this.tagName=tag.toUpperCase(); this.children=[]; this.parentElement=null;
    this.style=new CSS(); this._w=w;this._h=h;this.attributes={};this.controls=true;
    this.isConnected=true;
  }
  appendChild(child) { child.parentElement=this; this.children.push(child); return child; }
  contains(child) { for(let n=child;n;n=n.parentElement)if(n===this)return true;return false; }
  getBoundingClientRect(){return this.style.position==='fixed'?{width:1280,height:720}:{width:this._w,height:this._h};}
  getAttribute(key){return this.attributes[key]||null;}
  setAttribute(key,val){this.attributes[key]=String(val)}
  hasAttribute(key){return Object.prototype.hasOwnProperty.call(this.attributes,key)}
  removeAttribute(key){delete this.attributes[key]}
  closest(){return null}
  addEventListener(){}
}
function makeContext(name, withVideo) {
  const html=new Node('html',1280,720), body=html.appendChild(new Node('body',1280,720));
  const holder=body.appendChild(new Node('main'));
  const banner=body.appendChild(new Node('aside'));
  let video = null;
  if(withVideo) {
    video=holder.appendChild(new Node('video'));
    video.currentTime=0;video.duration=12;video.paused=true;video.ended=false;
    video.readyState=2;video.networkState=2;video.videoWidth=1920;video.videoHeight=1080;
    video.currentSrc='blob:https://video.example/mse';video.src=video.currentSrc;
    video.textTracks=[];video.error=null;video.buffered={length:0};video.frames=0;
    video.muted=true;video.defaultMuted=true;video.volume=0;
    video.getVideoPlaybackQuality=()=>({totalVideoFrames:video.frames});
    video.play=()=>{video.paused=false;return Promise.resolve()};
    video.pause=()=>{video.paused=true};
  }
  const listeners = {};
  const doc={body,documentElement:html,baseURI:'https://'+name+'/page',readyState:'complete',
    addEventListener(){},
    querySelectorAll(sel){if(sel==='video')return video?[video]:[];if(sel==='iframe')return holder.children.filter(e=>e.tagName==='IFRAME');return []}};
  const window={innerWidth:1280,innerHeight:720,addEventListener(type,fn){(listeners[type]??=[]).push(fn)},
    getComputedStyle(el){return {visibility:el.style.props.visibility||'visible',display:el.style.props.display||'block',opacity:1}}};
  const intervals=[];
  const env={window,document:doc,setInterval(cb){intervals.push(cb);return intervals.length},
    MutationObserver:class{constructor(cb){} observe(){} disconnect(){}},
    Date,Number,String,Object,Array,Boolean,JSON,Math,URL,Promise,WeakSet,isFinite,Error};
  vm.createContext(env);
  return {name,window,document:doc,holder,banner,video,events:listeners,intervals,env};
}
function run(site,source){return vm.runInContext(source,site.env)}
function dispatch(site,data,source){for(const fn of site.events.message||[])fn({data,source,origin:'https://other.example'})}

async function testEmbeddedPlayer() {
  const root=makeContext('watch.example',false);
  const player=makeContext('player.example',true);
  const iframe=root.holder.appendChild(new Node('iframe'));
  iframe.attributes.src='https://player.example/embed/video';
  const parentProxy={postMessage(data){dispatch(root,data,childProxy)}};
  const childProxy={postMessage(data){dispatch(player,data,parentProxy)}};
  iframe.contentWindow=childProxy;
  root.window.parent=root.window;
  player.window.parent=parentProxy;
  run(root,script('frame_agent'));
  run(player,script('frame_agent'));

  // Document-start agent may report immediately if the video already exists.
  player.intervals[0]();
  assert(root.window.__glasslinkFrameAgent.best().node===iframe,'Parent detects real cross-origin child video');
  const early=JSON.parse(run(root,script('probe')));
  assert(early.kind==='frame-video'&&early.found&&early.current===0,'Embedded media probing failed');
  assert(early.decodedFrames===0&&!early.isolated,'Media timeline alone is not playback proof');
  assert(early.muted,'Embedded player initial mute state was not relayed');
  assert(root.banner.style.props.visibility===undefined,'Page must remain unchanged during initialization');
  assert(player.video.paused,'Video should be paused before play request');
  assert(run(root,script('start'))==='requested','Play request should travel into the iframe');
  assert(!player.video.paused,'Cross-origin iframe received play command');
  assert(!player.video.muted&&player.video.volume===1,'Initial Play should restore embedded sound');
  player.intervals[0]();
  assert(!JSON.parse(run(root,script('probe'))).muted,'Sound state should reach the TV controls');
  run(root,script('sound'));
  assert(player.video.muted,'TV sound control should mute the embedded player');
  run(root,script('sound'));
  assert(!player.video.muted,'TV sound control should unmute the embedded player');
  player.video.muted=true;
  run(root,script('unmute'));
  assert(!player.video.muted,'Receiver should restore sound if the site mutes after playback starts');

  // Simulation of playback beginning, independently of an ever-growing duration.
  player.video.currentTime=1.25;player.video.frames=14;
  player.intervals[0]();
  const playing=JSON.parse(run(root,script('probe')));
  assert(playing.current===1.25&&playing.decodedFrames===14,'iframe frame progress lost');
  assert(!playing.isolated,'Must not fullscreen before verification');
  const masked=JSON.parse(run(root,script('isolate')));
  assert(masked.ok&&masked.kind==='frame-video','Cannot isolate iframe');
  assert(root.banner.style.props.visibility==='hidden','Top-level page UI not hidden');
  assert(player.banner.style.props.visibility==='hidden','Iframe-internal page UI not hidden');
  assert(player.video.style.props['object-fit']==='contain','Video should preserve aspect ratio');
  assert(!player.video.controls,'Touch controls should be replaced by native TV controls');
  player.video.controls=true;
  player.banner.style.setProperty('visibility','visible');
  player.intervals[0]();
  assert(!player.video.controls && player.banner.style.props.visibility==='hidden',
    'Website changes must not restore embedded controls or overlays');
  player.video.currentTime=2.8;player.video.frames=39;
  player.intervals[0]();
  const ready=JSON.parse(run(root,script('probe')));
  assert(ready.isolated&&ready.current===2.8,'Need child-verified fullscreen state');
  run(root,script('command'));
  assert(player.video.paused,'Play/Pause remote control must reach the inner video');
  run(root,script('restore'));
  assert(!player.video.style.props.position && !iframe.style.props.position,'CSS rollback must include both frames');
  assert(root.banner.style.props.visibility===undefined&&player.banner.style.props.visibility===undefined,
     'No permanent webpage CSS modifications');
  console.log('PASS cross-origin player: find, play, verify, fullscreen both frames, remote pause, restore');
}
async function testDirectPlayerSound(){
  const site=makeContext('watch.example',true);
  site.window.parent=site.window;
  assert(run(site,script('start'))==='requested','Direct player should start');
  assert(!site.video.muted&&site.video.volume===1,'Direct player should start with sound');
  run(site,script('sound'));
  assert(site.video.muted,'TV control should mute a direct player');
  run(site,script('sound'));
  assert(!site.video.muted,'TV control should unmute a direct player');
  assert(JSON.parse(run(site,script('isolate'))).ok,'Direct video isolation failed');
  site.video.controls=true;
  site.banner.style.setProperty('visibility','visible');
  assert(run(site,script('enforce'))==='ok','Direct video enforcement failed');
  assert(!site.video.controls && site.banner.style.props.visibility==='hidden',
    'Direct website controls reappeared after isolation');
  console.log('PASS direct video: start with sound, mute and unmute from TV controls');
}
async function testUnrelatedIframe() {
  const root=makeContext('watch.example',false);
  const frame=makeContext('ads.example',false);
  const iframe=root.holder.appendChild(new Node('iframe'));
  const parentProxy={postMessage(data){dispatch(root,data,childProxy)}};
  const childProxy={postMessage(data){dispatch(frame,data,parentProxy)}};
  iframe.contentWindow=childProxy;root.window.parent=root.window;frame.window.parent=parentProxy;
  run(root,script('frame_agent'));run(frame,script('frame_agent'));
  frame.intervals[0]();
  assert(root.window.__glasslinkFrameAgent.best()===null,'Unrelated iframe should not be treated as video');
  assert(JSON.parse(run(root,script('probe'))).found===false,'Do not display a random webpage');
  console.log('PASS unknown iframe fails closed');
}
async function testNestedFrames(){
  const top=makeContext('watch.example',false);
  const mid=makeContext('embed.example',false);
  const bottom=makeContext('cdn-player.example',true);
  const topFrame=top.holder.appendChild(new Node('iframe'));
  const midFrame=mid.holder.appendChild(new Node('iframe'));
  topFrame.attributes.src='https://embed.example/player';
  midFrame.attributes.src='https://cdn-player.example/channel';
  const topParent={postMessage(data){dispatch(top,data,midProxy)}};
  const midProxy={postMessage(data){dispatch(mid,data,topParent)}};
  const midParent={postMessage(data){dispatch(mid,data,bottomProxy)}};
  const bottomProxy={postMessage(data){dispatch(bottom,data,midParent)}};
  top.window.parent=top.window;
  mid.window.parent=topParent;
  bottom.window.parent=midParent;
  topFrame.contentWindow=midProxy;
  midFrame.contentWindow=bottomProxy;
  for(const site of [top,mid,bottom])run(site,script('frame_agent'));
  bottom.intervals[0]();
  const initial=JSON.parse(run(top,script('probe')));
  assert(initial.found&&initial.kind==='frame-video','Nested frames should relay playable video presence');
  assert(run(top,script('start'))==='requested'&&!bottom.video.paused,'Deeply embedded Play action lost');
  bottom.video.frames=16;bottom.video.currentTime=1.5;bottom.intervals[0]();
  assert(JSON.parse(run(top,script('probe'))).decodedFrames===16,'Nested playback data lost');
  assert(JSON.parse(run(top,script('isolate'))).ok,'Nested frame isolation failed');
  bottom.video.frames=30;bottom.video.currentTime=2.9;bottom.intervals[0]();
  const final=JSON.parse(run(top,script('probe')));
  assert(final.isolated,'Every nested frame must confirm its own isolation');
  assert(top.banner.style.props.visibility==='hidden'&&mid.banner.style.props.visibility==='hidden'&&bottom.banner.style.props.visibility==='hidden',
    'Nested webpage chrome leaked');
  bottom.video.controls=true;
  bottom.banner.style.setProperty('visibility','visible');
  bottom.intervals[0]();
  assert(!bottom.video.controls && bottom.banner.style.props.visibility==='hidden',
    'Nested player controls reappeared after isolation');
  run(top,script('restore'));
  assert(topFrame.style.position===''&&midFrame.style.position===''&&bottom.video.style.position==='',
    'Nested rollback incomplete');
  console.log('PASS two nested iframes: state relay, remote play, video-only isolation and restoration');
}
async function testStandaloneFallbackAndYouTubePriority(){
  const root=makeContext('watch.example',false);
  const iframe=root.holder.appendChild(new Node('iframe'));
  iframe.attributes.src='https://stream-host.example/embed/channel-abc';
  iframe.attributes.allow='autoplay; fullscreen';
  root.window.parent=root.window;
  run(root,script('frame_agent'));
  const candidate=JSON.parse(run(root,script('standalone')));
  assert(candidate.found&&candidate.url==='https://stream-host.example/embed/channel-abc',
    'Only large, plausibly embedded players may be opened standalone');
  iframe.attributes.src='https://adnet.example/ads/advertisement';
  assert(JSON.parse(run(root,script('standalone'))).found===false,
    'Do not follow obvious advertising iframes');
  iframe.attributes.src='https://www.youtube.com/embed/M7lc1UVf-VE';
  assert(JSON.parse(run(root,script('probe'))).kind==='youtube-frame',
    'Preserve the official YouTube embed route');
  console.log('PASS conservative standalone embed fallback, ad exclusion, YouTube priority');
}
async function testUnisolatedKnownEmbed(){
  const root=makeContext('watch.example',false);
  const iframe=root.holder.appendChild(new Node('iframe'));
  iframe.attributes.src='https://player.vimeo.com/video/123456';
  root.window.parent=root.window;
  run(root,script('frame_agent'));
  assert(JSON.parse(run(root,script('probe'))).found===false,
    'An iframe without verified inner video must not expose its own player UI');
  console.log('PASS unisolated third-party player stays covered');
}
(async()=>{await testDirectPlayerSound();await testEmbeddedPlayer();await testNestedFrames();await testUnrelatedIframe();
 await testStandaloneFallbackAndYouTubePriority();await testUnisolatedKnownEmbed()})().catch(err=>{console.error(err.stack);process.exitCode=1});
