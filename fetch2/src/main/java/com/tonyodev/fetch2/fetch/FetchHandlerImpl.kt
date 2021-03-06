package com.tonyodev.fetch2.fetch

import android.os.Build
import android.os.Handler
import com.tonyodev.fetch2.*
import com.tonyodev.fetch2.database.DatabaseManager
import com.tonyodev.fetch2.database.DownloadInfo
import com.tonyodev.fetch2.downloader.DownloadManager
import com.tonyodev.fetch2.provider.ListenerProvider
import com.tonyodev.fetch2.helper.PriorityListProcessor
import com.tonyodev.fetch2.util.*
import java.io.File

/**
 * This handler class handles all tasks and operations of Fetch.
 * */
class FetchHandlerImpl(private val namespace: String,
                       private val databaseManager: DatabaseManager,
                       private val downloadManager: DownloadManager,
                       private val priorityListProcessor: PriorityListProcessor<Download>,
                       override val fetchListenerProvider: ListenerProvider,
                       private val handler: Handler,
                       private val logger: Logger,
                       private val autoStart: Boolean,
                       private val requestOptions: Set<RequestOptions>,
                       private val downloader: Downloader,
                       private val fileTempDir: String) : FetchHandler {

    @Volatile
    private var isTerminating = false

    override fun init() {
        databaseManager.sanitize(true)
        if (autoStart) {
            priorityListProcessor.start()
        }
    }

    override fun enqueue(request: Request): Download {
        val downloadInfo = request.toDownloadInfo()
        downloadInfo.namespace = namespace
        downloadInfo.status = Status.QUEUED
        prepareDownloadInfoForEnqueue(downloadInfo)
        databaseManager.insert(downloadInfo)
        startPriorityQueueIfNotStarted()
        return downloadInfo
    }

    private fun prepareDownloadInfoForEnqueue(downloadInfo: DownloadInfo) {
        var doAutoIncrementFileNameCheck = true
        when {
        //Jump in here if any request options pertain to enqueuing a request
            requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE)
                    || requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE_FRESH)
                    || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_ID)
                    || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_FRESH_ID)
                    || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_FILE)
                    || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_FRESH_FILE) -> {
                //If any options pertain to id, do a database query based on id. If match found cancel download.
                var existingDownloadInfoWithId: DownloadInfo? = null
                if (requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE)
                        || requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE_FRESH)
                        || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_ID)
                        || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_FRESH_ID)) {
                    existingDownloadInfoWithId = databaseManager.get(downloadInfo.id)
                    if (existingDownloadInfoWithId != null) {
                        cancelDownload(existingDownloadInfoWithId.id)
                        existingDownloadInfoWithId = databaseManager.get(downloadInfo.id)
                    }
                }
                //If any options pertain to file, do a database query based on file. If match found cancel download.
                var existingDownloadInfoWithFile: DownloadInfo? = null
                if (requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE)
                        || requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE_FRESH)
                        || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_FILE)
                        || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_FRESH_FILE)) {
                    existingDownloadInfoWithFile = databaseManager.getByFile(downloadInfo.file)
                    if (existingDownloadInfoWithFile != null) {
                        cancelDownload(existingDownloadInfoWithFile.id)
                        existingDownloadInfoWithFile = databaseManager.getByFile(downloadInfo.file)
                    }
                }
                //If less damaging(do not delete existing file) option exist jump in here
                if (requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_ID)
                        || requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE)
                        || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_FILE)) {
                    val download: DownloadInfo? = existingDownloadInfoWithFile
                            ?: existingDownloadInfoWithId
                    if (download != null) {
                        downloadInfo.downloaded = download.downloaded
                        downloadInfo.total = download.total
                        if (download.status == Status.COMPLETED) {
                            downloadInfo.status = download.status
                        }
                        doAutoIncrementFileNameCheck = false
                    } else {
                        doAutoIncrementFileNameCheck = true
                    }
                } else {
                    doAutoIncrementFileNameCheck = true
                }
                //Delete found records if they exist
                if (existingDownloadInfoWithFile != null) {
                    databaseManager.delete(existingDownloadInfoWithFile)
                    deleteRequestTempFiles(
                            fileTempDir,
                            downloader,
                            existingDownloadInfoWithFile)
                }
                if (existingDownloadInfoWithId != null) {
                    databaseManager.delete(existingDownloadInfoWithId)
                    deleteRequestTempFiles(
                            fileTempDir,
                            downloader,
                            existingDownloadInfoWithId)
                }
            }
        }
        if (doAutoIncrementFileNameCheck) {
            updateFileForDownloadInfoIfNeeded(downloadInfo)
        }
    }

    private fun updateFileForDownloadInfoIfNeeded(downloadInfo: DownloadInfo) {
        updateFileForDownloadInfoIfNeeded(listOf(downloadInfo))
    }

    private fun updateFileForDownloadInfoIfNeeded(downloadInfoList: List<DownloadInfo>) {
        if (requestOptions.contains(RequestOptions.ADD_AUTO_INCREMENT_TO_FILE_ON_ENQUEUE)
                || requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE)) {
            downloadInfoList.forEach { downloadInfo ->
                val file = getIncrementedFileIfOriginalExists(downloadInfo.file)
                val generatedId = getUniqueId(downloadInfo.url, downloadInfo.file)
                downloadInfo.file = file.absolutePath
                if (generatedId == downloadInfo.id) {
                    downloadInfo.id = getUniqueId(downloadInfo.url, downloadInfo.file)
                }
                createFileIfPossible(file)
            }
        }
    }

    override fun enqueue(requests: List<Request>): List<Download> {
        val requestsList = prepareRequestListForEnqueue(requests)
        val downloadInfoList = requestsList.map {
            val downloadInfo = it.toDownloadInfo()
            downloadInfo.namespace = namespace
            downloadInfo.status = Status.QUEUED
            prepareDownloadInfoForEnqueue(downloadInfo)
            downloadInfo
        }
        val results = databaseManager.insert(downloadInfoList)
                .filter { it.second }
                .map {
                    logger.d("Enqueued download ${it.first}")
                    it.first
                }
        startPriorityQueueIfNotStarted()
        return results
    }

    private fun prepareRequestListForEnqueue(requests: List<Request>): List<Request> {
        var sequence = requests.asSequence()
        if (requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE)
                || requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE_FRESH)
                || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_ID)
                || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_FRESH_ID)) {
            sequence = sequence.distinctBy { it.id }
        }
        if (requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE)
                || requestOptions.contains(RequestOptions.REPLACE_ALL_ON_ENQUEUE_WHERE_UNIQUE_FRESH)
                || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_FILE)
                || requestOptions.contains(RequestOptions.REPLACE_ON_ENQUEUE_FRESH_FILE)) {
            sequence = sequence.distinctBy { it.file }
        }
        return sequence.toList()
    }

    override fun pause(ids: IntArray): List<Download> {
        startPriorityQueueIfNotStarted()
        ids.forEach {
            if (isDownloading(it)) {
                cancelDownload(it)
            }
        }
        val downloadsInfoList = databaseManager.get(ids.toList()).filterNotNull()
        downloadsInfoList.forEach {
            if (canPauseDownload(it)) {
                it.status = Status.PAUSED
            }
        }
        return try {
            databaseManager.update(downloadsInfoList)
            downloadsInfoList
        } catch (e: Exception) {
            logger.e(FETCH_DATABASE_ERROR, e)
            listOf()
        }
    }

    override fun pausedGroup(id: Int): List<Download> {
        startPriorityQueueIfNotStarted()
        var downloadInfoList = databaseManager.getByGroup(id)
        downloadInfoList.forEach {
            if (isDownloading(it.id)) {
                cancelDownload(it.id)
            }
        }
        downloadInfoList = databaseManager.getByGroup(id)
        downloadInfoList.forEach {
            if (canPauseDownload(it)) {
                it.status = Status.PAUSED
            }
        }
        return try {
            databaseManager.update(downloadInfoList)
            downloadInfoList
        } catch (e: Exception) {
            logger.e(FETCH_DATABASE_ERROR, e)
            listOf()
        }
    }

    override fun freeze() {
        startPriorityQueueIfNotStarted()
        priorityListProcessor.pause()
        downloadManager.cancelAll()
        databaseManager.sanitize(true)
    }

    override fun unfreeze() {
        startPriorityQueueIfNotStarted()
        databaseManager.sanitize(true)
        priorityListProcessor.resume()
    }

    override fun resume(ids: IntArray): List<Download> {
        startPriorityQueueIfNotStarted()
        ids.forEach {
            if (isDownloading(it)) {
                cancelDownload(it)
            }
        }
        val downloadsInfoList = databaseManager.get(ids.toList()).filterNotNull()
        downloadsInfoList.forEach {
            if (!isDownloading(it.id) && canResumeDownload(it)) {
                it.status = Status.QUEUED
            }
        }
        return try {
            databaseManager.update(downloadsInfoList)
            downloadsInfoList
        } catch (e: Exception) {
            logger.e(FETCH_DATABASE_ERROR, e)
            listOf()
        }
    }

    override fun resumeGroup(id: Int): List<Download> {
        startPriorityQueueIfNotStarted()
        val downloadsInfoList = databaseManager.getByGroup(id)
                .filter {
                    !isDownloading(it.id) && canResumeDownload(it)
                }
        downloadsInfoList.forEach {
            it.status = Status.QUEUED
        }
        return try {
            databaseManager.update(downloadsInfoList)
            downloadsInfoList
        } catch (e: Exception) {
            logger.e(FETCH_DATABASE_ERROR, e)
            listOf()
        }
    }

    override fun remove(ids: IntArray): List<Download> {
        startPriorityQueueIfNotStarted()
        ids.forEach {
            if (isDownloading(it)) {
                cancelDownload(it)
            }
        }
        val downloadsList = databaseManager.get(ids.toList()).filterNotNull()
        databaseManager.delete(downloadsList)
        val removedStatus = Status.REMOVED
        downloadsList.forEach {
            it.status = removedStatus
            deleteRequestTempFiles(fileTempDir, downloader, it)
        }
        return downloadsList
    }

    override fun removeGroup(id: Int): List<Download> {
        startPriorityQueueIfNotStarted()
        val downloadInfoList = databaseManager.getByGroup(id)
        downloadInfoList.forEach {
            if (isDownloading(it.id)) {
                cancelDownload(it.id)
            }
        }
        databaseManager.delete(downloadInfoList)
        val removedStatus = Status.REMOVED
        downloadInfoList.forEach {
            it.status = removedStatus
            deleteRequestTempFiles(fileTempDir, downloader, it)
        }
        return downloadInfoList
    }

    override fun removeAll(): List<Download> {
        startPriorityQueueIfNotStarted()
        downloadManager.cancelAll()
        val downloadInfoList = databaseManager.get()
        databaseManager.deleteAll()
        val removedStatus = Status.REMOVED
        downloadInfoList.forEach {
            it.status = removedStatus
            deleteRequestTempFiles(fileTempDir, downloader, it)
        }
        return downloadInfoList
    }

    override fun removeAllWithStatus(status: Status): List<Download> {
        startPriorityQueueIfNotStarted()
        val downloadInfoList = databaseManager.getByStatus(status)
        downloadInfoList.forEach {
            if (isDownloading(it.id)) {
                cancelDownload(it.id)
            }
        }
        databaseManager.delete(downloadInfoList)
        val removedStatus = Status.REMOVED
        downloadInfoList.forEach {
            it.status = removedStatus
            deleteRequestTempFiles(fileTempDir, downloader, it)
        }
        return downloadInfoList
    }

    override fun delete(ids: IntArray): List<Download> {
        startPriorityQueueIfNotStarted()
        ids.forEach {
            if (isDownloading(it)) {
                cancelDownload(it)
            }
        }
        val downloadsList = databaseManager.get(ids.toList()).filterNotNull()
        databaseManager.delete(downloadsList)
        val deletedStatus = Status.DELETED
        downloadsList.forEach {
            it.status = deletedStatus
            try {
                val file = File(it.file)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                logger.d("Failed to delete file ${it.file}", e)
            }
            deleteRequestTempFiles(fileTempDir, downloader, it)
        }
        return downloadsList
    }

    override fun deleteGroup(id: Int): List<Download> {
        startPriorityQueueIfNotStarted()
        val downloadInfoList = databaseManager.getByGroup(id)
        downloadInfoList.forEach {
            if (isDownloading(it.id)) {
                cancelDownload(it.id)
            }
        }
        databaseManager.delete(downloadInfoList)
        val deletedStatus = Status.DELETED
        downloadInfoList.forEach {
            it.status = deletedStatus
            try {
                val file = File(it.file)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                logger.d("Failed to delete file ${it.file}", e)
            }
            deleteRequestTempFiles(fileTempDir, downloader, it)
        }
        return downloadInfoList
    }

    override fun deleteAll(): List<Download> {
        startPriorityQueueIfNotStarted()
        downloadManager.cancelAll()
        val downloadInfoList = databaseManager.get()
        databaseManager.deleteAll()
        val deletedStatus = Status.DELETED
        downloadInfoList.forEach {
            it.status = deletedStatus
            try {
                val file = File(it.file)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                logger.d("Failed to delete file ${it.file}", e)
            }
            deleteRequestTempFiles(fileTempDir, downloader, it)
        }
        return downloadInfoList
    }

    override fun deleteAllWithStatus(status: Status): List<Download> {
        startPriorityQueueIfNotStarted()
        val downloadInfoList = databaseManager.getByStatus(status)
        downloadInfoList.forEach {
            if (isDownloading(it.id)) {
                cancelDownload(it.id)
            }
        }
        databaseManager.delete(downloadInfoList)
        val deletedStatus = Status.DELETED
        downloadInfoList.forEach {
            it.status = deletedStatus
            try {
                val file = File(it.file)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                logger.d("Failed to delete file ${it.file}", e)
            }
            deleteRequestTempFiles(fileTempDir, downloader, it)
        }
        return downloadInfoList
    }

    override fun cancel(ids: IntArray): List<Download> {
        startPriorityQueueIfNotStarted()
        ids.forEach {
            if (isDownloading(it)) {
                cancelDownload(it)
            }
        }
        val downloadInfoList = databaseManager.get(ids.toList()).filterNotNull()
        downloadInfoList.forEach {
            if (canCancelDownload(it)) {
                it.status = Status.CANCELLED
                it.error = defaultNoError
            }
        }
        return try {
            databaseManager.update(downloadInfoList)
            downloadInfoList
        } catch (e: Exception) {
            logger.e(FETCH_DATABASE_ERROR, e)
            listOf()
        }
    }

    override fun cancelGroup(id: Int): List<Download> {
        startPriorityQueueIfNotStarted()
        var downloadInfoList = databaseManager.getByGroup(id)
        downloadInfoList.forEach {
            if (isDownloading(it.id)) {
                downloadManager.cancel(it.id)
            }
        }
        downloadInfoList = databaseManager.getByGroup(id)
        downloadInfoList.forEach {
            if (canCancelDownload(it)) {
                it.status = Status.CANCELLED
                it.error = defaultNoError
            }
        }
        return try {
            databaseManager.update(downloadInfoList)
            downloadInfoList
        } catch (e: Exception) {
            logger.e(FETCH_DATABASE_ERROR, e)
            listOf()
        }
    }

    override fun cancelAll(): List<Download> {
        startPriorityQueueIfNotStarted()
        var downloadInfoList = databaseManager.get()
        downloadInfoList.forEach {
            if (isDownloading(it.id)) {
                downloadManager.cancel(it.id)
            }
        }
        downloadInfoList = databaseManager.get()
        downloadInfoList.forEach {
            if (canCancelDownload(it)) {
                it.status = Status.CANCELLED
                it.error = defaultNoError
            }
        }
        return try {
            databaseManager.update(downloadInfoList)
            downloadInfoList
        } catch (e: Exception) {
            logger.e(FETCH_DATABASE_ERROR, e)
            listOf()
        }
    }

    override fun retry(ids: IntArray): List<Download> {
        startPriorityQueueIfNotStarted()
        val downloadInfoList = databaseManager.get(ids.toList()).filterNotNull()
        downloadInfoList.forEach {
            if (canRetryDownload(it)) {
                it.status = Status.QUEUED
                it.error = defaultNoError
            }
        }
        return try {
            databaseManager.update(downloadInfoList)
            downloadInfoList
        } catch (e: Exception) {
            logger.e(FETCH_DATABASE_ERROR, e)
            listOf()
        }
    }

    override fun updateRequest(id: Int, requestInfo: RequestInfo): Download? {
        startPriorityQueueIfNotStarted()
        var downloadInfo = databaseManager.get(id)
        if (downloadInfo != null) {
            if (isDownloading(id)) {
                cancelDownload(id)
            }
            downloadInfo = databaseManager.get(id)
            if (downloadInfo != null) {
                downloadInfo.group = requestInfo.groupId
                downloadInfo.headers = requestInfo.headers
                downloadInfo.priority = requestInfo.priority
                downloadInfo.networkType = requestInfo.networkType
                databaseManager.update(downloadInfo)
                return downloadInfo
            }
        }
        return null
    }

    override fun getDownloads(): List<Download> {
        startPriorityQueueIfNotStarted()
        return databaseManager.get()
    }

    override fun getDownload(id: Int): Download? {
        startPriorityQueueIfNotStarted()
        return databaseManager.get(id)
    }

    override fun getDownloads(idList: List<Int>): List<Download> {
        startPriorityQueueIfNotStarted()
        val downloads = databaseManager.get(idList)
        val results = mutableListOf<Download>()
        downloads.filterNotNull().forEach {
            results.add(it)
        }
        return results
    }

    override fun getDownloadsInGroup(id: Int): List<Download> {
        startPriorityQueueIfNotStarted()
        return databaseManager.getByGroup(id)
    }

    override fun getDownloadsWithStatus(status: Status): List<Download> {
        startPriorityQueueIfNotStarted()
        return databaseManager.getByStatus(status)
    }

    override fun getDownloadsInGroupWithStatus(groupId: Int, status: Status): List<Download> {
        startPriorityQueueIfNotStarted()
        return databaseManager.getDownloadsInGroupWithStatus(groupId, status)
    }

    override fun close() {
        if (isTerminating) {
            return
        }
        isTerminating = true
        fetchListenerProvider.listeners.clear()
        priorityListProcessor.stop()
        downloadManager.terminateAllDownloads()
        handler.post {
            try {
                downloadManager.close()
                databaseManager.close()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    handler.looper.quitSafely()
                } else {
                    handler.looper.quit()
                }
            } catch (e: Exception) {
                logger.e("FetchHandler", e)
            }
        }
        FetchModulesBuilder.removeActiveFetchHandlerNamespaceInstance(namespace)
    }

    override fun setGlobalNetworkType(networkType: NetworkType) {
        startPriorityQueueIfNotStarted()
        priorityListProcessor.globalNetworkType = networkType
        downloadManager.cancelAll()
        databaseManager.sanitize(true)
    }

    override fun enableLogging(enabled: Boolean) {
        startPriorityQueueIfNotStarted()
        logger.d("Enable logging - $enabled")
        logger.enabled = enabled
    }

    override fun addListener(listener: FetchListener) {
        startPriorityQueueIfNotStarted()
        fetchListenerProvider.listeners.add(listener)
        logger.d("Added listener $listener")
    }

    override fun removeListener(listener: FetchListener) {
        startPriorityQueueIfNotStarted()
        val iterator = fetchListenerProvider.listeners.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() == listener) {
                logger.d("Removed listener $listener")
                iterator.remove()
                break
            }
        }
    }

    override fun isDownloading(id: Int): Boolean {
        startPriorityQueueIfNotStarted()
        return downloadManager.contains(id)
    }

    override fun cancelDownload(id: Int): Boolean {
        startPriorityQueueIfNotStarted()
        return downloadManager.cancel(id)
    }

    private fun startPriorityQueueIfNotStarted() {
        if (priorityListProcessor.isStopped && !isTerminating) {
            priorityListProcessor.start()
        }
        if (priorityListProcessor.isPaused && !isTerminating) {
            priorityListProcessor.resume()
        }
    }

}