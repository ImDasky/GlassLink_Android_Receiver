package com.glasslink.receiver

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** Discovers semantic controls inside the official YouTube embed. */
internal class YouTubeFrameBridge(
    private val videoID: String,
    private val events: Events
) {
    interface Events {
        fun onQualityOptions(options: List<String>)
        fun onCaptionsChanged(enabled: Boolean)
        fun onControlUnavailable(control: String)
    }

    private var active = false
    private var reply: JavaScriptReplyProxy? = null
    private var qualityOptions = emptyList<String>()

    fun attach(view: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        val origins = setOf("https://www.youtube.com", "https://www.youtube-nocookie.com")
        active = true
        WebViewCompat.addWebMessageListener(view, "glasslinkFrame", origins) {
                _, message, origin: Uri, isMainFrame, response ->
            if (!active || isMainFrame || origin.scheme != "https" || origin.host !in
                setOf("www.youtube.com", "www.youtube-nocookie.com")) return@addWebMessageListener
            val value = try { JSONObject(message.data.orEmpty()) }
                        catch (_: Exception) { return@addWebMessageListener }
            if (value.optString("videoID") != videoID) return@addWebMessageListener
            when (value.optString("type")) {
                "ready" -> reply = response
                "qualities" -> {
                    val raw = value.optJSONArray("options") ?: return@addWebMessageListener
                    val options = (0 until raw.length().coerceAtMost(16))
                        .map { raw.optString(it) }
                        .filter { QUALITY_LABEL.matches(it) }
                        .distinct()
                    if (options.isEmpty()) events.onControlUnavailable("quality")
                    else {
                        qualityOptions = options
                        events.onQualityOptions(options)
                    }
                }
                "captions" -> events.onCaptionsChanged(value.optBoolean("enabled"))
                "debug" -> Log.i("GlassLinkFrame", value.optString("details").take(3000))
                "unavailable" -> {
                    val control = value.optString("control")
                    if (control == "quality" || control == "captions")
                        events.onControlUnavailable(control)
                }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, origins)
        return true
    }

    fun requestQuality(): Boolean = send("openQuality")
    fun toggleCaptions(): Boolean = send("toggleCaptions")

    fun selectQuality(label: String): Boolean {
        if (label !in qualityOptions) return false
        return send("selectQuality", label)
    }

    fun dismissQuality() { send("closeMenu") }
    fun setPlayerControlsVisible(visible: Boolean) {
        val channel = reply?.takeIf { active } ?: return
        channel.postMessage(JSONObject().put("action", "showControls")
            .put("visible", visible).toString())
    }

    fun close() {
        active = false
        reply = null
        qualityOptions = emptyList()
    }

    private fun send(action: String, label: String? = null): Boolean {
        val channel = reply?.takeIf { active } ?: return false
        val command = JSONObject().put("action", action)
        if (label != null) command.put("label", label)
        channel.postMessage(command.toString())
        return true
    }

    private companion object {
        val QUALITY_LABEL = Regex(
            """(?:\d{3,4}p(?:\d{1,2})?(?: [A-Za-z0-9()+.-]{1,24})?|Auto(?: \(\d{3,4}p(?:\d{1,2})?\))?)"""
        )

        val SCRIPT = """
(function(){
  if(window.top===window || !window.glasslinkFrame)return;
  var match=location.pathname.match(/^\/embed\/([A-Za-z0-9_-]{11})(?:\/|$)/);
  if(!match)return;
  var videoID=match[1];
  var hiddenStyle=document.createElement('style');
  hiddenStyle.textContent=[
    'html.glasslink-native-mode .ytp-chrome-top',
    'html.glasslink-native-mode .ytp-chrome-bottom',
    'html.glasslink-native-mode .ytp-gradient-top',
    'html.glasslink-native-mode .ytp-gradient-bottom',
    'html.glasslink-native-mode .ytp-settings-menu',
    'html.glasslink-native-mode .ytp-popup',
    'html.glasslink-native-mode [role="menu"]',
    'html.glasslink-native-mode .ytp-toast',
    'html.glasslink-native-mode c3-toast',
    'html.glasslink-native-mode .ytp-tooltip',
    'html.glasslink-native-mode [role="tooltip"]',
    'html.glasslink-native-mode .ytp-notification',
    'html.glasslink-native-mode .ytp-large-play-button',
    'html.glasslink-native-mode .ytp-pause-overlay',
    'html.glasslink-native-mode .ytp-bezel'
  ].join(',')+'{opacity:0 !important;transition:none !important;}'+
    'html.glasslink-native-mode .glasslink-caption-hint{opacity:0 !important;}';
  function showControls(visible){
    document.documentElement.classList.toggle('glasslink-native-mode',!visible);
  }
  function emit(type,extra){
    var data={type:type,videoID:videoID};
    if(extra)for(var key in extra)data[key]=extra[key];
    window.glasslinkFrame.postMessage(JSON.stringify(data));
  }
  function label(node){
    return (node.getAttribute('aria-label')||node.innerText||node.textContent||'')
      .trim().replace(/\s+/g,' ');
  }
  function visible(node){
    var rect=node.getBoundingClientRect();
    return rect.width>0&&rect.height>0;
  }
  function controls(){
    return document.querySelectorAll('button,[role="button"],[role="menuitem"]');
  }
  function find(predicate){
    var nodes=controls();
    for(var i=0;i<nodes.length;i++)
      if(visible(nodes[i])&&predicate(label(nodes[i]),nodes[i]))return nodes[i];
    return null;
  }
  function findAny(predicate){
    var nodes=controls();
    for(var i=0;i<nodes.length;i++)
      if(predicate(label(nodes[i]),nodes[i]))return nodes[i];
    return null;
  }
  function waitFor(predicate,done,control,remaining){
    var node=find(predicate);
    if(node){done(node);return;}
    if(remaining<=0){emit('unavailable',{control:control});return;}
    setTimeout(function(){waitFor(predicate,done,control,remaining-1);},100);
  }
  function options(){
    var nodes=document.querySelectorAll('[role="menuitem"]'), found=[];
    for(var i=0;i<nodes.length;i++){
      var name=label(nodes[i]);
      if(visible(nodes[i])&&/^(?:\d{3,4}p|Auto\b)/.test(name)&&
         found.indexOf(name)<0)found.push(name);
    }
    return found;
  }
  function awaitOptions(remaining){
    var found=options();
    if(found.length){emit('qualities',{options:found});return;}
    if(remaining<=0){emit('unavailable',{control:'quality'});return;}
    setTimeout(function(){awaitOptions(remaining-1);},100);
  }
  function openQuality(){
    var settings=findAny(function(name){return name==='Playback Settings';});
    function hierarchy(node){
      var path=[];
      for(var i=0;node&&i<8;i++,node=node.parentElement)
        path.push(node.tagName+'.'+String(node.className).slice(0,80)+'#'+node.id);
      return path;
    }
    var cc=findAny(function(name){return /^Subtitles\/CC turned/.test(name);});
    var progress=document.querySelector('[role="slider"]');
    emit('debug',{details:JSON.stringify({
      settings:hierarchy(settings),cc:hierarchy(cc),progress:hierarchy(progress)
    })});
    if(!settings){emit('unavailable',{control:'quality'});return;}
    settings.click();
    waitFor(function(name){return /^Quality(?:\s|$)/.test(name);},function(node){
      node.click();
      awaitOptions(20);
    },'quality',20);
  }
  function toggleCaptions(){
    var button=findAny(function(name){return /^Subtitles\/CC turned (?:on|off)$/.test(name);});
    if(!button||button.disabled||button.getAttribute('aria-disabled')==='true'){
      emit('unavailable',{control:'captions'});return;
    }
    button.click();
    setTimeout(function(){
      var after=find(function(name){return /^Subtitles\/CC turned (?:on|off)$/.test(name);});
      if(after)emit('captions',{enabled:/turned on$/.test(label(after))});
      else emit('unavailable',{control:'captions'});
    },400);
    watchCaptionHint(50);
  }
  function watchCaptionHint(remaining){
    var windows=document.querySelectorAll('.ytp-caption-window-top');
    for(var i=0;i<windows.length;i++){
      var text=(windows[i].textContent||'').trim().replace(/\s+/g,' ');
      windows[i].classList.toggle('glasslink-caption-hint',
        /Click.*for settings/i.test(text));
    }
    if(remaining>0)setTimeout(function(){watchCaptionHint(remaining-1);},100);
  }
  function reportCaptionState(remaining){
    var button=findAny(function(name){return /^Subtitles\/CC turned (?:on|off)$/.test(name);});
    if(button){
      emit('captions',{enabled:/turned on$/.test(label(button))});
    }else if(remaining>0){
      setTimeout(function(){reportCaptionState(remaining-1);},100);
    }
  }
  function selectQuality(target){
    if(!/^(?:\d{3,4}p|Auto\b)/.test(target))return;
    var choice=find(function(name,node){
      return node.getAttribute('role')==='menuitem'&&name===target;
    });
    if(choice)choice.click();
    else emit('unavailable',{control:'quality'});
  }
  function closeMenu(){
    var nodes=controls();
    for(var i=nodes.length-1;i>=0;i--)
      if(visible(nodes[i])&&label(nodes[i])==='Close'){
        nodes[i].click();return;
      }
  }
  window.glasslinkFrame.onmessage=function(event){
    var command;
    try{command=JSON.parse(event.data);}catch(e){return;}
    if(command.action==='openQuality')openQuality();
    else if(command.action==='toggleCaptions')toggleCaptions();
    else if(command.action==='selectQuality')selectQuality(command.label||'');
    else if(command.action==='closeMenu')closeMenu();
    else if(command.action==='showControls')showControls(command.visible===true);
  };
  function ready(){
    document.head.appendChild(hiddenStyle);
    showControls(false);
    emit('ready');
    reportCaptionState(30);
    watchCaptionHint(50);
  }
  if(document.readyState==='loading')
    document.addEventListener('DOMContentLoaded',ready,{once:true});
  else ready();
})();
""".trimIndent()
    }
}
