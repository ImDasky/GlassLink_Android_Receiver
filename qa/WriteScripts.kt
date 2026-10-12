package com.glasslink.receiver
import java.io.File
fun main(args:Array<String>) {
  val dir=File(args[0]);dir.mkdirs()
  File(dir,"frame_agent.js").writeText(FramePlaybackScript.install)
  File(dir,"probe.js").writeText(WebVideoScript.probe)
  File(dir,"isolate.js").writeText(WebVideoScript.isolate)
  File(dir,"enforce.js").writeText(WebVideoScript.enforceIsolation)
  File(dir,"start.js").writeText(WebVideoScript.attemptPlay)
  File(dir,"restore.js").writeText(WebVideoScript.restore)
  File(dir,"command.js").writeText(WebVideoScript.command("toggle")!!)
  File(dir,"sound.js").writeText(WebVideoScript.command("toggleMute")!!)
  File(dir,"unmute.js").writeText(WebVideoScript.command("unmute")!!)
  File(dir,"standalone.js").writeText(WebVideoScript.standaloneEmbedCandidate)
}
