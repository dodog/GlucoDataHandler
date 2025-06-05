package de.michelinside.glucodatahandler.common.database

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import de.michelinside.glucodatahandler.common.AppSource
import de.michelinside.glucodatahandler.common.Command
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.GlucoDataService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringWriter

object dbSync : ChannelClient.ChannelCallback() {
    private val LOG_ID = "GDH.dbSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recvFinished = true
    private var channel: ChannelClient.Channel? = null
    private var registered = false
    private var retryCount = 0
    private var syncThread: Thread? = null

    private fun registerChannel(context: Context) {
        if(!registered) {
            Log.d(LOG_ID, "registerChannel called")
            Wearable.getChannelClient(context).registerChannelCallback(this)
            registered = true
            Log.d(LOG_ID, "ChannelClient registered")
        }
    }

    private fun unregisterChannel(context: Context) {
        if(registered) {
            Log.d(LOG_ID, "unregisterChannel called")
            Wearable.getChannelClient(context).unregisterChannelCallback(this)
            registered = false
            Log.d(LOG_ID, "ChannelClient unregistered")
        }
    }

    private fun getStringFromInputStream(stream: InputStream?): String {
        var n: Int
        val buffer = CharArray(1024 * 4)
        val reader = InputStreamReader(stream, "UTF8")
        val writer = StringWriter()
        while (-1 != (reader.read(buffer).also { n = it })) writer.write(buffer, 0, n)
        return writer.toString()
    }

    override fun onChannelOpened(p0: ChannelClient.Channel) {
        try {
            Log.d(LOG_ID, "onChannelOpened for path ${p0.path}")
            if(p0.path != Constants.DB_SYNC_CHANNEL_PATH)
                return
            super.onChannelOpened(p0)
            channel = p0
            scope.launch {
                try {
                    Log.d(LOG_ID, "receiving...")
                    val inputStream = Tasks.await(Wearable.getChannelClient(GlucoDataService.context!!).getInputStream(p0))
                    Log.d(LOG_ID, "received - read")
                    val received = getStringFromInputStream(inputStream)
                    Log.v(LOG_ID, "received data: $received")
                    dbAccess.addGlucoseValuesFromJson(received)
                    Log.d(LOG_ID, "db data saved")
                } catch (exc: Exception) {
                    Log.e(LOG_ID, "reading input exception: " + exc.message.toString() )
                }
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onChannelOpened exception: " + exc.message.toString() )
        }
    }

    override fun onInputClosed(p0: ChannelClient.Channel, i: Int, i1: Int) {
        try {
            Log.d(LOG_ID, "onInputClosed for path ${p0.path}")
            if(p0.path != Constants.DB_SYNC_CHANNEL_PATH)
                return
            super.onInputClosed(p0, i, i1)
            close(GlucoDataService.context!!)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onInputClosed exception: " + exc.message.toString() )
        }
    }

    override fun onOutputClosed(p0: ChannelClient.Channel, p1: Int, p2: Int) {
        try {
            super.onOutputClosed(p0, p1, p2)
            Log.d(LOG_ID, "onOutputClosed for path ${p0.path}")
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onInputClosed exception: " + exc.message.toString() )
        }
    }

    fun sendData(context: Context, nodeId: String) {
        try {
            Log.i(LOG_ID, "send db data to $nodeId")
            val channelClient = Wearable.getChannelClient(context)
            val channelTask =
                channelClient.openChannel(nodeId, Constants.DB_SYNC_CHANNEL_PATH)
            channelTask.addOnSuccessListener { sendChannel ->
                Thread {
                    try {
                        val outputStream = Tasks.await(channelClient.getOutputStream(sendChannel))
                        val minTime = System.currentTimeMillis() - (if(GlucoDataService.appSource == AppSource.WEAR_APP) Constants.DB_MAX_DATA_TIME_MS else Constants.DB_MAX_DATA_WEAR_TIME_MS)  // from phone to wear, only send the last 24h
                        val string = dbAccess.getGlucoseValuesAsJson(minTime)
                        Log.v(LOG_ID, string)
                        outputStream.write(string.toByteArray())
                        outputStream.flush()
                        outputStream.close()
                        channelClient.close(sendChannel)
                        Log.d(LOG_ID, "db data sent")
                        if(GlucoDataService.appSource == AppSource.WEAR_APP) {
                            Log.i(LOG_ID, "Clear old data after sync")
                            dbAccess.deleteOldValues(System.currentTimeMillis()-Constants.DB_MAX_DATA_WEAR_TIME_MS)
                        }
                    } catch (exc: Exception) {
                        Log.e(LOG_ID, "sendData exception: " + exc.toString())
                    }
                }.start()
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "sendData exception: " + exc.toString())
        }
    }

    private fun close(context: Context) {
        try {
            val channelClient = Wearable.getChannelClient(context)
            if(channel != null) {
                Log.d(LOG_ID, "close channel")
                channelClient.close(channel!!)
                channel = null
                Log.d(LOG_ID, "channel closed")
            }
            syncThread = null
            recvFinished = true
            unregisterChannel(context)
            Log.d(LOG_ID, "sync closed")
        } catch (exc: Exception) {
            Log.e(LOG_ID, "close exception: " + exc.toString())
        }
    }

    private fun isSyncActive(): Boolean {
        return syncThread != null && syncThread!!.isAlive
    }

    private fun waitFor(context: Context) {
        syncThread = Thread {
            try {
                Log.v(LOG_ID, "Waiting for receiving db data")
                var count = 0
                while (!recvFinished && count < 10) {
                    Thread.sleep(1000)
                    count++
                }
                val success: Boolean
                if (!recvFinished) {
                    Log.w(LOG_ID, "Receiving still not finished!")
                    success = false
                } else {
                    Log.d(LOG_ID, "Receiving finished!")
                    success = true
                }
                close(context)
                if (success) {
                    Log.i(LOG_ID, "db sync succeeded")
                    if (GlucoDataService.appSource == AppSource.PHONE_APP) {
                        Log.d(LOG_ID, "Trigger watch sync")
                        GlucoDataService.sendCommand(Command.REQUEST_DB_SYNC)
                    }
                } else {
                    Log.w(LOG_ID, "db sync failed - cur retry: $retryCount")
                    if(retryCount < 3) {
                        Thread.sleep(10000)
                        retryCount++
                        requestDbSync(context, retryCount)
                    } else {
                        Log.d(LOG_ID, "Trigger watch sync even phone sync failed...")
                        GlucoDataService.sendCommand(Command.REQUEST_DB_SYNC)
                    }
                }
            } catch (exc: Exception) {
                Log.e(LOG_ID, "waitFor exception: " + exc.message.toString() )
            }
        }
        syncThread!!.start()
    }

    fun requestDbSync(context: Context, curRetry: Int = 0) {
        Log.d(LOG_ID, "request db sync - finished: $recvFinished - retry: $curRetry - syncActive: ${isSyncActive()}")
        if (!isSyncActive()) {
            close(context)
            retryCount = curRetry
            recvFinished = false
            registerChannel(context)
            GlucoDataService.sendCommand(Command.DB_SYNC)
            waitFor(context)
        }
    }
}