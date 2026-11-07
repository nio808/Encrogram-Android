// JNI declarations for the prebuilt SimpleX glue library (libapp-lib.so).
//
// The glue exports its functions as Java_chat_simplex_common_platform_CoreKt_*, so these declarations must live
// in a file named Core.kt in package chat.simplex.common.platform, with signatures identical to the upstream
// file (reference/simplex-chat/apps/multiplatform/common/src/commonMain/kotlin/chat/simplex/common/platform/Core.kt).
// Do not rename this file, add @file:JvmName, or move it.
//
// Internal to :encrogram-core. Only app.encrogram.core.internal.NativeCore and the engine call these.
// SimpleX Chat is AGPL v3; see the module README for licence notes.
package chat.simplex.common.platform

import java.nio.ByteBuffer

// ghc's rts. Called once per process, after System.loadLibrary("app-lib").
external fun initHS()

// android-support. Redirects the whole process's stdout/stderr into a local socket.
// Encrogram never calls it: in our combined app it would also capture Telegram's output (no-logging rule).
external fun pipeStdOutToSocket(socketName: String): Int

// SimpleX API
typealias ChatCtrl = Long

/** Returns [migrationResultJson: String, ctrl: Long]. */
external fun chatMigrateInit(dbPath: String, dbKey: String, confirm: String): Array<Any>
external fun chatCloseStore(ctrl: ChatCtrl): String
external fun chatSendCmdRetry(ctrl: ChatCtrl, msg: String, retryNum: Int): String
external fun chatSendRemoteCmdRetry(ctrl: ChatCtrl, rhId: Int, msg: String, retryNum: Int): String
external fun chatRecvMsg(ctrl: ChatCtrl): String
/** Blocks up to [timeout] microseconds; returns "" on timeout. */
external fun chatRecvMsgWait(ctrl: ChatCtrl, timeout: Int): String
external fun chatParseMarkdown(str: String): String
external fun chatParseServer(str: String): String
external fun chatParseUri(str: String, safe: Int): String
external fun chatPasswordHash(pwd: String, salt: String): String
external fun chatValidName(name: String): String
external fun chatJsonLength(str: String): Int
external fun chatWriteFile(ctrl: ChatCtrl, path: String, buffer: ByteBuffer): String
external fun chatReadFile(path: String, key: String, nonce: String): Array<Any>
external fun chatEncryptFile(ctrl: ChatCtrl, fromPath: String, toPath: String): String
external fun chatDecryptFile(fromPath: String, key: String, nonce: String, toPath: String): String
