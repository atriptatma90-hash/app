package app.downloadverse.android
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketTimeoutException
@RunWith(AndroidJUnit4::class)
class SmokeTest {
 private val context:Context get()=ApplicationProvider.getApplicationContext()
 @Test fun launchesNativeActivity() { ActivityScenario.launch(MainActivity::class.java).use { scenario->scenario.onActivity { assertNotNull(it.findViewById<android.view.View>(android.R.id.content)) } } }
 @Test fun initializesBundledEngines() { Engine.initialize(context) }
 @Test fun downloadsExactBytes() {
  val bytes="DownloadVerse local smoke test".toByteArray()
  Fixture(bytes).use { server->
   val job=Store.add(listOf(server.url),"file","best").single()
   try {
    Direct.enqueue(context,job);val deadline=System.currentTimeMillis()+45000;var result:Job
    do { Direct.refresh(context);result=Store.get(job.id)!!;if(result.state in Job.terminal)break;Thread.sleep(250) }while(System.currentTimeMillis()<deadline)
    assertEquals(result.message,"complete",result.state)
    val saved=context.contentResolver.openInputStream(Uri.parse(result.uri))!!.use { it.readBytes() };assertArrayEquals(bytes,saved)
   }finally { Store.get(job.id)?.let { if(it.systemId>=0)Direct.manager(context).remove(it.systemId) };Store.change(job.id) { it.state="cancelled" };Store.remove(job.id) }
  }
 }
 private class Fixture(private val bytes:ByteArray):AutoCloseable {
  private val socket=ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout=500 };@Volatile private var closed=false
  val url="http://127.0.0.1:${socket.localPort}/fixture.txt"
  private val thread=Thread {
   while(!closed)try { socket.accept().use { client->client.soTimeout=5000;val reader=BufferedReader(InputStreamReader(client.getInputStream(),Charsets.US_ASCII));val head=reader.readLine().orEmpty().startsWith("HEAD ");while(true) { val line=reader.readLine();if(line.isNullOrEmpty())break };val crlf=13.toChar().toString()+10.toChar();val headers=listOf("HTTP/1.1 200 OK","Content-Type: text/plain","Content-Length: ${bytes.size}","Connection: close","","").joinToString(crlf);client.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII));if(!head)write(bytes);flush() } } }catch(_:SocketTimeoutException){}catch(e:Exception) { if(!closed)throw e }
  }.apply { isDaemon=true;start() }
  override fun close() { closed=true;socket.close();thread.join(2000) }
 }
}
