package app.downloadverse.android
import org.junit.Assert.*
import org.junit.Test
class RulesTest {
 private fun reject(s:String) { try { Rules.url(s);fail("Should reject $s") }catch(_:IllegalArgumentException){} }
 @Test fun validatesHttps() { assertEquals("https://example.org/a?b=1",Rules.url(" https://example.org/a?b=1#fragment ")) }
 @Test fun rejectsNonNetworkSchemes() { listOf("file:///etc/passwd","javascript:alert(1)","ftp://example.org/a","--exec=sh").forEach(::reject) }
 @Test fun rejectsCredentials() { reject("https://user:secret@example.org/a") }
 @Test fun rejectsWhitespace() { reject("https://example.org/a b");reject("https://example.org/a"+10.toChar()+"b") }
 @Test fun deduplicates() { assertEquals(1,Rules.batch(listOf("https://example.org/a","https://example.org/a").joinToString(System.lineSeparator())).size) }
 @Test fun capsBatch() { try { Rules.batch((1..21).joinToString(System.lineSeparator()) { "https://example.org/$it" });fail() }catch(_:IllegalArgumentException){} }
 @Test fun choosesFiles() { assertEquals("file",Rules.mode("https://example.org/file.MP4?token=1","auto")) }
 @Test fun honorsAudioMode() { assertEquals("audio",Rules.mode("https://example.org/file.mp4","audio")) }
 @Test fun safeNames() { assertFalse(Rules.name("../a:b.mp4").contains('/')) }
 @Test fun redactsLinks() { assertFalse(Rules.error("ERROR https://example.org/a?token=secret").contains("secret")) }
}
