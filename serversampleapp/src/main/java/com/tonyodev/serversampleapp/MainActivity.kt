package com.tonyodev.serversampleapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import com.tonyodev.fetch2.*
import com.tonyodev.fetch2.util.getFileMd5String
import com.tonyodev.fetch2fileserver.FetchFileServerDownloader
import com.tonyodev.fetch2fileserver.*
import com.tonyodev.fetch2fileserver.ContentFile
import java.io.File

class MainActivity : AppCompatActivity() {

    private val storage_code = 100
    private lateinit var fetchContentFileServer: FetchFileServer
    private lateinit var fetch: Fetch
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textView = findViewById(R.id.textView)
        checkStoragePermission()
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), storage_code)
        } else {
            startSampleTest()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        if (requestCode == storage_code && grantResults.isNotEmpty()
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSampleTest()
        } else {
            Toast.makeText(this, "Permission not accepted", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    @SuppressLint("LogNotTimber")
    private fun startSampleTest() {
        Thread({
            fetchContentFileServer = FetchFileServer.Builder(this)
                    .setProgressListener(object : ContentFileProgressListener {
                        override fun onProgress(client: String, contentFile: ContentFile, progress: Int) {
                            Log.d("onServerProgress", "$progress%")
                        }
                    })
                    .build()
            fetchContentFileServer.start()
            //TODO: FILE MUST BE IN DIRECTORY FOR THIS TO WORK OR TEST WILL FAIL
            val file = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/bunnyMovie.m4v"
            val contentFile = ContentFile()
            contentFile.id = 45
            contentFile.file = file
            contentFile.length = File(file).length()
            contentFile.name = "bunnyMovie.m4v"
            contentFile.customData = "{}"
            contentFile.md5 = getFileMd5String(file) ?: ""
            fetchContentFileServer.removeAllContentFiles()
            fetchContentFileServer.addContentFile(contentFile)
            fetch = Fetch.Builder(this, "MyFetch")
                    .setFetchFileServerDownloader(FetchFileServerDownloader(Downloader.FileDownloaderType.PARALLEL))
                    .setDownloadConcurrentLimit(1)
                    .enableLogging(true)
                    .enableRetryOnNetworkGain(false)
                    .addRequestOptions(RequestOptions.ADD_AUTO_INCREMENT_TO_FILE_ON_ENQUEUE)
                    .build()
            fetch.deleteAll()
            fetch.addListener(object : AbstractFetchListener() {

                override fun onQueued(download: Download) {
                    super.onQueued(download)
                    Toast.makeText(this@MainActivity, "Download Started", Toast.LENGTH_SHORT).show()
                }

                override fun onCompleted(download: Download) {
                    Log.d("FetchListener", "onCompleted $download")
                    Toast.makeText(this@MainActivity, "Download Completed", Toast.LENGTH_SHORT).show()
                }

                override fun onError(download: Download) {
                    Log.d("FetchListener", "onError $download")
                    val errorString = "Error ${download.error}"
                    textView.text = errorString
                }

                override fun onProgress(download: Download, etaInMilliSeconds: Long, downloadedBytesPerSecond: Long) {
                    Log.d("FetchListener", "onProgress ${download.progress}%      ${download.url}")
                    val progressString = "progress ${download.progress}%"
                    textView.text = progressString
                }
            })
            var host = fetchContentFileServer.address
            if (host.isEmpty() || host.contentEquals("::")) {
                host = "127.0.0.1"
            }
            val url = Uri.Builder()
                    .scheme("fetchlocal")
                    .encodedAuthority("$host:${fetchContentFileServer.port}")
                    .path("bunnyMovie.m4v")
                    .build().toString()
            val requestFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/testMovie.m4v"
            val request = Request(url, requestFile)
            fetch.enqueue(request, object : Func<Download> {
                override fun call(download: Download) {
                    Log.d("MainEnqueue", "$download")
                }
            }, object : Func<Error> {
                override fun call(error: Error) {
                    Log.d("MainError", "$error")
                }
            })
        }).start()
    }

    override fun onDestroy() {
        super.onDestroy()
        fetchContentFileServer.shutDown(true)
    }

}
