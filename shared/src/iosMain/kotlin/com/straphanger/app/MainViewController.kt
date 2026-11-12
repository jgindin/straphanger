package com.straphanger.app

import androidx.compose.ui.window.ComposeUIViewController
import com.straphanger.app.data.local.FileRidePersistenceBackend
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
fun MainViewController() = ComposeUIViewController {
  val documents =
    NSFileManager.defaultManager
      .URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
      )
      ?.path ?: error("Documents directory is not available")
  val version =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
  App(FileRidePersistenceBackend(Path(documents, "rides.json")), appVersion = version)
}
