package com.tonyodev.fetch2fileserver

import java.net.HttpURLConnection
import java.util.*

data class ContentFileResponse(val status: Int = HttpURLConnection.HTTP_UNSUPPORTED_TYPE,
                               val type: Int = ContentFileRequest.TYPE_INVALID,
                               val connection: String = CLOSE_CONNECTION,
                               val date: Long = Date().time,
                               val contentLength: Long = 0,
                               val md5: String = "") {

    val toJsonString: String
        get() {
            return "{\"Status\":$status,\"Type\":${type},\"Connection\":$connection,\"Date\":$date," +
                    "\"ContentLength\":$contentLength,\"Md5\":\"$md5\"}"
        }

    companion object {
        const val CLOSE_CONNECTION = "Close"
        const val OPEN_CONNECTION = "Open"
    }

}