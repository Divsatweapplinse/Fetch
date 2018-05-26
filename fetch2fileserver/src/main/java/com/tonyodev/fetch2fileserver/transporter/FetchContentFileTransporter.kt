package com.tonyodev.fetch2fileserver.transporter

import com.tonyodev.fetch2fileserver.ContentFileRequest
import com.tonyodev.fetch2fileserver.ContentFileResponse
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress

class FetchContentFileTransporter(private val client: Socket = Socket()) : ContentFileTransporter {


    private lateinit var dataInput: DataInputStream
    private lateinit var dataOutput: DataOutputStream
    private val lock = Any()
    @Volatile
    private var closed = false

    override val isClosed: Boolean
        get() {
            synchronized(lock) {
                return closed
            }
        }

    init {
        if (client.isConnected && !client.isClosed) {
            dataInput = DataInputStream(client.getInputStream())
            dataOutput = DataOutputStream(client.getOutputStream())
        }
        if (client.isClosed) {
            closed = true
        }
    }

    override fun connect(socketAddress: SocketAddress) {
        synchronized(lock) {
            throwExceptionIfClosed()
            client.connect(socketAddress)
            dataInput = DataInputStream(client.getInputStream())
            dataOutput = DataOutputStream(client.getOutputStream())
        }
    }

    override fun receiveContentFileRequest(): ContentFileRequest? {
        return synchronized(lock) {
            throwExceptionIfClosed()
            throwIfNotConnected()
            val json = JSONObject(dataInput.readUTF())
            val requestType = json.getInt("Type")
            val contentFileId = json.getString("ContentFileId")
            var rangeStart = json.getLong("RangeStart")
            var rangeEnd = json.getLong("RangeEnd")
            val authorization = json.getString("Authorization")
            val client = json.getString("Client")
            val customData = json.getString("CustomData")
            var page = json.getInt("Page")
            var size = json.getInt("Size")
            if ((rangeStart < 0L || rangeStart > rangeEnd) && rangeEnd > -1) {
                rangeStart = 0L
            }
            if (rangeEnd < 0L || rangeEnd < rangeStart) {
                rangeEnd = -1L
            }
            if (page < -1) {
                page = -1
            }
            if (size < -1) {
                size = -1
            }
            val persistConnection = json.getBoolean("PersistConnection")
            ContentFileRequest(
                    type = requestType,
                    contentFileId = contentFileId,
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd,
                    authorization = authorization,
                    client = client,
                    customData = customData,
                    page = page,
                    size = size,
                    persistConnection = persistConnection)
        }
    }

    override fun sendContentFileRequest(contentFileRequest: ContentFileRequest) {
        synchronized(lock) {
            throwExceptionIfClosed()
            throwIfNotConnected()
            dataOutput.writeUTF(contentFileRequest.toJsonString)
            dataOutput.flush()
        }
    }

    override fun receiveContentFileResponse(): ContentFileResponse? {
        return synchronized(lock) {
            throwExceptionIfClosed()
            throwIfNotConnected()
            val json = JSONObject(dataInput.readUTF())
            val status = json.getInt("Status")
            val requestType = json.getInt("Type")
            val connection = json.getString("Connection")
            val date = json.getLong("Date")
            val contentLength = json.getLong("ContentLength")
            val md5 = json.getString("Md5")
            ContentFileResponse(
                    status = status,
                    type = requestType,
                    connection = connection,
                    date = date,
                    contentLength = contentLength,
                    md5 = md5)
        }
    }

    override fun sendContentFileResponse(contentFileResponse: ContentFileResponse) {
        synchronized(lock) {
            throwExceptionIfClosed()
            throwIfNotConnected()
            dataOutput.writeUTF(contentFileResponse.toJsonString)
            dataOutput.flush()
        }
    }

    override fun sendRawBytes(byteArray: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            throwExceptionIfClosed()
            throwIfNotConnected()
            dataOutput.write(byteArray, offset, length)
            dataOutput.flush()
        }
    }

    override fun readRawBytes(byteArray: ByteArray, offset: Int, length: Int): Int {
        return synchronized(lock) {
            throwExceptionIfClosed()
            throwIfNotConnected()
            dataInput.read(byteArray, offset, length)
        }
    }

    override fun getInputStream(): InputStream {
        synchronized(lock) {
            throwExceptionIfClosed()
            throwIfNotConnected()
            return dataInput
        }
    }

    override fun getOutputStream(): OutputStream {
        synchronized(lock) {
            throwExceptionIfClosed()
            throwIfNotConnected()
            return dataOutput
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                try {
                    dataInput.close()
                } catch (e: Exception) {
                }
                try {
                    dataOutput.close()
                } catch (e: Exception) {
                }
                try {
                    client.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun throwExceptionIfClosed() {
        if (closed) {
            throw Exception("ClientContentFileTransporter is already closed.")
        }
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun throwIfNotConnected() {
        if (dataInput == null || dataOutput == null) {
            throw Exception("You forgot to call connect before calling this method.")
        }
    }


}