// SPDX-License-Identifier: GPL-3.0-or-later
package app.downloadverse.android

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.util.AtomicFile
import android.util.Patterns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors

class App:Application() { override fun onCreate() { super.onCreate();Store.initialize(this) } }

object Rules {
 private val extensions=setOf("mp4","m4v","mkv","webm","mov","mp3","m4a","wav","ogg","flac","jpg","jpeg","png","webp","gif","avif","heic","pdf","zip","7z","rar","txt","csv","json","epub","docx","xlsx","pptx","apk")
 fun url(raw:String):String {
  val s=raw.trim()
  require(s.length in 1..8192 && s.none { it.isWhitespace() || it.code<32 || it.code==127 || it.code==92 }) { "Paste a complete link without spaces or control characters." }
  val u=try { URI(s) }catch(_:Exception) { throw IllegalArgumentException("This is not a valid link.") }
  require(u.scheme?.lowercase() in setOf("http","https") && !u.host.isNullOrBlank()) { "Only complete http:// and https:// links are supported." }
  require(u.rawUserInfo==null) { "Links containing usernames or passwords are not accepted." }
  require(u.port==-1 || u.port in 1..65535) { "Invalid port in link." }
  return s.substringBefore('#')
 }
 fun batch(text:String):List<String> {
  val rows=text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
  require(rows.size in 1..20) { "Add 1–20 links, one per line." };return rows.map(::url).distinct()
 }
 fun mode(url:String,selected:String):String {
  require(selected in setOf("auto","video","audio","file"))
  if(selected!="auto")return selected
  return if(URI(url).path.orEmpty().substringAfterLast('.',"").lowercase() in extensions)"file" else "video"
 }
 fun name(text:String):String=text.map { if(it.code<32 || it.code in setOf(127,34,47,58,60,62,63,92,124) || it.code in 0x202a..0x202e || it.code in 0x2066..0x2069)'_' else it }.joinToString("").trim().trim('.').take(150).ifBlank { "download" }
 fun title(url:String):String { val raw=URI(url).rawPath.orEmpty().substringAfterLast('/');return name(try { URLDecoder.decode(raw.replace("+","%2B"),"UTF-8") }catch(_:Exception) { raw }) }
 fun error(text:String):String=text.replace(Regex("https?://[^\\s]+"),"[link]").takeLast(800).ifBlank { "Download failed. Check the link, connection and free storage, then retry." }
}

data class Job(val id:String,val url:String,val mode:String,val quality:String,var title:String,var state:String="queued",var percent:Int=-1,var message:String="Waiting to start",var systemId:Long=-1,var uri:String="",var pendingUri:String="",var mime:String="application/octet-stream",val created:Long=System.currentTimeMillis()) {
 fun json()=JSONObject().apply { put("id",id);put("url",url);put("mode",mode);put("quality",quality);put("title",title);put("state",state);put("percent",percent);put("message",message);put("systemId",systemId);put("uri",uri);put("pendingUri",pendingUri);put("mime",mime);put("created",created) }
 companion object {
  val terminal=setOf("complete","failed","cancelled","interrupted")
  fun from(j:JSONObject)=Job(j.getString("id"),j.getString("url"),j.getString("mode"),j.getString("quality"),j.getString("title"),j.optString("state","interrupted"),j.optInt("percent",-1),j.optString("message"),j.optLong("systemId",-1),j.optString("uri"),j.optString("pendingUri"),j.optString("mime","application/octet-stream"),j.optLong("created"))
 }
}
object Store {
 private val rows=linkedMapOf<String,Job>();private lateinit var disk:AtomicFile;private var ready=false
 var notice="";private set
 @Synchronized fun initialize(c:Context) {
  if(ready)return;disk=AtomicFile(File(c.filesDir,"queue-v1.json"))
  if(disk.baseFile.exists())try {
   val data=JSONObject(String(disk.readFully(),Charsets.UTF_8)).getJSONArray("jobs");require(data.length()<=250)
   for(i in 0 until data.length()) { val j=Job.from(data.getJSONObject(i));require(j.id.matches(Regex("[a-f0-9-]{36}")))
    if(j.state !in Job.terminal && (j.mode!="file" || j.systemId<0)) { j.state="interrupted";j.message="Android or the app stopped this task. Tap Retry." };rows[j.id]=j
   }
  }catch(_:Exception) { disk.baseFile.copyTo(File(c.filesDir,"history-unreadable-${System.currentTimeMillis()}.json"),false);rows.clear();notice="History could not be read. A private backup was kept; public files were not removed." }
  ready=true;save()
 }
 @Synchronized private fun save() { val out=disk.startWrite();try { out.write(JSONObject().put("jobs",JSONArray(rows.values.map { it.json() })).toString().toByteArray(Charsets.UTF_8));disk.finishWrite(out) }catch(e:Exception) { disk.failWrite(out);throw e } }
 @Synchronized fun all()=rows.values.map { it.copy() }
 @Synchronized fun get(id:String)=rows[id]?.copy()
 @Synchronized fun change(id:String,block:(Job)->Unit):Job? { val j=rows[id]?:return null;block(j);save();return j.copy() }
 @Synchronized fun add(urls:List<String>,mode:String,quality:String):List<Job> {
  require(quality in setOf("best","1080","720","480"))
  val urlsNew=urls.filter { url->rows.values.none { it.url==url && it.mode==Rules.mode(url,mode) && it.quality==quality && it.state !in Job.terminal } }
  require(rows.size+urlsNew.size<=250) { "History is full. Remove finished history items first." }
  val added=urlsNew.map { Job(UUID.randomUUID().toString(),it,Rules.mode(it,mode),quality,Rules.title(it)) };added.forEach { rows[it.id]=it };save();return added.map { it.copy() }
 }
 @Synchronized fun next():Job? { val j=rows.values.firstOrNull { it.mode!="file" && it.state=="queued" }?:return null;j.state="preparing";j.message="Preparing bundled media tools";j.percent=-1;save();return j.copy() }
 @Synchronized fun queued()=rows.values.any { it.mode!="file" && it.state=="queued" }
 @Synchronized fun retry(id:String):Job { val j=rows[id]?:throw IllegalArgumentException("Task removed");require(j.state in setOf("failed","interrupted","cancelled")) { "Task is active or already complete." };val old=j.copy();j.state="queued";j.percent=-1;j.message="Waiting to retry";j.systemId=-1;j.uri="";save();return old }
 @Synchronized fun remove(id:String) { val j=rows[id]?:return;require(j.state in Job.terminal);rows.remove(id);save() }
 @Synchronized fun publish(id:String,uri:String,commit:()->Unit) { val j=rows[id]?:throw InterruptedException("Task removed");if(j.state in setOf("cancelled","cancelling","interrupted"))throw InterruptedException("Stopped");commit();j.uri=uri;j.pendingUri="";j.state="complete";j.percent=100;j.message="Saved to Downloads / DownloadVerse";save() }
}
object Direct {
 fun manager(c:Context)=requireNotNull(c.getSystemService(DownloadManager::class.java))
 fun mime(name:String)=MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.',"").lowercase())?:"application/octet-stream"
 fun enqueue(c:Context,j:Job) {
  val base=Rules.title(j.url);val dot=base.lastIndexOf('.');val name=if(dot>0)base.substring(0,dot)+"-"+j.id.take(8)+base.substring(dot) else base+"-"+j.id.take(8)
  val request=DownloadManager.Request(Uri.parse(j.url)).setTitle(name).setDescription("DownloadVerse direct file").setAllowedOverMetered(true).setAllowedOverRoaming(false).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"DownloadVerse/$name")
  val id=manager(c).enqueue(request)
  try { Store.change(j.id) { it.systemId=id;it.title=name;it.state="downloading";it.message="Managed by Android" } }catch(e:Exception) { manager(c).remove(id);throw e }
 }
 fun cancel(c:Context,j:Job) { if(j.systemId>=0)manager(c).remove(j.systemId);Store.change(j.id) { it.systemId=-1;it.state="cancelled";it.percent=-1;it.message="Cancelled" } }
 fun refresh(c:Context) {
  val dm=manager(c)
  Store.all().filter { it.mode=="file" && it.systemId>=0 && it.state !in Job.terminal }.forEach { j->
   dm.query(DownloadManager.Query().setFilterById(j.systemId))?.use { q->
    if(!q.moveToFirst()) { Store.change(j.id) { it.state="failed";it.message="Android no longer has this task. Retry if needed." };return@use }
    fun n(key:String)=q.getLong(q.getColumnIndexOrThrow(key))
    val status=n(DownloadManager.COLUMN_STATUS).toInt();val reason=n(DownloadManager.COLUMN_REASON).toInt();val done=n(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);val total=n(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
    val type=q.getString(q.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)).orEmpty()
    Store.change(j.id) { row->
     if(row.state in Job.terminal || row.state=="cancelling")return@change
     row.percent=if(total>0)(100L*done/total).coerceIn(0L,99L).toInt() else -1
     when(status) {
      DownloadManager.STATUS_SUCCESSFUL->{val uri=dm.getUriForDownloadedFile(j.systemId);if(uri==null || total==0L || type.substringBefore(';') in setOf("text/html","application/xhtml+xml")) { row.state="failed";row.message="The link returned a web page or empty file. Try a direct file URL or Video mode." }else { row.uri=uri.toString();row.mime=type.ifBlank { mime(j.title) };row.state="complete";row.percent=100;row.message="Saved to Downloads / DownloadVerse" }}
      DownloadManager.STATUS_FAILED->{row.state="failed";row.message=when(reason) { DownloadManager.ERROR_INSUFFICIENT_SPACE->"Not enough storage. Free space and Retry.";DownloadManager.ERROR_CANNOT_RESUME->"The server cannot resume. Retry starts a new transfer.";DownloadManager.ERROR_DEVICE_NOT_FOUND->"Download storage unavailable.";in 400..499->"Server refused the link (HTTP $reason). It may be private or expired.";else->"Android download error $reason. Check the link, connection and storage." }}
      DownloadManager.STATUS_PENDING,DownloadManager.STATUS_PAUSED->{row.state="waiting";row.message="Waiting for Android or network availability"}
      else->{row.state="downloading";row.message="Downloading with Android"}
     }
    }
   }
  }
 }
}
object Engine {
 @Volatile private var ready=false
 @Synchronized fun initialize(c:Context) { if(ready)return;YoutubeDL.getInstance().init(c.applicationContext);FFmpeg.getInstance().init(c.applicationContext);ready=true }
 fun cancel(id:String) { if(ready)YoutubeDL.getInstance().destroyProcessById(id) }
 fun update(c:Context) { initialize(c);YoutubeDL.getInstance().updateYoutubeDL(c.applicationContext,YoutubeDL.UpdateChannel.STABLE) }
 fun version(c:Context):String=try { YoutubeDL.getInstance().version(c.applicationContext).orEmpty().ifBlank { "Bundled engine" } }catch(_:Exception) { "Bundled engine" }
 fun download(c:Context,j:Job,folder:File,onProgress:(Int,String)->Unit):File {
  initialize(c);val marker=File(folder,"engine-success.txt")
  if(marker.isFile) { val existing=File(marker.readText().trim()).canonicalFile;if(existing.parentFile==folder.canonicalFile && existing.isFile && existing.length()>0)return existing;marker.delete() }
  val req=YoutubeDLRequest(j.url)
  listOf("--ignore-config","--no-playlist","--no-simulate","--no-mtime","--windows-filenames","--continue","--no-overwrites","--no-color","--progress","--newline").forEach { req.addOption(it) }
  req.addOption("--playlist-end","1");req.addOption("--socket-timeout","20");req.addOption("--retries","3");req.addOption("--fragment-retries","3");req.addOption("--concurrent-fragments","4")
  req.addOption("-o",File(folder,"%(title).100B [%(id)s].%(ext)s").absolutePath);req.addOption("--print","after_move:DV_OUTPUT:%(filepath)s")
  if(j.mode=="audio") { req.addOption("-f","bestaudio/best");req.addOption("-x");req.addOption("--audio-format","mp3");req.addOption("--audio-quality","0") }
  else { val cap=if(j.quality=="best")"" else "[height<=${j.quality}]";req.addOption("-f","bv*$cap+ba/b$cap");req.addOption("--merge-output-format","mkv") }
  val response=YoutubeDL.getInstance().execute(req,j.id) { percent,_,line->
   if(Thread.currentThread().isInterrupted)throw InterruptedException("Stopped")
   val p=if(percent.isFinite() && percent>=0)percent.toInt().coerceIn(0,99) else -1
   onProgress(p,if(line.contains("[Merger]") || line.contains("[ExtractAudio]"))"Processing media" else if(p>=0)"Downloading media" else "Contacting media source")
  }
  val paths=response.out.lineSequence().filter { it.startsWith("DV_OUTPUT:") }.map { it.removePrefix("DV_OUTPUT:").trim() }.filter { it.isNotEmpty() }.distinct().toList()
  require(paths.size==1) { "This source did not produce one complete file. Use an individual media link. Partial data was retained." }
  val output=File(paths.single()).canonicalFile
  require(output.parentFile==folder.canonicalFile && output.isFile && output.length()>0 && !output.name.endsWith(".part")) { "No complete output file was produced. Retry or use another link." }
  marker.writeText(output.absolutePath);return output
 }
}
object Publisher {
 fun folder(c:Context,id:String):File { require(id.matches(Regex("[a-f0-9-]{36}")));val f=File(File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?:c.filesDir,"staging"),id);check(f.isDirectory || f.mkdirs()) { "Temporary storage unavailable." };return f }
 fun stopped(id:String)=Thread.currentThread().isInterrupted || Store.get(id)?.state in setOf("cancelled","cancelling","interrupted")
 private fun pending(c:Context,uri:Uri):Boolean { var value=false;c.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)?.use { value=it.moveToFirst() && it.getInt(0)==1 };return value }
 fun cleanup(c:Context,id:String) { Store.get(id)?.pendingUri?.takeIf { it.isNotBlank() }?.let { val uri=Uri.parse(it);if(pending(c,uri))c.contentResolver.delete(uri,null,null) };folder(c,id).deleteRecursively();Store.change(id) { it.pendingUri="" } }
 fun recover(c:Context,j:Job):Boolean {
  if(j.pendingUri.isBlank())return false;val uri=Uri.parse(j.pendingUri);var exists=false;var unfinished=false
  c.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.IS_PENDING),null,null,null)?.use { exists=it.moveToFirst();unfinished=exists && it.getInt(0)==1 }
  if(exists && !unfinished) { Store.change(j.id) { it.uri=j.pendingUri;it.pendingUri="";it.state="complete";it.percent=100;it.message="Recovered previously saved file" };folder(c,j.id).deleteRecursively();return true }
  if(unfinished)c.contentResolver.delete(uri,null,null);Store.change(j.id) { it.pendingUri="" };return false
 }
 fun export(c:Context,j:Job,source:File) {
  require(source.canonicalFile.parentFile==folder(c,j.id).canonicalFile);if(stopped(j.id))throw InterruptedException("Stopped")
  val resolver=c.contentResolver;val name=Rules.name(source.name);val mime=Direct.mime(name)
  val values=ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/DownloadVerse");put(MediaStore.MediaColumns.IS_PENDING,1) }
  val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:throw IllegalStateException("Cannot create a file in Downloads.")
  var committed=false
  try {
   Store.change(j.id) { row->if(row.state in setOf("cancelled","cancelling","interrupted"))throw InterruptedException("Stopped");row.pendingUri=uri.toString();row.title=name;row.mime=mime;row.state="exporting";row.percent=99;row.message="Saving to Downloads" }
   val out=resolver.openOutputStream(uri,"w")?:throw IllegalStateException("Downloads storage is not writable.")
   out.use { output->source.inputStream().use { input->val buffer=ByteArray(1024*1024);while(true) { if(stopped(j.id))throw InterruptedException("Stopped");val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n) } };output.flush() }
   Store.publish(j.id,uri.toString()) { check(resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)==1) { "Could not finalize saved file." } };committed=true
  }finally { if(!committed) { if(pending(c,uri))resolver.delete(uri,null,null);Store.change(j.id) { it.pendingUri="" } } }
  folder(c,j.id).deleteRecursively()
 }
}
class MediaService:Service() {
 private val executor=Executors.newSingleThreadExecutor();private val main=Handler(Looper.getMainLooper());@Volatile private var stopping=false;private var wake:PowerManager.WakeLock?=null
 companion object {
  const val CHANNEL="downloadverse-media";const val STOP="stop-media";const val UPDATE="update-engine"
  @Volatile var busy=false;@Volatile var engineStatus=""
  fun start(c:Context,update:Boolean=false) { ContextCompat.startForegroundService(c,Intent(c,MediaService::class.java).apply { if(update)action=UPDATE }) }
  fun cancel(c:Context,j:Job) { Store.change(j.id) { if(it.state !in Job.terminal) { it.state=if(it.state=="queued")"cancelled" else "cancelling";it.message="Cancelling" } };runCatching { Engine.cancel(j.id) };if(Store.get(j.id)?.state=="cancelled")Publisher.cleanup(c,j.id) }
 }
 override fun onCreate() { super.onCreate();requireNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(NotificationChannel(CHANNEL,"Downloads",NotificationManager.IMPORTANCE_LOW)) }
 private fun note(text:String,p:Int=-1)=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_download).setContentTitle("DownloadVerse").setContentText(text).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,p.coerceAtLeast(0),p<0).setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).addAction(0,"Stop queue",PendingIntent.getService(this,1,Intent(this,MediaService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action==STOP) { stopQueue(false);stopSelf();return START_NOT_STICKY }
  startForeground(1001,note("Preparing downloads"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  if(busy)return START_NOT_STICKY;busy=true
  wake=requireNotNull(getSystemService(PowerManager::class.java)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"DownloadVerse:media").apply { setReferenceCounted(false);acquire(6L*60*60*1000) }
  if(intent?.action==UPDATE)executor.execute { engineStatus="Updating media engine…";try { Engine.update(this);engineStatus="Engine updated. Retry failed links if needed." }catch(e:Throwable) { engineStatus="Update failed: "+Rules.error(e.message.orEmpty()) }finally { main.post { finishWork() } } }
  else runQueue();return START_NOT_STICKY
 }
 private fun runQueue() { executor.execute {
  try { while(!stopping && !Thread.currentThread().isInterrupted) {
   val j=Store.next()?:break
   try {
    if(Publisher.recover(this,j))continue
    if(Publisher.stopped(j.id))throw InterruptedException("Stopped")
    val folder=Publisher.folder(this,j.id);check(folder.usableSpace>64L*1024*1024) { "Less than 64 MB free. Free storage before downloading." };var last=0L
    val output=Engine.download(this,j,folder) { p,phase->
     if(Publisher.stopped(j.id)) { Engine.cancel(j.id);throw InterruptedException("Stopped") }
     val now=System.currentTimeMillis();if(now-last>=1000) { last=now;Store.change(j.id) { if(it.state !in setOf("cancelled","cancelling","interrupted")) { it.state="downloading";it.percent=p;it.message=phase } }
      if(Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)try { requireNotNull(getSystemService(NotificationManager::class.java)).notify(1001,note(phase,p)) }catch(_:SecurityException){}
     }
    }
    if(Publisher.stopped(j.id))throw InterruptedException("Stopped");Publisher.export(this,Store.get(j.id)?:j,output)
   }catch(e:Throwable) {
    val state=Store.get(j.id)?.state
    when {
     state=="complete"->Unit
     state in setOf("cancelled","cancelling")->{runCatching { Publisher.cleanup(this,j.id) };Store.change(j.id) { it.state="cancelled";it.percent=-1;it.message="Cancelled; private temporary data removed" }}
     stopping || Thread.currentThread().isInterrupted || state=="interrupted"->Store.change(j.id) { it.state="interrupted";it.message="Android stopped this task. Reopen and Retry." }
     else->Store.change(j.id) { it.state="failed";it.percent=-1;it.message=if(e is LinkageError)"The bundled media engine could not load on this device." else Rules.error(e.message.orEmpty()) }
    }
   }
  } }finally { main.post { finishWork() } }
 } }
 private fun finishWork() { if(!stopping && Store.queued()) { runQueue();return };busy=false;if(wake?.isHeld==true)wake?.release();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
 private fun stopQueue(interrupted:Boolean) { stopping=true;Store.all().filter { it.mode!="file" && it.state !in Job.terminal }.forEach { j->Store.change(j.id) { it.state=if(interrupted)"interrupted" else "cancelled";it.message=if(interrupted)"Android stopped this task. Reopen and Retry." else "Cancelled from notification" };runCatching { Engine.cancel(j.id) };if(!interrupted && j.state=="queued")runCatching { Publisher.cleanup(this,j.id) } };executor.shutdownNow() }
 override fun onTimeout(startId:Int,fgsType:Int) { stopQueue(true);stopSelf() }
 override fun onDestroy() { if(busy && !stopping)stopQueue(true);stopping=true;busy=false;executor.shutdownNow();if(wake?.isHeld==true)wake?.release();super.onDestroy() }
 override fun onBind(intent:Intent?):IBinder?=null
}
class MainActivity:ComponentActivity() {
 private val io=Executors.newSingleThreadExecutor();private val main=Handler(Looper.getMainLooper())
 private lateinit var input:EditText;private lateinit var mode:Spinner;private lateinit var quality:Spinner;private lateinit var jobsView:LinearLayout;private lateinit var status:TextView
 private var active=false;private var polling=false;private var notified=false
 private var ink=Color.BLACK;private var muted=Color.DKGRAY;private var surface=Color.WHITE;private var border=Color.LTGRAY;private var blue=Color.BLUE
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
 private fun shape(color:Int)=GradientDrawable().apply { setColor(color);cornerRadius=dp(10).toFloat();setStroke(dp(1),border) }
 private fun text(parent:LinearLayout,value:String,size:Float=16f,bold:Boolean=false):TextView {
  val view=TextView(this).apply { this.text=value;textSize=size;setTextColor(ink);setLineSpacing(dp(3).toFloat(),1f);if(bold)setTypeface(typeface,Typeface.BOLD) }
  parent.addView(view,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) });return view
 }
 private fun button(parent:LinearLayout,label:String,primary:Boolean=false,action:()->Unit):Button {
  val b=Button(this).apply { text=label;isAllCaps=false;textSize=16f;minHeight=dp(48);setPadding(dp(14),dp(10),dp(14),dp(10));setTextColor(if(primary)Color.WHITE else blue);background=shape(if(primary)Color.rgb(36,107,203) else surface);setOnClickListener { action() } }
  parent.addView(b,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) });return b
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val dark=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
  ink=Color.parseColor(if(dark)"#F6F5F3" else "#2C2C2B");muted=Color.parseColor(if(dark)"#B4B2AC" else "#66645F");surface=Color.parseColor(if(dark)"#232323" else "#FFFFFF");border=Color.parseColor(if(dark)"#484845" else "#D7D5D1");blue=Color.parseColor(if(dark)"#8CBDFF" else "#246BCB")
  WindowCompat.setDecorFitsSystemWindows(window,false)
  val outer=column().apply { setBackgroundColor(Color.parseColor(if(dark)"#191919" else "#F9F8F7")) }
  WindowCompat.getInsetsController(window,outer).apply { isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark }
  ViewCompat.setOnApplyWindowInsetsListener(outer) { v,i->val bars=i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout());val ime=i.getInsets(WindowInsetsCompat.Type.ime());v.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom));i }
  val scroll=ScrollView(this);val body=column().apply { setPadding(dp(20),dp(24),dp(20),dp(24)) };scroll.addView(body);outer.addView(scroll,LinearLayout.LayoutParams(-1,-1));setContentView(outer)
  text(body,"DownloadVerse",28f,true);text(body,"Save videos, audio and files on your device.").setTextColor(muted)
  text(body,"Links — one per line",16f,true)
  input=EditText(this).apply { id=View.generateViewId();hint="https://…";textSize=16f;minLines=3;maxLines=8;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI;setTextColor(ink);setHintTextColor(muted);setPadding(dp(12),dp(12),dp(12),dp(12));background=shape(surface) }
  body.addView(input,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) });input.setText(savedInstanceState?.getString("draft").orEmpty())
  button(body,"Paste from clipboard") { try { input.setText(sharedText(getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty())) }catch(_:Exception) { toast("Long-press the link field and choose Paste.") } }
  text(body,"Download as",16f,true);mode=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Auto","Video","MP3 audio","Direct file"));minimumHeight=dp(48);setSelection(savedInstanceState?.getInt("mode",0)?:0) };body.addView(mode)
  text(body,"Video quality (media-site videos)",16f,true);quality=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Best available","Up to 1080p","Up to 720p","Up to 480p"));minimumHeight=dp(48);setSelection(savedInstanceState?.getInt("quality",0)?:0) };body.addView(quality)
  button(body,"Add to downloads",true) { enqueue() }
  text(body,"Saved to Downloads / DownloadVerse",14f,true)
  text(body,"Up to 20 links. Only save content you own or have permission to download. Auto detects direct files; other links use yt-dlp. Video keeps the source format; split streams merge to MKV. MP4 is not guaranteed.",14f).setTextColor(muted)
  text(body,"Downloads",24f,true);jobsView=column();body.addView(jobsView)
  text(body,"Storage and engines",24f,true)
  button(body,"Open Downloads") { try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }catch(_:Exception) { toast("Open Files > Downloads > DownloadVerse.") } }
  button(body,"Update yt-dlp") { confirm("Update media engine?","Fetch the latest stable engine from its upstream publisher. Finish or cancel media tasks first. Updates do not guarantee that every site works.") { if(MediaService.busy || Store.all().any { it.mode!="file" && it.state !in Job.terminal })toast("Finish or cancel media tasks first.") else { askNotifications();startMedia(emptyList(),true) } } }
  status=text(body,"",14f)
  text(body,"Preview build ${BuildConfig.VERSION_NAME}",16f,true)
  text(body,"No remote download server, login, advertising or analytics. Downloads may use mobile data. Allow space for temporary tracks and a final copy. After force-stop/reboot or Android background limits, reopen the app and retry interrupted media tasks.",14f).setTextColor(muted)
  text(body,"Not ported: desktop Studio editing, clip-only transfer, gallery-dl post extraction, authenticated cookies or full playlists. No DRM or site-restriction bypass. Native compilation, rendering and real-device downloads were not verified here; see the source validation checklist.",14f).setTextColor(muted)
  text(body,"GPL-3.0-or-later. Preserve dependency notices and required corresponding source when distributing APKs.",14f).setTextColor(muted)
  acceptShare(intent);renderJobs(Store.all());if(Store.notice.isNotEmpty())toast(Store.notice)
 }
 private val poll=object:Runnable { override fun run() { if(!active || io.isShutdown)return;if(!polling) { polling=true;io.execute { val result=runCatching { Direct.refresh(this@MainActivity);Store.all() };main.post { polling=false;if(active) { result.onSuccess { renderJobs(it) };status.text=MediaService.engineStatus.ifBlank { "Bundled yt-dlp/Python and FFmpeg. Engine updates require internet." } } } } };main.postDelayed(this,1500) } }
 private fun askNotifications() { if(Build.VERSION.SDK_INT>=33 && !notified && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) { notified=true;requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),50) } }
 private fun enqueue() {
  val urls=try { Rules.batch(input.text.toString()) }catch(e:Exception) { toast(e.message.orEmpty());return }
  val selected=arrayOf("auto","video","audio","file")[mode.selectedItemPosition];val cap=arrayOf("best","1080","720","480")[quality.selectedItemPosition]
  val run={ askNotifications();io.execute {
   val result=runCatching { val rows=Store.add(urls,selected,cap);rows.filter { it.mode=="file" }.forEach { j->try { Direct.enqueue(this,j) }catch(e:Exception) { Store.change(j.id) { it.state="failed";it.message=Rules.error(e.message.orEmpty()) } } };rows }
   main.post { result.onSuccess { rows->if(rows.any { it.mode!="file" })startMedia(rows);input.setText("");renderJobs(Store.all());toast(if(rows.isEmpty())"Those links are already active." else "${rows.size} task(s) added. Check the queue for status.") }.onFailure { toast(Rules.error(it.message.orEmpty())) } }
  } }
  if(urls.any { it.startsWith("http:") })confirm("Unencrypted HTTP link","HTTP traffic is not encrypted. Continue only if you trust this source.",run) else run()
 }
 private fun startMedia(rows:List<Job>,update:Boolean=false) { try { check(active && !isFinishing) { "Reopen DownloadVerse and Retry." };MediaService.start(this,update) }catch(e:Exception) { rows.filter { it.mode!="file" }.forEach { j->Store.change(j.id) { it.state="interrupted";it.message=Rules.error(e.message.orEmpty()) } };toast(Rules.error(e.message.orEmpty())) } }
 private fun renderJobs(rows:List<Job>) {
  // Avoid rebuilding identical rows and stealing TalkBack/touch focus unnecessarily.
  val fingerprint=rows.map { it.json().toString() }.joinToString()
  if(jobsView.tag==fingerprint)return;jobsView.tag=fingerprint;jobsView.removeAllViews()
  if(rows.isEmpty()) { text(jobsView,"No tasks yet. Add a link above.").setTextColor(muted);return }
  rows.sortedByDescending { it.created }.forEach { j->
   val card=column().apply { setPadding(dp(16),dp(16),dp(16),dp(8));background=shape(surface) }
   text(card,j.title,18f,true).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END }
   text(card,(runCatching { URI(j.url).host }.getOrNull().orEmpty())+" · "+j.mode,14f).setTextColor(muted)
   text(card,j.state.replaceFirstChar { it.uppercase() }+(if(j.percent>=0 && j.state !in Job.terminal)" · ${j.percent}%" else ""),16f,true)
   if(j.state !in Job.terminal)card.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100;progress=j.percent.coerceAtLeast(0);isIndeterminate=j.percent<0 },LinearLayout.LayoutParams(-1,dp(8)).apply { bottomMargin=dp(12) })
   text(card,j.message,14f).setTextColor(muted)
   if(j.state=="complete") { button(card,"Open file") { openFile(j,false) };button(card,"Share") { openFile(j,true) } }
   else if(j.state in Job.terminal)button(card,"Retry") { background {
    if(j.mode!="file" && Publisher.recover(this,j))return@background
    val old=Store.retry(j.id)
    if(old.mode=="file") { if(old.systemId>=0)Direct.manager(this).remove(old.systemId);try { Direct.enqueue(this,Store.get(j.id)!!) }catch(e:Exception) { Store.change(j.id) { it.state="failed";it.message=Rules.error(e.message.orEmpty()) };throw e } }
    else main.post { startMedia(listOf(j)) }
   } }
   else if(j.state!="cancelling")button(card,"Cancel") { confirm("Cancel download?","This stops the task and removes its private temporary files. You can retry later.") { background { if(j.mode=="file")Direct.cancel(this,j) else MediaService.cancel(this,j) } } }
   if(j.state in Job.terminal)button(card,"Remove history") { confirm("Remove history item?","Completed public files are kept. Private unfinished files for this task are removed.") { background { if(j.mode!="file")Publisher.cleanup(this,j.id) else if(j.state!="complete" && j.systemId>=0)Direct.manager(this).remove(j.systemId);Store.remove(j.id) } } }
   jobsView.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(16) })
  }
 }
 private fun background(work:()->Unit) { if(io.isShutdown)return;io.execute { val result=runCatching(work);main.post { if(!isDestroyed) { result.onFailure { toast(Rules.error(it.message.orEmpty())) };renderJobs(Store.all()) } } } }
 private fun openFile(j:Job,share:Boolean) { background {
  require(j.state=="complete" && j.uri.startsWith("content://")) { "This file is not ready." };val uri=Uri.parse(j.uri)
  contentResolver.openFileDescriptor(uri,"r")?.use {}?:throw IllegalStateException("The file was moved or deleted.")
  main.post { try { val intent=if(share)Intent(Intent.ACTION_SEND).apply { type=j.mime;putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newUri(contentResolver,j.title,uri) } else Intent(Intent.ACTION_VIEW).setDataAndType(uri,j.mime);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(if(share)Intent.createChooser(intent,"Share downloaded file") else intent) }catch(_:Exception) { toast("No installed app can open this file type. Try your Files app.") } }
 } }
 private fun confirm(title:String,message:String,action:()->Unit) { AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Go back",null).setPositiveButton("Continue") { _,_->action() }.show() }
 private fun toast(message:String) { if(!isDestroyed)Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
 private fun sharedText(text:String):String { val m=Patterns.WEB_URL.matcher(text);val links=mutableListOf<String>();while(m.find() && links.size<20) { val raw=m.group().orEmpty();if(raw.startsWith("https://") || raw.startsWith("http://"))links.add(raw.trimEnd(')',',','.',']','}')) };return if(links.isEmpty())text.take(180000) else links.joinToString(System.lineSeparator()) }
 private fun acceptShare(intent:Intent?) { if(intent?.action==Intent.ACTION_SEND && intent.type=="text/plain")input.setText(sharedText(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())) }
 override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);setIntent(intent);acceptShare(intent) }
 override fun onSaveInstanceState(outState:Bundle) { outState.putString("draft",input.text.toString());outState.putInt("mode",mode.selectedItemPosition);outState.putInt("quality",quality.selectedItemPosition);super.onSaveInstanceState(outState) }
 override fun onResume() { super.onResume();active=true;main.post(poll) }
 override fun onPause() { active=false;main.removeCallbacks(poll);super.onPause() }
 override fun onDestroy() { active=false;main.removeCallbacks(poll);io.shutdown();super.onDestroy() }
}
