package com.hermes.client.ui
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.HermesApp
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.ui.screens.ChatScreen
import com.hermes.client.ui.screens.ConversationDrawerContent
import com.hermes.client.ui.screens.SettingsScreen
import com.hermes.client.ui.theme.HermesAppTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],qualifiers="en-rUS-w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SiteScreenshotCaptureTest {
 @get:Rule val compose = createComposeRule()
 @Test fun chatEnglish() = capture("en","chat")
 @Test @Config(qualifiers="ja-rJP-w412dp-h892dp-xhdpi") fun chatJapanese() = capture("ja","chat")
 @Test fun sessionsEnglish() = capture("en","drawer")
 @Test @Config(qualifiers="ja-rJP-w412dp-h892dp-xhdpi") fun sessionsJapanese() = capture("ja","drawer")
 @Test fun settingsEnglish() = capture("en","settings")
 @Test @Config(qualifiers="ja-rJP-w412dp-h892dp-xhdpi") fun settingsJapanese() = capture("ja","settings")
 private fun capture(locale:String, screen:String) {
  val title=if(locale=="ja") "公開チェックリスト" else "Release checklist"
  val question=if(locale=="ja") "公開前に確認することを整理して。" else "What should I check before the release?"
  val answer=if(locale=="ja") "## 公開前の確認\n\n- アプリの動作と通知を確認\n- ライセンスとプライバシーを確認\n- インストール手順を用意\n\nまずは実機で会話と画像送信を試しましょう。" else "## Release checklist\n\n- Test chat, images and notifications\n- Review licenses and privacy\n- Write the installation steps\n\nStart by testing chat and image uploads on an Android device."
  fun escape(s:String)=s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")
  val server=MockWebServer()
  server.dispatcher=object:Dispatcher(){
   override fun dispatch(request:RecordedRequest):MockResponse {
    val body=if(request.path.orEmpty().contains("/messages")) """{"messages":[{"role":"user","content":"${escape(question)}"},{"role":"assistant","content":"${escape(answer)}"}]}""" else """{"sessions":[{"id":"site-demo","title":"${escape(title)}","updated_at":1794300000},{"id":"api-demo","title":"${if(locale=="ja") "API連携" else "API integration"}","updated_at":1794290000},{"id":"weekend-demo","title":"${if(locale=="ja") "週末の予定" else "Weekend plans"}","updated_at":1794200000}]}"""
    return MockResponse().setBody(body).setHeader("Content-Type","application/json")
   }
  }
  server.start()
  val app=ApplicationProvider.getApplicationContext<Application>() as HermesApp
  val url=if(screen=="settings") "https://hermes.example.com" else server.url("/").toString().trimEnd('/')
  try {
   runBlocking {
    app.container.settingsStore.save(ClientSettings(baseUrl=url,apiKey="public-screenshot-example",language=locale))
    repeat(100) { if(app.container.settingsStore.current().baseUrl==url) return@runBlocking; delay(20) }
    error("Settings did not load")
   }
   lateinit var renderedView: android.view.View
   compose.setContent { renderedView = androidx.compose.ui.platform.LocalView.current; HermesAppTheme(darkTheme=true) {
    when(screen) {
     "chat" -> ChatScreen(sessionId="site-demo",title=title,onOpenDrawer={})
     "drawer" -> {
      val drawer=androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Open)
      androidx.compose.material3.ModalNavigationDrawer(drawerState=drawer,drawerContent={
       ConversationDrawerContent(selectedSessionId="site-demo",drawerState=drawer,onOpenChat={_,_->},onOpenSearch={},onOpenSettings={},onCloseDrawer={})
      }) { ChatScreen(sessionId="site-demo",title=title,onOpenDrawer={}) }
     }
     else -> SettingsScreen(onNavigateBack={})
    }
   } }
   if(screen!="settings") compose.waitUntil(15000) { compose.onAllNodesWithText(if(screen=="chat") question else if(locale=="ja") "API連携" else "API integration",substring=true).fetchSemanticsNodes().isNotEmpty() }
   compose.waitForIdle()
   val file=File(System.getProperty("yorube.capture.output"),"$locale-$screen.png")
   file.parentFile!!.mkdirs()
   compose.runOnIdle {
    val bitmap=Bitmap.createBitmap(renderedView.width,renderedView.height,Bitmap.Config.ARGB_8888)
    renderedView.draw(android.graphics.Canvas(bitmap))
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
   }
  } finally { compose.runOnUiThread { app.container.runStreams.close() }; server.shutdown() }
 }
}
