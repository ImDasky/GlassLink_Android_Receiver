package com.glasslink.receiver

/**
 * WebView is a private playback engine, NOT a web browser screen. Only a
 * positively identified HTML5 video or a known VIDEO EMBED is ever uncovered.
 * Everything else remains behind an opaque Android view and eventually fails
 * closed, rather than displaying a website on the TV.
 *
 * JS runs in the webpage's regular world and never has a privileged Android
 * bridge. Cross-origin iframe interiors are deliberately not inspected.
 */
internal object WebVideoScript {
    private val helper = """
      function glVisible(e) {
        if (!e || !e.getBoundingClientRect) return false;
        var r=e.getBoundingClientRect();
        if (r.width<120 || r.height<68) return false;
        var s=window.getComputedStyle(e);
        // A previously hidden sibling can become the next video in a SPA.
        // Visibility:hidden still computes layout and is reversed on selection.
        var glasslinkHidden=!!(e.closest && e.closest('[data-glasslink-concealed]'));
        return s.display!=='none' && (s.visibility!=='hidden' || glasslinkHidden) && Number(s.opacity)!==0;
      }
      function glVideo() {
        var all=document.querySelectorAll('video'), best=null, score=-1;
        for(var i=0;i<all.length;i++) {
          var v=all[i];
          if(!glVisible(v)) continue;
          var r=v.getBoundingClientRect();
          var rank=r.width*r.height + ((!v.paused&&!v.ended&&v.readyState>1)?100000000:0);
          if(rank>score){best=v;score=rank;}
        }
        return best;
      }
      function glFrame() {
        var all=document.querySelectorAll('iframe'), best=null, score=-1;
        for(var i=0;i<all.length;i++) {
          var f=all[i]; if(!glVisible(f)) continue;
          var src=f.getAttribute('src')||'', address;
          try { address=new URL(src,document.baseURI); } catch(e) { continue; }
          if(address.protocol!=='https:' && address.protocol!=='http:') continue;
          var host=address.hostname.toLowerCase(), path=address.pathname;
          // A generic iframe or a fullscreen-capable advertisement is NOT a player.
          var youtube=(host==='youtube.com'||host.endsWith('.youtube.com')||
              host==='youtube-nocookie.com'||host.endsWith('.youtube-nocookie.com')) &&
              /^\/embed\/[A-Za-z0-9_-]{11}/.test(path);
          var vimeo=(host==='player.vimeo.com' && /^\/video\/[0-9]+/.test(path));
          if(!youtube && !vimeo) continue;
          var r=f.getBoundingClientRect(), size=r.width*r.height;
          if(size>score){best={node:f,kind:vimeo?'vimeo':'youtube-frame',origin:address.origin};score=size;}
        }
        return best;
      }
      function glTarget() {
        var video=glVideo(), known=glFrame();
        // Preserve the existing official YouTube IFrame path, including TV
        // captions/quality menus, before trying the generic embedded bridge.
        if(known && known.kind==='youtube-frame')return known;
        var child=null;
        try {
          child=window.__glasslinkFrameAgent && window.__glasslinkFrameAgent.best();
        }catch(e){}
        if(child&&child.state&&child.state.kind==='video' && child.node) {
          var r=child.node.getBoundingClientRect();
          var localArea=video?(video.getBoundingClientRect().width*video.getBoundingClientRect().height):0;
          var childArea=r.width*r.height;
          if(childArea>=120*68 && (!video || child.state.playing || childArea>localArea*1.2)) {
            return {node:child.node,kind:'frame-video',frame:child.state,video:null};
          }
        }
        if(video)return {node:video,kind:'video',video:video};
        // A known iframe alone is still a complete third-party player UI.
        // Reveal only an iframe whose own frame agent found a real video.
        return null;
      }
    """.trimIndent()

    /**
     * Metadata alone does not prove playback: return decoded-frame and clock
     * progress signals so Kotlin can distinguish stalls from actual playback.
     * Only report a media URL to the receiver in memory, never to logcat.
     */
    val probe: String = """(function(){
      $helper
      var target=glTarget(), v=target&&target.video;
      if(!target) return JSON.stringify({found:false});
      if(!target.node.__glasslinkID){window.__glasslinkTargetSerial=(window.__glasslinkTargetSerial||0)+1;target.node.__glasslinkID=window.__glasslinkTargetSerial;}
      if(target.kind==='frame-video'){
        var frame=target.frame||{};
        var isLocal=window.__glasslinkFrameAgent && window.__glasslinkFrameAgent.best();
        return JSON.stringify({found:true,kind:'frame-video',
          ready:frame.ready===true,playing:frame.playing===true,paused:frame.paused===true,
          waiting:frame.waiting===true,playRejected:frame.playRejected===true,
          playError:String(frame.playError||'').slice(0,40),
          current:Number(frame.current)||0,duration:Number(frame.duration)||0,
          decodedFrames:Number.isFinite(Number(frame.decodedFrames))?Number(frame.decodedFrames):-1,
          readyState:Number(frame.readyState)||0,networkState:0,
          mediaError:Number(frame.mediaError)||0,bufferedAhead:Number(frame.bufferedAhead)||0,
          videoWidth:Number(frame.videoWidth)||0,videoHeight:Number(frame.videoHeight)||0,
          ended:frame.ended===true,captions:frame.captions===true,
          muted:frame.muted===true,
          isolated:!!(isLocal&&isLocal.node===target.node&&frame.isolated&&
             target.node.style.position==='fixed'),targetID:target.node.__glasslinkID,
          sourceURL:''});
      }
      if(v) {
        if(!v.__glasslinkPlayback) v.__glasslinkPlayback={waiting:false,playError:'',hasPlayed:false};
        if(!v.__glasslinkObserved) {
          v.__glasslinkObserved=true;
          // Count frames the browser actually presents, when supported.
          // This catches live/MSE streams whose currentTime does not advance
          // normally and distinguishes real decoded video from a moving UI.
          if(typeof v.requestVideoFrameCallback==='function'){
            v.__glasslinkPresentedFrames=0;
            var watch=function(time,metadata){
              v.__glasslinkPresentedFrames=Math.max(
                v.__glasslinkPresentedFrames||0,Number(metadata&&metadata.presentedFrames)||0);
              if(document.documentElement && document.documentElement.contains(v))
                v.requestVideoFrameCallback(watch);
            };
            v.requestVideoFrameCallback(watch);
          }
          v.addEventListener('playing',function(){v.__glasslinkPlayback.waiting=false;v.__glasslinkPlayback.hasPlayed=true;});
          v.addEventListener('waiting',function(){v.__glasslinkPlayback.waiting=true;});
          v.addEventListener('stalled',function(){v.__glasslinkPlayback.waiting=true;});
          v.addEventListener('canplay',function(){v.__glasslinkPlayback.waiting=false;});
        }
      }
      var duration=v?(Number(v.duration)||0):(Number(window.__glasslinkVimeoDuration)||0);
      var seconds=v?(Number(v.currentTime)||0):(Number(window.__glasslinkVimeoTime)||0);
      var frames=-1, bufferedAhead=0;
      if(v) {
        try {if(v.getVideoPlaybackQuality) frames=v.getVideoPlaybackQuality().totalVideoFrames||0;
          else if(typeof v.webkitDecodedFrameCount==='number')frames=v.webkitDecodedFrameCount;
          if(typeof v.__glasslinkPresentedFrames==='number')
            frames=Math.max(frames,v.__glasslinkPresentedFrames);
        }catch(e){}
        try {for(var i=0;i<v.buffered.length;i++) {
          if(v.buffered.start(i)<=seconds+.1 && v.buffered.end(i)>=seconds)
            bufferedAhead=Math.max(bufferedAhead,v.buffered.end(i)-seconds);
        }}catch(e){}
      }
      var st=v?(v.__glasslinkPlayback||{}):{};
      return JSON.stringify({found:true,kind:target.kind,
        ready:!!v ? v.readyState>=1 : true,
        playing:!!v ? (!v.paused&&!v.ended) : !!window.__glasslinkVimeoPlaying,
        paused:!!v ? v.paused : !window.__glasslinkVimeoPlaying,
        waiting:!!st.waiting,playRejected:!!st.playError,playError:String(st.playError||'').slice(0,80),
        readyState:v?v.readyState:0,networkState:v?v.networkState:0,
        mediaError:v&&v.error?Number(v.error.code)||0:0,
        decodedFrames:frames,bufferedAhead:bufferedAhead,
        videoWidth:v?Number(v.videoWidth)||0:0,videoHeight:v?Number(v.videoHeight)||0:0,
        sourceURL:v?(v.currentSrc||v.src||''):(target.node.src||''),
        targetID:target.node.__glasslinkID,
        ended:!!v && v.ended,
        isolated:target.node.hasAttribute('data-glasslink-isolated'),
        current:seconds,duration:duration,
        captions:!!v && !!v.textTracks && v.textTracks.length>0,
        muted:!!v && (v.muted || v.volume===0)});
    })();""".trimIndent()

    /** Start the site's existing player with sound, without reloading its stream. */
    val attemptPlay: String = """(function(){
      $helper
      var target=glTarget();
      if(target&&target.kind==='frame-video'){
        var agent=window.__glasslinkFrameAgent;
        agent.control('unmute',0,target.node);
        return agent.control('play',0,target.node)?'requested':'no-frame';
      }
      var v=glVideo();if(!v)return 'no-video';
      var st=v.__glasslinkPlayback||(v.__glasslinkPlayback={waiting:false,playError:''});
      st.playError='';
      try {
        v.defaultMuted=false;v.muted=false;if(v.volume===0)v.volume=1;
        var p=v.play();
        if(p && typeof p.then==='function')p.then(function(){st.playError='';})
          .catch(function(e){st.playError=String(e&&e.name||e||'not-allowed');});
        return 'requested';
      }catch(e){st.playError=String(e&&e.name||e||'play-failed');return 'failed';}
    })();""".trimIndent()

    /**
     * This runs only AFTER the first verified advancing frame/time. Do not
     * manipulate a site's live player before its JavaScript has initialized.
     * Styling is reversible and no <video> nodes are detached/reparented.
     */
    val isolate: String = """(function(){
      $helper
      var t=glTarget();if(!t)return JSON.stringify({ok:false,reason:'no-video'});
      if(t.kind==='frame-video'){
        var ok=!!(window.__glasslinkFrameAgent && window.__glasslinkFrameAgent.isolate(t.node));
        return JSON.stringify({ok:ok,kind:'frame-video'});
      }
      var n=t.node, body=document.body, html=document.documentElement;
      if(!body || !html || !body.contains(n))return JSON.stringify({ok:false,reason:'detached'});
      var existing=window.__glasslinkIsolation;
      if(existing && existing.node===n) return JSON.stringify({ok:true,kind:t.kind});
      if(existing && window.__glasslinkRestore)window.__glasslinkRestore();
      var touched=[], seen=new WeakSet(), originalControls=t.video?t.video.controls:null;
      function set(e,k,v){
        if(!e || !e.style)return;
        if(!seen.has(e)){seen.add(e);touched.push({el:e,css:e.style.cssText});}
        e.style.setProperty(k,v,'important');
      }
      function shield(){
        var child=n;
        for(var parent=n.parentElement;parent;parent=parent.parentElement){
          for(var i=0;i<parent.children.length;i++){
            var other=parent.children[i];
            if(other!==child)set(other,'visibility','hidden');
          }
          set(parent,'visibility','visible');
          if(parent!==body && parent!==html){
            set(parent,'overflow','visible');set(parent,'transform','none');
          }
          child=parent;
          if(parent===html)break;
        }
      }
      set(html,'overflow','hidden');set(body,'overflow','hidden');
      set(html,'background-color','#000');set(body,'background-color','#000');
      set(n,'position','fixed');set(n,'top','0');set(n,'left','0');
      set(n,'width','100vw');set(n,'height','100vh');
      set(n,'max-width','none');set(n,'max-height','none');
      set(n,'object-fit','contain');set(n,'background-color','#000');
      set(n,'transform','none');set(n,'margin','0');set(n,'z-index','2147483600');
      set(n,'visibility','visible');
      if(t.video)t.video.controls=false;
      shield();
      var guard=new MutationObserver(function(records){
        // Child insertions may include ad overlays. Do not mutate the video or
        // its source subtree; only hide newly inserted siblings.
        if(!document.documentElement.contains(n))return;
        shield();
      });
      guard.observe(html,{subtree:true,childList:true});
      window.__glasslinkIsolation={node:n,touched:touched,guard:guard,originalControls:originalControls};
      window.__glasslinkRestore=function(){
        var state=window.__glasslinkIsolation;if(!state)return;
        state.guard.disconnect();
        for(var i=state.touched.length-1;i>=0;i--){
          var item=state.touched[i];item.el.style.cssText=item.css;
        }
        if(state.node.tagName==='VIDEO')state.node.controls=state.originalControls;
        window.__glasslinkIsolation=null;
      };
      var rect=n.getBoundingClientRect();
      var ok=rect.width>=window.innerWidth*.8 && rect.height>=window.innerHeight*.8;
      return JSON.stringify({ok:ok,kind:t.kind});
    })();""".trimIndent()

    /**
     * Last-resort support for older TV WebViews without document-start subframe
     * injection: only return ONE large, plausibly video-specific iframe URL.
     * Loading it in the SAME WebView keeps TV-local cookies, but embedding-only
     * pages may reject top-level navigation. No site is scraped or bypassed.
     */
    val standaloneEmbedCandidate: String = """(function(){
      var list=document.querySelectorAll('iframe'),best=null,score=-1,chosen=0;
      for(var i=0;i<list.length;i++) {
        var node=list[i],r=node.getBoundingClientRect();
        if(r.width<350||r.height<190)continue;
        var css=window.getComputedStyle(node);
        if(css.display==='none'||css.visibility==='hidden')continue;
        var raw=node.getAttribute('src')||'';
        var uri;
        try{uri=new URL(raw,document.baseURI);}catch(e){continue;}
        if(uri.protocol!=='http:'&&uri.protocol!=='https:')continue;
        if(uri.username||uri.password||uri.href.length>8192)continue;
        var p=uri.pathname.toLowerCase(),host=uri.hostname.toLowerCase();
        if(/\/(?:ads?|advert|preroll|banner|vast)(?:\/|$)/.test(p)||
           host.indexOf('doubleclick')>=0)continue;
        var allow=(node.getAttribute('allow')||'').toLowerCase();
        var hasFullscreen=node.hasAttribute('allowfullscreen')||
          allow.indexOf('fullscreen')>=0||allow.indexOf('autoplay')>=0;
        var looksLikePlayer=/(?:embed|player|stream|live|channel|watch|video)/.test(p);
        if(!hasFullscreen&&!looksLikePlayer)continue;
        var area=r.width*r.height;
        if(area>score){score=area;best=uri.href;chosen=1;}
      }
      return JSON.stringify(best?{found:true,url:best}:{found:false});
    })();""".trimIndent()

    /** Restore all styles on a stall; the native black cover stays visible. */
    val restore: String = """(function(){
       if(window.__glasslinkFrameAgent)window.__glasslinkFrameAgent.restore();
       if(window.__glasslinkRestore){window.__glasslinkRestore();return 'restored';}
       return 'nothing-to-restore';
    })();""".trimIndent()

    /** Reassert video-only styling when a website changes controls or overlays. */
    val enforceIsolation: String = """(function(){
      var agent=window.__glasslinkFrameAgent;
      if(agent&&agent.enforce())return 'frame';
      var state=window.__glasslinkIsolation;
      if(!state||!state.node)return 'none';
      var n=state.node, html=document.documentElement;
      if(!html||!html.contains(n))return 'lost';
      if(n.tagName==='VIDEO')n.controls=false;
      n.style.setProperty('position','fixed','important');
      n.style.setProperty('z-index','2147483600','important');
      var child=n;
      for(var parent=n.parentElement;parent;parent=parent.parentElement){
        for(var i=0;i<parent.children.length;i++){
          var sibling=parent.children[i];
          if(sibling!==child)sibling.style.setProperty('visibility','hidden','important');
        }
        child=parent;if(parent===html)break;
      }
      return 'ok';
    })();""".trimIndent()

    /** Identical-key commands for any accessible HTMLMediaElement. */
    fun command(action: String, value: Double? = null): String? {
        val safe = value?.takeIf { it.isFinite() }?.coerceIn(0.0, 86400.0) ?: 0.0
        val op = when (action) {
            "toggle" -> "if(v.paused){var st=v.__glasslinkPlayback||(v.__glasslinkPlayback={});st.playError='';var p=v.play();if(p&&p.catch)p.catch(function(e){st.playError=String(e&&e.name||e);});}else v.pause();"
            "play" -> "var st=v.__glasslinkPlayback||(v.__glasslinkPlayback={});st.playError='';var p=v.play();if(p&&p.catch)p.catch(function(e){st.playError=String(e&&e.name||e);});"
            "pause" -> "v.pause();"
            "rewind" -> "if(isFinite(v.currentTime))v.currentTime=Math.max(0,v.currentTime-10);"
            "forward" -> "if(isFinite(v.currentTime))v.currentTime=Math.min(isFinite(v.duration)?v.duration:v.currentTime+10,v.currentTime+10);"
            "seek" -> "if(isFinite(v.duration)&&v.duration>0)v.currentTime=Math.min(v.duration,$safe);"
            "speed" -> "v.playbackRate=Math.max(0.5,Math.min(2,$safe));"
            "unmute" -> "v.defaultMuted=false;v.muted=false;if(v.volume===0)v.volume=1;"
            "toggleMute" -> "if(v.muted||v.volume===0){v.defaultMuted=false;v.muted=false;v.volume=1;}else v.muted=true;"
            "captions" -> "if(v.textTracks)for(var i=0;i<v.textTracks.length;i++){var t=v.textTracks[i];t.mode=t.mode==='showing'?'disabled':'showing';}"
            else -> return null
        }
        return """(function(){ $helper
          var target=glTarget();
          if(target&&target.kind==='frame-video'){
            return window.__glasslinkFrameAgent.control('$action',$safe,target.node)?'sent':'unsupported';
          }
          var v=glVideo();if(!v)return 'unsupported'; $op return 'ok'; })();"""
    }

    /** Known Vimeo iframe API (not arbitrary cross-origin iframe scripting). */
    fun iframeCommand(action: String, value: Double? = null): String? {
        val safe = value?.takeIf { it.isFinite() }?.coerceIn(0.0, 86400.0) ?: 0.0
        val method = when(action) {
            "play" -> "play"
            "toggle" -> "toggle"
            "pause" -> "pause"
            "seek" -> "setCurrentTime"
            "speed" -> "setPlaybackRate"
            "rewind" -> "rewind"
            "forward" -> "forward"
            else -> return null
        }
        return """(function(){
            $helper
            var f=glFrame();if(!f||f.kind!=='vimeo')return 'unsupported';
            var method='$method', val=$safe;
            if(method==='toggle')method=window.__glasslinkVimeoPlaying?'pause':'play';
            if(method==='rewind'||method==='forward') {
              if(!isFinite(window.__glasslinkVimeoTime))return 'no-time';
              val=Math.max(0,window.__glasslinkVimeoTime+(method==='rewind'?-10:10));
              method='setCurrentTime';
            }
            var msg={method:method};
            if(method==='setCurrentTime'||method==='setPlaybackRate')msg.value=val;
            f.node.contentWindow.postMessage(JSON.stringify(msg),f.origin);
            return 'sent';
        })();""".trimIndent()
    }

    val vimeoEvents: String = """(function(){
      $helper
      var target=glFrame();if(!target||target.kind!=='vimeo')return 'no-vimeo';
      var f=target.node, origin=target.origin;
      if(!window.__glasslinkVimeoListening){
        window.__glasslinkVimeoListening=true;
        window.addEventListener('message',function(e){
          var current=glFrame();if(!current||e.origin!==current.origin)return;
          var d=e.data;
          if(typeof d==='string'){try{d=JSON.parse(d);}catch(error){return;}}
          if(!d||typeof d!=='object')return;
          if(d.event==='timeupdate' && d.data){
            window.__glasslinkVimeoTime=Number(d.data.seconds)||0;
            window.__glasslinkVimeoDuration=Number(d.data.duration)||0;
            window.__glasslinkVimeoPlaying=true;
          }
          if(d.event==='pause')window.__glasslinkVimeoPlaying=false;
          if(d.event==='play')window.__glasslinkVimeoPlaying=true;
        });
      }
      // Calling this again is safe if the iframe was slow to initialize.
      ['timeupdate','pause','play'].forEach(function(e){
        f.contentWindow.postMessage(JSON.stringify({method:'addEventListener',value:e}),origin);
      });
      return 'listening';
    })();""".trimIndent()
}
