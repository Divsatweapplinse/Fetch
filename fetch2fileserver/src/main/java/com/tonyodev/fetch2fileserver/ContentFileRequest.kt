package com.tonyodev.fetch2fileserver

data class ContentFileRequest(val type: Int = TYPE_INVALID,
                              val contentFileId: String = CATALOG_ID.toString(),
                              val rangeStart: Long = 0L,
                              val rangeEnd: Long = -1L,
                              val authorization: String = "",
                              val client: String = "",
                              val customData: String = "",
                              val page: Int = 0,
                              val size: Int = 0,
                              val persistConnection: Boolean = true) {

    val toJsonString: String
        get() {
            return "{\"Type\":$type,\"ContentFileId\":$contentFileId,\"RangeStart\":$rangeStart,\"RangeEnd\":$rangeEnd," +
                    "\"Authorization\":\"$authorization\",\"Client\":\"$client\",\"CustomData\":\"$customData\"," +
                    "\"Page\":$page,\"Size\":$size,\"PersistConnection\":$persistConnection}"
        }

    companion object {

        const val TYPE_INVALID = -1
        const val TYPE_PING = 0
        const val TYPE_FILE = 1
        const val TYPE_CATALOG = 2
        const val CATALOG_ID = -1L

    }

}