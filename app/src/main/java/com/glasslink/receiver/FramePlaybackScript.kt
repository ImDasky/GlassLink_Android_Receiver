package com.glasslink.receiver

/**
 * An unprivileged document-start playback agent. AndroidX WebKit injects this
 * script into each frame (when supported), so cross-origin iframe videos can
 * be detected and controlled IN THEIR OWN frame. Only sanitized playback
 * state is passed to ancestor frames using ordinary postMessage. No cookies,
 * manifest URLs, credentials, native objects or web APIs are exposed.
 *
 * Parent windows verify event.source is an actual iframe window before trusting
 * a report. Nothing is sent to Android from the child frame directly.
 */
internal object FramePlaybackScript {
    const val marker = "glasslink-iframe-video-v1"

    val install: String = """(function(){
      'use strict';
      if(window.__glasslinkFrameAgent)return;
      var MARK='glasslink-iframe-video-v1';
      var childReports=[];
      var activeIsolation=null;
      var state={waiting:false,playError:'',hasPlayed:false};
      var now=function(){return Date.now();};
      function styleVisible(e){
        if(!e||!e.getBoundingClientRect)return false;
        var r=e.getBoundingClientRect();
        if(r.width<120||r.height<68)return false;
        try{
          var s=window.getComputedStyle(e);
          return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)!==0;
        }catch(e){return false;}
      }
      function video(){
        var items=document.querySelectorAll('video'),best=null,score=-1;
        for(var i=0;i<items.length;i++){
          var v=items[i];if(!styleVisible(v))continue;
          var r=v.getBoundingClientRect();
          var n=r.width*r.height+(!v.paused&&v.readyState>=2?1e8:0);
          if(n>score){score=n;best=v;}
        }
        return best;
      }
      function realFrame(e){
        if(!e)return null;
        var all=document.querySelectorAll('iframe');
        for(var i=0;i<all.length;i++) {
          if(all[i].contentWindow===e)return all[i];
        }
        return null;
      }
      function chooseChild(){
        var best=null,score=-1,t=now();
        for(var i=childReports.length-1;i>=0;i--){
          var p=childReports[i];
          if(t-p.at>3500||!document.documentElement.contains(p.node)){childReports.splice(i,1);continue;}
          if(!styleVisible(p.node))continue;
          var r=p.node.getBoundingClientRect();
          var s=r.width*r.height+(p.state.playing?1e8:0);
          if(s>score){score=s;best=p;}
        }
        return best;
      }
      function numeric(value,limit){
        var n=Number(value);return Number.isFinite(n)&&n>=0?Math.min(n,limit):0;
      }
      function clean(data){
        if(!data||typeof data!=='object'||data.kind!=='video')return null;
        // Never accept or forward a URL, token, cookie, or arbitrary object.
        return {kind:'video',found:true,
          playing:data.playing===true,paused:data.paused===true,
          waiting:data.waiting===true,ready:data.ready===true,
          playRejected:data.playRejected===true,playError:String(data.playError||'').slice(0,40),
          current:numeric(data.current,86400),duration:numeric(data.duration,604800),
          decodedFrames:Number(data.decodedFrames)>=0?numeric(data.decodedFrames,1e9):-1,
          readyState:numeric(data.readyState,4),mediaError:numeric(data.mediaError,4),
          videoWidth:numeric(data.videoWidth,16384),videoHeight:numeric(data.videoHeight,16384),
          bufferedAhead:numeric(data.bufferedAhead,3600),
          isolated:data.isolated===true,captions:data.captions===true,
          muted:data.muted===true,
          ended:data.ended===true};
      }
      function sample(v){
        var decoded=-1;
        try{
          if(v.getVideoPlaybackQuality) decoded=Number(v.getVideoPlaybackQuality().totalVideoFrames)||0;
          else if(typeof v.webkitDecodedFrameCount==='number')decoded=v.webkitDecodedFrameCount;
          if(typeof v.__glasslinkPresented==='number')decoded=Math.max(decoded,v.__glasslinkPresented);
        }catch(e){}
        if(!v.__glasslinkWatch){
          v.__glasslinkWatch=true;
          if(typeof v.requestVideoFrameCallback==='function'){
            v.__glasslinkPresented=0;
            function watch(_,metadata){
              v.__glasslinkPresented=Math.max(v.__glasslinkPresented||0,
                Number(metadata&&metadata.presentedFrames)||0);
              if(document.documentElement&&document.documentElement.contains(v)){
                try{v.requestVideoFrameCallback(watch);}catch(e){}
              }
            }
            try{v.requestVideoFrameCallback(watch);}catch(e){}
          }
          v.addEventListener('waiting',function(){state.waiting=true;});
          v.addEventListener('stalled',function(){state.waiting=true;});
          v.addEventListener('playing',function(){state.waiting=false;state.playError='';});
        }
        var t=Number(v.currentTime)||0,b=0;
        try{
          for(var i=0;i<v.buffered.length;i++){
            if(v.buffered.start(i)<=t+.1&&v.buffered.end(i)>=t)
              b=Math.max(b,v.buffered.end(i)-t);
          }
        }catch(e){}
        return clean({kind:'video',found:true,paused:v.paused,playing:!v.paused&&!v.ended,
          waiting:state.waiting,ready:v.readyState>=1,
          playRejected:!!state.playError,playError:state.playError,
          current:t,duration:Number(v.duration)||0,decodedFrames:decoded,
          readyState:v.readyState,mediaError:v.error?Number(v.error.code)||0:0,
          videoWidth:Number(v.videoWidth)||0,videoHeight:Number(v.videoHeight)||0,
          bufferedAhead:b,isolated:!!activeIsolation&&activeIsolation.node===v,
          captions:!!v.textTracks&&v.textTracks.length>0,
          muted:v.muted||v.volume===0,ended:v.ended});
      }
      function choose(){
        var v=video();if(v)return {node:v,stats:sample(v),direct:true};
        var report=chooseChild();
        if(!report)return null;
        var updated=Object.assign({},report.state);
        updated.isolated=!!(activeIsolation&&activeIsolation.node===report.node&&report.state.isolated);
        return {node:report.node,stats:updated,direct:false};
      }
      function publish(){
        if(activeIsolation)enforce();
        if(window.parent===window)return;
        var chosen=choose();
        if(!chosen)return;
        try{window.parent.postMessage({tag:MARK,type:'state',state:chosen.stats},'*');}
        catch(e){}
      }
      function activate(v){
        if(!v)return;
        state.playError='';
        try{
          var result=v.play();
          if(result&&result.catch)result.catch(function(error){
            state.playError=String(error&&error.name||'rejected').slice(0,40);
          });
        }catch(error){state.playError=String(error&&error.name||'rejected').slice(0,40);}
      }
      function control(action,value,explicitFrame){
        if(explicitFrame&&explicitFrame.tagName==='IFRAME'){
          explicitFrame.contentWindow.postMessage({tag:MARK,type:'control',action:action,value:value},'*');
          return true;
        }
        var own=video();
        if(!own){
          var child=chooseChild();if(!child)return false;
          child.node.contentWindow.postMessage({tag:MARK,type:'control',action:action,value:value},'*');
          return true;
        }
        if(action==='play'){activate(own);return true;}
        if(action==='pause'){own.pause();return true;}
        if(action==='toggle'){if(own.paused)activate(own);else own.pause();return true;}
        if(action==='unmute'){
          own.defaultMuted=false;own.muted=false;if(own.volume===0)own.volume=1;
          return true;
        }
        if(action==='toggleMute'){
          if(own.muted||own.volume===0){own.defaultMuted=false;own.muted=false;own.volume=1;}
          else {own.muted=true;}
          return true;
        }
        if(action==='rewind'){own.currentTime=Math.max(0,own.currentTime-10);return true;}
        if(action==='forward'){own.currentTime=Math.min(isFinite(own.duration)?own.duration:own.currentTime+10,own.currentTime+10);return true;}
        if(action==='seek'&&Number.isFinite(Number(value))&&isFinite(own.duration)){
          own.currentTime=Math.max(0,Math.min(own.duration,Number(value)));return true;
        }
        if(action==='speed'&&Number.isFinite(Number(value))){own.playbackRate=Math.max(.5,Math.min(2,Number(value)));return true;}
        if(action==='captions'&&own.textTracks){
          for(var i=0;i<own.textTracks.length;i++){
            var t=own.textTracks[i];t.mode=t.mode==='showing'?'disabled':'showing';
          }return true;
        }
        return false;
      }
      function focused(target){
        if(!document.body||!document.documentElement||!document.body.contains(target))return false;
        restore();
        var touched=[],seen=[];
        function set(el,key,value){
          if(!el||!el.style)return;
          if(seen.indexOf(el)<0){seen.push(el);touched.push({el:el,style:el.style.cssText});}
          el.style.setProperty(key,value,'important');
        }
        var html=document.documentElement,body=document.body;
        set(html,'background','#000');set(body,'background','#000');
        set(html,'overflow','hidden');set(body,'overflow','hidden');
        var child=target;
        for(var parent=target.parentElement;parent;parent=parent.parentElement){
          for(var i=0;i<parent.children.length;i++){
            var sibling=parent.children[i];if(sibling!==child)set(sibling,'visibility','hidden');
          }
          set(parent,'visibility','visible');
          if(parent!==body&&parent!==html){set(parent,'overflow','visible');set(parent,'transform','none');}
          child=parent;if(parent===html)break;
        }
        set(target,'position','fixed');set(target,'top','0');set(target,'left','0');
        set(target,'width','100vw');set(target,'height','100vh');
        set(target,'max-width','none');set(target,'max-height','none');
        set(target,'object-fit','contain');set(target,'background','#000');
        set(target,'z-index','2147483600');set(target,'visibility','visible');
        var oldControls=target.tagName==='VIDEO'?target.controls:null;
        if(target.tagName==='VIDEO')target.controls=false;
        var guard=null;
        if(typeof MutationObserver==='function'){
          guard=new MutationObserver(function(){
            if(!document.documentElement.contains(target))return;
            var n=target;
            for(var p=target.parentElement;p;p=p.parentElement){
              for(var k=0;k<p.children.length;k++)if(p.children[k]!==n)
                p.children[k].style.setProperty('visibility','hidden','important');
              n=p;if(p===html)break;
            }
          });
          guard.observe(html,{childList:true,subtree:true});
        }
        activeIsolation={node:target,touched:touched,guard:guard,controls:oldControls};
        var r=target.getBoundingClientRect();
        return r.width>=window.innerWidth*.8&&r.height>=window.innerHeight*.8;
      }
      function isolate(explicitFrame){
        var choice=explicitFrame?{node:explicitFrame,direct:false}:choose();if(!choice)return false;
        var success=focused(choice.node);
        if(!success){restore();return false;}
        if(!choice.direct)
          choice.node.contentWindow.postMessage({tag:MARK,type:'isolate'},'*');
        publish();return true;
      }
      function restore(){
        var old=activeIsolation;if(!old)return;
        if(old.guard)old.guard.disconnect();
        for(var i=old.touched.length-1;i>=0;i--)old.touched[i].el.style.cssText=old.touched[i].style;
        if(old.node.tagName==='VIDEO')old.node.controls=old.controls;
        if(old.node.tagName==='IFRAME'){
          try{old.node.contentWindow.postMessage({tag:MARK,type:'restore'},'*');}catch(e){}
        }
        activeIsolation=null;
      }
      function enforce(){
        var old=activeIsolation;if(!old||!old.node)return false;
        var target=old.node,html=document.documentElement;
        if(!html||!html.contains(target)){restore();return false;}
        if(target.tagName==='VIDEO')target.controls=false;
        target.style.setProperty('position','fixed','important');
        target.style.setProperty('z-index','2147483600','important');
        var child=target;
        for(var parent=target.parentElement;parent;parent=parent.parentElement){
          for(var i=0;i<parent.children.length;i++){
            var sibling=parent.children[i];
            if(sibling!==child)sibling.style.setProperty('visibility','hidden','important');
          }
          child=parent;if(parent===html)break;
        }
        if(target.tagName==='IFRAME')
          target.contentWindow.postMessage({tag:MARK,type:'enforce'},'*');
        return true;
      }
      window.addEventListener('message',function(e){
        var d=e.data;
        if(!d||typeof d!=='object'||d.tag!==MARK)return;
        if(d.type==='state'){
          var frame=realFrame(e.source), sanitized=clean(d.state);
          if(!frame||!sanitized)return;
          var found=null;
          for(var i=0;i<childReports.length;i++)if(childReports[i].node===frame){found=childReports[i];break;}
          if(!found){found={node:frame};childReports.push(found);}
          found.state=sanitized;found.at=now();
          // Unlike a native bridge, this contains NO URLs/credentials and is
          // never forwarded outside this WebView.
          publish();return;
        }
        if(e.source!==window.parent || window.parent===window)return;
        if(d.type==='control'&&typeof d.action==='string'&&d.action.length<16)control(d.action,d.value);
        else if(d.type==='isolate')isolate();
        else if(d.type==='restore')restore();
        else if(d.type==='enforce')enforce();
      });
      window.__glasslinkFrameAgent={best:chooseChild,control:control,isolate:isolate,
        restore:restore,enforce:enforce};
      setInterval(publish,850);
      if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',publish);
      else publish();
    })();""".trimIndent()
}
