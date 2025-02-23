package io.legado.app.lib.webdav

import io.legado.app.constant.AppLog
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.printOnDebug
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.intellij.lang.annotations.Language
import org.jsoup.Jsoup

import java.io.File
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder

@Suppress("unused", "MemberVisibilityCanBePrivate")
class WebDav(urlStr: String) {
    companion object {
        // 指定返回哪些属性
        @Language("xml")
        private const val DIR =
            """<?xml version="1.0"?>
            <a:propfind xmlns:a="DAV:">
                <a:prop>
                    <a:displayname/>
                    <a:resourcetype/>
                    <a:getcontentlength/>
                    <a:creationdate/>
                    <a:getlastmodified/>
                    %s
                </a:prop>
            </a:propfind>"""
    }

    private val url: URL = URL(urlStr)
    private val httpUrl: String? by lazy {
        val raw = url.toString().replace("davs://", "https://").replace("dav://", "http://")
        return@lazy kotlin.runCatching {
            URLEncoder.encode(raw, "UTF-8")
                .replace("\\+".toRegex(), "%20")
                .replace("%3A".toRegex(), ":")
                .replace("%2F".toRegex(), "/")
        }.getOrNull()
    }
    val host: String? get() = url.host
    val path get() = url.toString()
    var displayName: String? = null
    var size: Long = 0
    var parent = ""
    var urlName = ""
    var contentType = ""

    /**
     * 填充文件信息。实例化WebDAVFile对象时，并没有将远程文件的信息填充到实例中。需要手动填充！
     * @return 远程文件是否存在
     */
    suspend fun indexFileInfo(): Boolean {
        return !propFindResponse(ArrayList()).isNullOrEmpty()
    }

    /**
     * 列出当前路径下的文件
     *
     * @return 文件列表
     */
    suspend fun listFiles(): List<WebDav> {
        propFindResponse()?.let { body ->
            return parseDir(body)
        }
        return ArrayList()
    }

    /**
     * @param propsList 指定列出文件的哪些属性
     */
    private suspend fun propFindResponse(propsList: List<String> = emptyList()): String? {
        val requestProps = StringBuilder()
        for (p in propsList) {
            requestProps.append("<a:").append(p).append("/>\n")
        }
        val requestPropsStr: String = if (requestProps.toString().isEmpty()) {
            DIR.replace("%s", "")
        } else {
            String.format(DIR, requestProps.toString() + "\n")
        }
        val url = httpUrl
        val auth = HttpAuth.auth
        if (url != null && auth != null) {
            return kotlin.runCatching {
                okHttpClient.newCallResponseBody {
                    url(url)
                    addHeader("Authorization", Credentials.basic(auth.user, auth.pass))
                    addHeader("Depth", "1")
                    // 添加RequestBody对象，可以只返回的属性。如果设为null，则会返回全部属性
                    // 注意：尽量手动指定需要返回的属性。若返回全部属性，可能后由于Prop.java里没有该属性名，而崩溃。
                    val requestBody = requestPropsStr.toRequestBody("text/plain".toMediaType())
                    method("PROPFIND", requestBody)
                }.text()
            }.onFailure { e ->
                e.printOnDebug()
            }.getOrNull()
        }
        return null
    }

    private fun parseDir(s: String): List<WebDav> {
        val list = ArrayList<WebDav>()
        val document = Jsoup.parse(s)
        val elements = document.getElementsByTag("d:response")
        httpUrl?.let { urlStr ->
            val baseUrl = if (urlStr.endsWith("/")) urlStr else "$urlStr/"
            for (element in elements) {
                val href = element.getElementsByTag("d:href")[0].text()
                if (!href.endsWith("/")) {
                    val fileName = href.substring(href.lastIndexOf("/") + 1)
                    val webDavFile: WebDav
                    try {
                        webDavFile = WebDav(baseUrl + fileName)
                        webDavFile.displayName = fileName
                        webDavFile.contentType = element
                            .getElementsByTag("d:getcontenttype")
                            .getOrNull(0)?.text() ?: ""
                        if (href.isEmpty()) {
                            webDavFile.urlName =
                                if (parent.isEmpty()) url.file.replace("/", "")
                                else url.toString().replace(parent, "").replace("/", "")
                        } else {
                            webDavFile.urlName = href
                        }
                        list.add(webDavFile)
                    } catch (e: MalformedURLException) {
                        e.printOnDebug()
                    }
                }
            }
        }
        return list
    }

    /**
     * 文件是否存在
     */
    suspend fun exists(): Boolean {
        val response = propFindResponse() ?: return false
        val document = Jsoup.parse(response)
        val elements = document.getElementsByTag("d:response")
        return elements.isNotEmpty()
    }

    /**
     * 根据自己的URL，在远程处创建对应的文件夹
     * @return 是否创建成功
     */
    suspend fun makeAsDir(): Boolean {
        val url = httpUrl
        val auth = HttpAuth.auth
        if (url != null && auth != null) {
            //防止报错
            return kotlin.runCatching {
                if (!exists()) {
                    okHttpClient.newCallResponseBody {
                        url(url)
                        method("MKCOL", null)
                        addHeader("Authorization", Credentials.basic(auth.user, auth.pass))
                    }.close()
                }
            }.onFailure {
                AppLog.put(it.localizedMessage)
            }.isSuccess
        }
        return false
    }

    /**
     * 下载到本地
     *
     * @param savedPath       本地的完整路径，包括最后的文件名
     * @param replaceExisting 是否替换本地的同名文件
     * @return 下载是否成功
     */
    suspend fun downloadTo(savedPath: String, replaceExisting: Boolean): Boolean {
        if (File(savedPath).exists()) {
            if (!replaceExisting) return false
        }
        val inputS = getInputStream() ?: return false
        File(savedPath).writeBytes(inputS.readBytes())
        return true
    }

    suspend fun download(): ByteArray? {
        val inputS = getInputStream() ?: return null
        return inputS.readBytes()
    }

    /**
     * 上传文件
     */
    suspend fun upload(
        localPath: String,
        contentType: String = "application/octet-stream"
    ): Boolean {
        val file = File(localPath)
        if (!file.exists()) return false
        // 务必注意RequestBody不要嵌套，不然上传时内容可能会被追加多余的文件信息
        val fileBody = file.asRequestBody(contentType.toMediaType())
        val url = httpUrl
        val auth = HttpAuth.auth
        if (url != null && auth != null) {
            return kotlin.runCatching {
                okHttpClient.newCallResponseBody {
                    url(url)
                    put(fileBody)
                    addHeader("Authorization", Credentials.basic(auth.user, auth.pass))
                }.close()
            }.isSuccess
        }
        return false
    }

    suspend fun upload(byteArray: ByteArray, contentType: String): Boolean {
        // 务必注意RequestBody不要嵌套，不然上传时内容可能会被追加多余的文件信息
        val fileBody = byteArray.toRequestBody(contentType.toMediaType())
        val url = httpUrl
        val auth = HttpAuth.auth
        if (url != null && auth != null) {
            return kotlin.runCatching {
                okHttpClient.newCallResponseBody {
                    url(url)
                    put(fileBody)
                    addHeader("Authorization", Credentials.basic(auth.user, auth.pass))
                }.close()
            }.isSuccess
        }
        return false
    }

    private suspend fun getInputStream(): InputStream? {
        val url = httpUrl
        val auth = HttpAuth.auth
        if (url != null && auth != null) {
            return kotlin.runCatching {
                okHttpClient.newCallResponseBody {
                    url(url)
                    addHeader("Authorization", Credentials.basic(auth.user, auth.pass))
                }.byteStream()
            }.getOrNull()
        }
        return null
    }

}