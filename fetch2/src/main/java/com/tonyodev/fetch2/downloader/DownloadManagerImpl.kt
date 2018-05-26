package com.tonyodev.fetch2.downloader

import android.os.Handler
import com.tonyodev.fetch2.*
import com.tonyodev.fetch2.exception.FetchException
import com.tonyodev.fetch2.exception.FetchImplementationException
import com.tonyodev.fetch2.helper.DownloadInfoUpdater
import com.tonyodev.fetch2.helper.FileDownloaderDelegate
import com.tonyodev.fetch2.provider.ListenerProvider
import com.tonyodev.fetch2.provider.NetworkInfoProvider
import com.tonyodev.fetch2.util.getRequestForDownload
import com.tonyodev.fetch2.util.isFetchFileServerUrl
import com.tonyodev.fetch2.util.toDownloadInfo
import java.util.concurrent.Executors

class DownloadManagerImpl(private val downloader: Downloader,
                          private val concurrentLimit: Int,
                          private val progressReportingIntervalMillis: Long,
                          private val downloadBufferSizeBytes: Int,
                          private val logger: Logger,
                          private val networkInfoProvider: NetworkInfoProvider,
                          private val retryOnNetworkGain: Boolean,
                          private val fetchListenerProvider: ListenerProvider,
                          private val uiHandler: Handler,
                          private val downloadInfoUpdater: DownloadInfoUpdater,
                          private val requestOptions: Set<RequestOptions>,
                          private val fileTempDir: String,
                          private val fetchFileServerDownloader: Downloader?) : DownloadManager {

    private val lock = Object()
    private val executor = Executors.newFixedThreadPool(concurrentLimit)
    private val currentDownloadsMap = hashMapOf<Int, FileDownloader>()
    @Volatile
    private var downloadCounter = 0
    @Volatile
    private var closed = false
    override val isClosed: Boolean
        get() = closed

    override fun start(download: Download): Boolean {
        return synchronized(lock) {
            throwExceptionIfClosed()
            if (currentDownloadsMap.containsKey(download.id)) {
                logger.d("DownloadManager already running download $download")
                return false
            }
            if (downloadCounter >= concurrentLimit) {
                logger.d("DownloadManager cannot init download $download because " +
                        "the download queue is full")
                return false
            }
            val delegate = getFileDownloaderDelegate()
            try {
                val fileDownloader = getNewFileDownloaderForDownload(download)
                if (fileDownloader != null) {
                    fileDownloader.delegate = delegate
                    downloadCounter += 1
                    currentDownloadsMap[download.id] = fileDownloader
                    try {
                        executor.execute {
                            logger.d("DownloadManager starting download $download")
                            fileDownloader.run()
                            synchronized(lock) {
                                if (currentDownloadsMap.containsKey(download.id)) {
                                    currentDownloadsMap.remove(download.id)
                                    downloadCounter -= 1
                                }
                            }
                        }
                        true
                    } catch (e: Exception) {
                        logger.e("DownloadManager failed to start download $download", e)
                        false
                    }
                } else {
                    val downloadInfo = download.toDownloadInfo()
                    downloadInfo.error = Error.FETCH_FILE_SERVER_DOWNLOADER_NOT_SET
                    delegate.onError(downloadInfo)
                    false
                }
            } catch (e: Exception) {
                val downloadInfo = download.toDownloadInfo()
                downloadInfo.error = Error.FETCH_FILE_SERVER_URL_INVALID
                delegate.onError(downloadInfo)
                false
            }
        }
    }

    override fun cancel(id: Int): Boolean {
        synchronized(lock) {
            throwExceptionIfClosed()
            return if (currentDownloadsMap.containsKey(id)) {
                val fileDownloader = currentDownloadsMap[id] as FileDownloader
                fileDownloader.interrupted = true
                while (!fileDownloader.terminated) {
                    //Wait until download runnable terminates
                }
                currentDownloadsMap.remove(id)
                downloadCounter -= 1
                logger.d("DownloadManager cancelled download ${fileDownloader.download}")
                true
            } else {
                false
            }
        }
    }

    override fun cancelAll() {
        synchronized(lock) {
            throwExceptionIfClosed()
            cancelAllDownloads()
        }
    }

    private fun cancelAllDownloads() {
        currentDownloadsMap.iterator().forEach {
            it.value.interrupted = true
            while (!it.value.terminated) {
                //Wait until download runnable terminates
            }
            logger.d("DownloadManager cancelled download ${it.value.download}")
        }
        currentDownloadsMap.clear()
        downloadCounter = 0
    }

    override fun terminateAllDownloads() {
        currentDownloadsMap.iterator().forEach {
            it.value.terminated = true
            logger.d("DownloadManager terminated download ${it.value.download}")
        }
        currentDownloadsMap.clear()
        downloadCounter = 0
        try {
            downloader.close()
        } catch (e: Exception) {
            logger.e("DownloadManager closing downloader", e)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            terminateAllDownloads()
            logger.d("DownloadManager closing download manager")
            executor.shutdown()
        }
    }

    override fun contains(id: Int): Boolean {
        synchronized(lock) {
            throwExceptionIfClosed()
            return currentDownloadsMap.containsKey(id)
        }
    }

    override fun canAccommodateNewDownload(): Boolean {
        synchronized(lock) {
            throwExceptionIfClosed()
            return downloadCounter < concurrentLimit
        }
    }

    override fun getActiveDownloadCount(): Int {
        synchronized(lock) {
            throwExceptionIfClosed()
            return downloadCounter
        }
    }

    override fun getDownloads(): List<Download> {
        synchronized(lock) {
            throwExceptionIfClosed()
            return currentDownloadsMap.values.map { it.download }
        }
    }

    private fun throwExceptionIfClosed() {
        if (closed) {
            throw FetchImplementationException("DownloadManager is already shutdown.",
                    FetchException.Code.CLOSED)
        }
    }

    override fun getNewFileDownloaderForDownload(download: Download): FileDownloader? {
        val request = getRequestForDownload(download)
        return if (!isFetchFileServerUrl(request.url)) {
            getFileDownloader(request, download, downloader)
        } else if (fetchFileServerDownloader != null) {
            getFileDownloader(request, download, fetchFileServerDownloader)
        } else {
            null
        }
    }

    private fun getFileDownloader(request: Downloader.Request, download: Download, downloader: Downloader): FileDownloader {
        return if (downloader.getFileDownloaderType(request) == Downloader.FileDownloaderType.SEQUENTIAL) {
            SequentialFileDownloaderImpl(
                    initialDownload = download,
                    downloader = downloader,
                    progressReportingIntervalMillis = progressReportingIntervalMillis,
                    downloadBufferSizeBytes = downloadBufferSizeBytes,
                    logger = logger,
                    networkInfoProvider = networkInfoProvider,
                    retryOnNetworkGain = retryOnNetworkGain)
        } else {
            val tempDir = downloader.getDirectoryForFileDownloaderTypeParallel(request)
                    ?: fileTempDir
            ParallelFileDownloaderImpl(
                    initialDownload = download,
                    downloader = downloader,
                    progressReportingIntervalMillis = progressReportingIntervalMillis,
                    downloadBufferSizeBytes = downloadBufferSizeBytes,
                    logger = logger,
                    networkInfoProvider = networkInfoProvider,
                    retryOnNetworkGain = retryOnNetworkGain,
                    fileTempDir = tempDir)
        }
    }

    override fun getFileDownloaderDelegate(): FileDownloader.Delegate {
        return FileDownloaderDelegate(
                downloadInfoUpdater = downloadInfoUpdater,
                uiHandler = uiHandler,
                fetchListener = fetchListenerProvider.mainListener,
                logger = logger,
                retryOnNetworkGain = retryOnNetworkGain,
                requestOptions = requestOptions,
                downloader = downloader,
                fileTempDir = fileTempDir)
    }

}