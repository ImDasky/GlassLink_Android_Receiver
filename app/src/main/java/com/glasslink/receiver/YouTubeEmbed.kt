package com.glasslink.receiver

/**
 * The supported YouTube iframe player is the ONLY webpage content rendered.
 * Remote control is via the documented IFrame API; YouTube supplies actual
 * media streams/DRM. No attempt to extract the YouTube video file is made.
 */
internal object YouTubeEmbed {
    const val BASE_URL = "https://appassets.androidplatform.net/"

    fun html(videoID: String): String {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoID))
        return """
<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>
 html,body,#player {height:100%;width:100%;margin:0;padding:0;background:#000;overflow:hidden;}
 iframe {display:block;width:100%;height:100%;border:0;}
</style></head><body><div id="player"></div>
<script>
'use strict';
window.glasslinkState='OPENING';
var glPlayer=null;
function onYouTubeIframeAPIReady(){
  glPlayer=new YT.Player('player', {
    width:'100%',height:'100%',videoId:'$videoID',
    // Keep the provider's control bar hidden. GlassLink owns TV navigation.
    playerVars:{autoplay:1,controls:0,disablekb:1,playsinline:1,fs:0,hl:'en',
      iv_load_policy:3,
      origin:'https://appassets.androidplatform.net'},
    events:{
      onReady:function(e){window.glasslinkState='READY';e.target.playVideo();},
      onStateChange:function(e){
        var states={'-1':'READY','0':'ENDED','1':'PLAYING','2':'PAUSED','3':'BUFFERING','5':'READY'};
        window.glasslinkState=states[String(e.data)]||'READY';
      },
      onError:function(e){window.glasslinkState='ERROR_'+String(e.data);},
      onAutoplayBlocked:function(){window.glasslinkState='BLOCKED';}
    }
  });
}
window.glasslinkInfo=function(){
  var state=window.glasslinkState||'OPENING',playing=state==='PLAYING';
  var position=0,duration=0;
  try {if(glPlayer){position=Number(glPlayer.getCurrentTime())||0;
                    duration=Number(glPlayer.getDuration())||0;}} catch(e) {}
  return JSON.stringify({state:state,found:state==='READY'||state==='PLAYING'||
    state==='PAUSED'||state==='BUFFERING'||state==='BLOCKED'||state==='ENDED',
    ready:state!=='OPENING',playing:playing,current:position,duration:duration,
    kind:'youtube',captions:false});
};
window.glasslinkRemote=function(key,amount){
  if(!glPlayer)return 'not-ready';
  try {
    if(key==='toggle') {if(glPlayer.getPlayerState()===1)glPlayer.pauseVideo();else glPlayer.playVideo();}
    else if(key==='play')glPlayer.playVideo();
    else if(key==='pause')glPlayer.pauseVideo();
    else if(key==='rewind')glPlayer.seekTo(Math.max(0,glPlayer.getCurrentTime()-10),true);
    else if(key==='forward')glPlayer.seekTo(Math.min(glPlayer.getDuration(),glPlayer.getCurrentTime()+10),true);
    else if(key==='seek')glPlayer.seekTo(Math.max(0,Math.min(glPlayer.getDuration(),Number(amount)||0)),true);
    else if(key==='speed')glPlayer.setPlaybackRate(Math.max(.5,Math.min(2,Number(amount)||1)));
    return 'ok';
  }catch(e){return 'unavailable';}
};
var tag=document.createElement('script');
tag.src='https://www.youtube.com/iframe_api';tag.async=true;
tag.onerror=function(){window.glasslinkState='LOAD_ERROR';};
document.head.appendChild(tag);
</script></body></html>
""".trimIndent()
    }
}
