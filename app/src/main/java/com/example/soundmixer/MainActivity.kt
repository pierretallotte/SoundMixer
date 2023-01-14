package com.example.soundmixer

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.util.JsonWriter
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toFile
import androidx.core.view.children
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.slider.Slider
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.lang.Math.min
import java.nio.file.Files.createTempDirectory
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.collections.LinkedHashMap


class MainActivity : AppCompatActivity() {

    class MediaPlayerProxy {
        var mediaVolume = 1.0F
        var maxVolume = 1.0F
        var isStopping = false
        var volumeStop = 0.0F
        var timeStop = 0.0F
        var beginFadeDuration = 0
        var endFadeDuration = 0
        var mediaPlayer = MediaPlayer()
        var name: String? = null
        var uri: Uri? = null
    }

    class ActivityState : ViewModel() {
        var mediaPlayerList = LinkedHashMap<Int, MediaPlayerProxy>()
        var toogleSettings = true
    }

    class LooperThread : Thread() {
        var handler: Handler? = null

        var state = ActivityState()
        var context: Context? = null

        companion object {
            val CREATE_MEDIA = 0
            val START_MEDIA = 1
            val PAUSE_MEDIA = 2
            val STOP_MEDIA = 3
            val DELETE_MEDIA = 6
            val NEW_UPDATE_VOLUME = 7
        }

        var contentResolver: ContentResolver? = null

        override fun run() {
            Looper.prepare()

            handler = Looper.myLooper()?.let {
                Handler(it, Handler.Callback { message ->
                    Log.i("LOOPERTHREADCALLBACK", "Id: ${message.arg1} - Action: ${message.arg2}")

                    if(message.arg2 == CREATE_MEDIA || state.mediaPlayerList[message.arg1] != null) {
                        when(message.arg2) {
                            CREATE_MEDIA ->
                                if(message.obj is Uri) {
                                    val mediaPlayerProxy = MediaPlayerProxy()
                                    val ips = FileInputStream(contentResolver?.openFileDescriptor(
                                        message.obj as Uri, "r")!!.fileDescriptor)
                                    mediaPlayerProxy.mediaPlayer.setDataSource(ips.fd)
                                    mediaPlayerProxy.mediaPlayer.prepare()
                                    mediaPlayerProxy.mediaPlayer.setVolume(0.0F, 0.0F)
                                    mediaPlayerProxy.mediaVolume = 0.0F
                                    mediaPlayerProxy.name = DocumentFile.fromSingleUri(context!!, message.obj as Uri)?.name
                                    mediaPlayerProxy.uri = message.obj as Uri
                                    state.mediaPlayerList[message.arg1] = mediaPlayerProxy
                                }

                            START_MEDIA -> {
                                Log.i("LOOPERTHREADCALLBACK", "Start media")
                                if(!state.mediaPlayerList[message.arg1]?.mediaPlayer?.isPlaying!!) {
                                    state.mediaPlayerList[message.arg1]?.mediaPlayer?.start()
                                    state.mediaPlayerList[message.arg1]?.isStopping = false

                                    Log.i("LOOPERTHREADCALLBACK", "Current position: ${state.mediaPlayerList[message.arg1]?.mediaPlayer?.currentPosition!!}")

                                    val newMessage = Message()
                                    newMessage.arg1 = message.arg1
                                    newMessage.arg2 = NEW_UPDATE_VOLUME
                                    handler?.sendMessageDelayed(newMessage, 100)
                                }
                            }

                            PAUSE_MEDIA -> {
                                Log.i("LOOPERTHREADCALLBACK", "Pause media")
                                if (state.mediaPlayerList[message.arg1]?.mediaPlayer?.isPlaying!!)
                                    state.mediaPlayerList[message.arg1]?.mediaPlayer?.pause()
                            }

                            STOP_MEDIA -> {
                                Log.i("LOOPERTHREADCALLBACK", "Stop media")
                                if(state.mediaPlayerList[message.arg1]?.mediaPlayer?.isPlaying!!) {
                                    if(state.mediaPlayerList[message.arg1]?.endFadeDuration == 0) {
                                        Log.i("LOOPERTHREADCALLBACK", "Stop directly")
                                        state.mediaPlayerList[message.arg1]?.mediaVolume = 0.0F
                                        state.mediaPlayerList[message.arg1]?.isStopping = false
                                        state.mediaPlayerList[message.arg1]?.mediaPlayer?.stop()
                                        state.mediaPlayerList[message.arg1]?.mediaPlayer?.prepare()
                                    } else {
                                        state.mediaPlayerList[message.arg1]?.isStopping = true
                                        state.mediaPlayerList[message.arg1]?.volumeStop =
                                            state.mediaPlayerList[message.arg1]?.mediaVolume!!
                                        state.mediaPlayerList[message.arg1]?.timeStop =
                                            state.mediaPlayerList[message.arg1]?.mediaPlayer?.currentPosition?.toFloat()!!

                                        val newMessage = Message()
                                        newMessage.arg1 = message.arg1
                                        newMessage.arg2 = NEW_UPDATE_VOLUME
                                        handler?.sendMessageDelayed(newMessage, 100)
                                    }
                                }
                            }

                            DELETE_MEDIA -> {
                                Log.i("LOOPERTHREADCALLBACK", "Delete media")
                                state.mediaPlayerList[message.arg1]?.mediaPlayer?.release()
                                state.mediaPlayerList.remove(message.arg1);
                            }

                            NEW_UPDATE_VOLUME -> {
                                Log.i("LOOPERTHREADCALLBACK", "Update volume")
                                if(state.mediaPlayerList[message.arg1]?.mediaPlayer?.isPlaying!!) {
                                    Log.i("LOOPERTHREADCALLBACK", "Is playing")
                                    Log.i("LOOPERTHREADCALLBACK", "Current position: ${state.mediaPlayerList[message.arg1]?.mediaPlayer?.currentPosition!!}")
                                    if(!state.mediaPlayerList[message.arg1]?.isStopping!! && state.mediaPlayerList[message.arg1]?.mediaPlayer?.currentPosition!! > state.mediaPlayerList[message.arg1]?.mediaPlayer?.duration!! - state.mediaPlayerList[message.arg1]?.endFadeDuration!! * 1000) {
                                        Log.i("LOOPERTHREADCALLBACK", "End of the sound")
                                        state.mediaPlayerList[message.arg1]?.isStopping = true
                                        state.mediaPlayerList[message.arg1]?.volumeStop =
                                            state.mediaPlayerList[message.arg1]?.mediaVolume!!
                                        state.mediaPlayerList[message.arg1]?.timeStop =
                                            state.mediaPlayerList[message.arg1]?.mediaPlayer?.currentPosition?.toFloat()!!
                                    }

                                    if(state.mediaPlayerList[message.arg1]?.isStopping!!) {
                                        Log.i("LOOPERTHREADCALLBACK", "Is stopping")
                                        state.mediaPlayerList[message.arg1]?.mediaVolume = state.mediaPlayerList[message.arg1]?.volumeStop!! - (state.mediaPlayerList[message.arg1]?.volumeStop!! * (state.mediaPlayerList[message.arg1]?.mediaPlayer?.currentPosition?.toFloat()!! - state.mediaPlayerList[message.arg1]?.timeStop!!) / (state.mediaPlayerList[message.arg1]?.endFadeDuration!! * 1000))
                                        state.mediaPlayerList[message.arg1]?.mediaPlayer?.setVolume(state.mediaPlayerList[message.arg1]?.mediaVolume!!, state.mediaPlayerList[message.arg1]?.mediaVolume!!)
                                    } else {
                                        Log.i("LOOPERTHREADCALLBACK", "Not stopping")
                                        if(state.mediaPlayerList[message.arg1]?.mediaPlayer?.currentPosition!! < state.mediaPlayerList[message.arg1]?.beginFadeDuration!! * 1000) {
                                            Log.i("LOOPERTHREADCALLBACK", "Fade in")
                                            state.mediaPlayerList[message.arg1]?.mediaVolume = (state.mediaPlayerList[message.arg1]?.mediaPlayer?.currentPosition?.toFloat()!! / (state.mediaPlayerList[message.arg1]?.beginFadeDuration!! * 1000)) * state.mediaPlayerList[message.arg1]?.maxVolume!!
                                        } else {
                                            Log.i("LOOPERTHREADCALLBACK", "Max volume")
                                            state.mediaPlayerList[message.arg1]?.mediaVolume = state.mediaPlayerList[message.arg1]?.maxVolume!!
                                        }
                                        state.mediaPlayerList[message.arg1]?.mediaPlayer?.setVolume(state.mediaPlayerList[message.arg1]?.mediaVolume!!, state.mediaPlayerList[message.arg1]?.mediaVolume!!)
                                    }

                                    Log.i("LOOPERTHREADCALLBACK", "Update volume to ${state.mediaPlayerList[message.arg1]?.mediaVolume}")

                                    if(state.mediaPlayerList[message.arg1]?.mediaVolume!! >= 0.0F) {
                                        Log.i("LOOPERTHREADCALLBACK", "Continue to update")
                                        val newMessage = Message()
                                        newMessage.arg1 = message.arg1
                                        newMessage.arg2 = NEW_UPDATE_VOLUME
                                        handler?.sendMessageDelayed(newMessage, 100)
                                    } else {
                                        Log.i("LOOPERTHREADCALLBACK", "Stop the sound")
                                        state.mediaPlayerList[message.arg1]?.isStopping = false
                                        state.mediaPlayerList[message.arg1]?.mediaPlayer?.stop()
                                        state.mediaPlayerList[message.arg1]?.mediaPlayer?.prepare()
                                        state.mediaPlayerList[message.arg1]?.mediaPlayer?.seekTo(0)
                                    }
                                }
                            }
                        }
                    }
                    true
                })
            }

            Log.i("LOOPERTHREADCALLBACK", "Before Loop")
            Looper.loop()
            Log.i("LOOPERTHREADCALLBACK", "After Loop")
        }
    }

    private val resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            val id = addPlayingButtons(uri!!)
            val message = Message()
            message.arg1 = id
            message.arg2 = MainActivity.LooperThread.CREATE_MEDIA
            message.obj = uri
            looperThread?.handler?.sendMessage(message)
        }
    }

    private fun addPlayingButtons(param:Any = View.generateViewId()): Int {
        var id: Int? = null
        var name: String? = null
        if(param is Int) {
            id = param
            name = looperThread?.state?.mediaPlayerList?.get(id)?.name
        } else if(param is Uri) {
            id = View.generateViewId()
            name = DocumentFile.fromSingleUri(this, param as Uri)?.name
        } else if(param is JSONObject) {
            id = param.getInt("id")
            name = param.getString("name")
        }

        val globalLayout = LinearLayout(this)
        globalLayout.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        globalLayout.orientation = LinearLayout.VERTICAL
        globalLayout.id = id!!

        val nameLayout = LinearLayout(this)
        nameLayout.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        nameLayout.orientation = LinearLayout.HORIZONTAL

        val nameView = TextView(this)
        nameView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        nameView.text = name
        nameLayout.addView(nameView)

        val buttonLayout = LinearLayout(this)
        buttonLayout.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        buttonLayout.orientation = LinearLayout.HORIZONTAL

        val playButton = Button(this)
        playButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        playButton.text = "Play"
        playButton.setOnClickListener {
            val message = Message()
            message.arg1 = ((it.parent as LinearLayout).parent as LinearLayout).id
            message.arg2 = MainActivity.LooperThread.START_MEDIA
            looperThread?.handler?.sendMessage(message)
        }
        buttonLayout.addView(playButton)

        val pauseButton = Button(this)
        pauseButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        pauseButton.text = "Pause"
        pauseButton.setOnClickListener {
            val message = Message()
            message.arg1 = ((it.parent as LinearLayout).parent as LinearLayout).id
            message.arg2 = MainActivity.LooperThread.PAUSE_MEDIA
            looperThread?.handler?.sendMessage(message)
        }
        buttonLayout.addView(pauseButton)

        val stopButton = Button(this)
        stopButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        stopButton.text = "Stop"
        stopButton.setOnClickListener {
            val message = Message()
            message.arg1 = ((it.parent as LinearLayout).parent as LinearLayout).id
            message.arg2 = MainActivity.LooperThread.STOP_MEDIA
            looperThread?.handler?.sendMessage(message)
        }
        buttonLayout.addView(stopButton)

        val removeButton = Button(this)
        removeButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        removeButton.text = "Remove"
        removeButton.setOnClickListener {
            val message = Message()
            message.arg1 = ((it.parent as LinearLayout).parent as LinearLayout).id
            message.arg2 = LooperThread.DELETE_MEDIA
            looperThread?.handler?.sendMessage(message)

            ((it.parent as LinearLayout).parent as LinearLayout).visibility = View.GONE
        }
        buttonLayout.addView(removeButton)

        val paramLayout = LinearLayout(this)
        paramLayout.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        paramLayout.orientation = LinearLayout.HORIZONTAL

        val beginFade = NumberPicker(this)
        beginFade.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        beginFade.minValue = 0
        beginFade.maxValue = 10
        beginFade.value = if (param is JSONObject) param.getInt("beginFadeDuration") else 0
        beginFade.setOnValueChangedListener { _, _, newValue ->
            looperThread?.state?.mediaPlayerList?.get(id)?.beginFadeDuration = newValue
            Log.i("FILERETURN", "Change beginFade $newValue")
        }
        paramLayout.addView(beginFade)

        val endFade = NumberPicker(this)
        endFade.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        endFade.minValue = 0
        endFade.maxValue = 10
        endFade.value = if (param is JSONObject) param.getInt("endFadeDuration") else 0
        endFade.setOnValueChangedListener { _, _, newValue ->
            looperThread?.state?.mediaPlayerList?.get(id)?.endFadeDuration = newValue
            Log.i("FILERETURN", "Change endFade $newValue")
        }
        paramLayout.addView(endFade)

        val soundLayout = LinearLayout(this)
        soundLayout.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        soundLayout.orientation = LinearLayout.HORIZONTAL

        val volumeSlider = Slider(this)
        volumeSlider.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        volumeSlider.valueFrom = 0.0F
        volumeSlider.valueTo = 1.0F
        volumeSlider.value = if (param is JSONObject) param.getDouble("maxVolume").toFloat() else 1.0F
        volumeSlider.addOnChangeListener { _, value, _ ->
            looperThread?.state?.mediaPlayerList?.get(id)?.maxVolume = value
            Log.i("FILERETURN", "Change volume to $value")

            val message = Message()
            message.arg1 = id
            message.arg2 = MainActivity.LooperThread.NEW_UPDATE_VOLUME
            looperThread?.handler?.sendMessage(message)
        }
        soundLayout.addView(volumeSlider)

        globalLayout.addView(nameLayout)
        globalLayout.addView(buttonLayout)
        globalLayout.addView(paramLayout)
        globalLayout.addView(soundLayout)

        paramLayout.visibility = if(findViewById<Switch>(R.id.showSettings).isChecked) View.VISIBLE else View.GONE
        soundLayout.visibility = if(findViewById<Switch>(R.id.showSettings).isChecked) View.VISIBLE else View.GONE

        findViewById<LinearLayout>(R.id.buttonLayout).addView(globalLayout)

        return globalLayout.id
    }

    private fun toggleSettings(on: Boolean) {
        val buttonLayout: LinearLayout = findViewById(R.id.buttonLayout)

        buttonLayout.children.forEach {
            (it as LinearLayout).children.elementAt(2).visibility = if(on) View.VISIBLE else View.GONE
            (it as LinearLayout).children.elementAt(3).visibility = if(on) View.VISIBLE else View.GONE
        }
    }

    private var looperThread: MainActivity.LooperThread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        looperThread = LooperThread()
        looperThread!!.contentResolver = contentResolver
        looperThread!!.name = "Main Looper Thread"
        looperThread!!.start()

        looperThread!!.context = this

        val state: ActivityState = ViewModelProvider(this)[ActivityState::class.java]
        looperThread?.state = state

        val addSoundButton: Button = findViewById(R.id.addSoundButton)
        addSoundButton.setOnClickListener { addSound() }

        val saveProjectButton: Button = findViewById(R.id.saveProject)
        saveProjectButton.setOnClickListener { saveProject() }

        val loadProjectButton: Button = findViewById(R.id.loadProject)
        loadProjectButton.setOnClickListener { loadProject() }

        val showSettingsSwitch: Switch = findViewById(R.id.showSettings)
        showSettingsSwitch.isChecked = state.toogleSettings
        showSettingsSwitch.setOnCheckedChangeListener { _, b ->
            showSettingsSwitch.isChecked = b
            toggleSettings(b);
        }

        state.mediaPlayerList.forEach { (id, mediaPlayerProxy) ->
            addPlayingButtons(id)
        }
    }

    private fun saveProject() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "test.zip")
        }
        saveProjectLauncher.launch(intent)
    }

    private fun loadProject() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        loadProjectLauncher.launch(intent)
    }

    private val loadProjectLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {

            val proc = Runtime.getRuntime().exec("rm -rf " + filesDir.path)
            proc.waitFor()

            var dataFolder = File(filesDir.path + "/" + UUID.randomUUID().toString())
            dataFolder.mkdir()

            // Write URI to File
            val fis = contentResolver.openInputStream(result.data?.data!!)
            val tmpZip = File(dataFolder.path + "/project.zip")
            val tmpFos = FileOutputStream(tmpZip)
            val tmpBuffer = ByteArray(fis?.available()!!)
            fis.read(tmpBuffer)
            tmpFos.write(tmpBuffer)
            tmpFos.close()
            fis?.close()

            val zfis = FileInputStream(dataFolder.path + "/project.zip")
            val bis = BufferedInputStream(zfis)
            val zis = ZipInputStream(bis)

            var entry = zis.nextEntry
            while(entry != null)  {
                val fos = FileOutputStream(dataFolder.path + "/" + entry.name)
                val bos = BufferedOutputStream(fos)

                val buffer = ByteArray(1024)

                var n = zis.read(buffer)
                while(n > 0) {
                    bos.write(buffer, 0, n)
                    n = zis.read(buffer)
                }

                bos.close()
                fos.close()

                entry = zis.nextEntry
            }

            zis.close()
            bis.close()
            zfis.close()

            val config = File(dataFolder.path + "/config.json")

            if(!config.isFile) {
                Toast.makeText(this, "Impossible to load project: no config.json", Toast.LENGTH_SHORT).show()
            } else {
                val configFis = FileInputStream(config)
                val configData = ByteArray(configFis.available())
                configFis.read(configData)
                configFis.close()
                val jsonData = String(configData)
                val jsonArray = JSONArray(jsonData)

                // Remove all loaded medias
                looperThread?.state?.mediaPlayerList?.forEach { (id, _) ->
                    val message = Message()
                    message.arg1 = id
                    message.arg2 = LooperThread.DELETE_MEDIA
                    looperThread?.handler?.sendMessage(message)

                    findViewById<LinearLayout>(id).visibility = View.GONE
                }

                for(idx in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(idx)

                    val id = addPlayingButtons(obj)
                    val message = Message()
                    message.arg1 = id
                    message.arg2 = MainActivity.LooperThread.CREATE_MEDIA
                    message.obj = Uri.fromFile(File(dataFolder.path + "/" + obj.getString("id") + "_" + obj.getString("name")))
                    looperThread?.handler?.sendMessage(message)

                    while(!looperThread?.state?.mediaPlayerList?.containsKey(id)!!) {
                        Thread.sleep(10)
                    }

                    looperThread?.state?.mediaPlayerList!![id]?.maxVolume = obj.getDouble("maxVolume").toFloat()
                    looperThread?.state?.mediaPlayerList!![id]?.beginFadeDuration = obj.getInt("beginFadeDuration")
                    looperThread?.state?.mediaPlayerList!![id]?.endFadeDuration = obj.getInt("endFadeDuration")
                }
            }

        }
    }

    private val saveProjectLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {

            val zipUri:Uri = result.data?.data!!
            val jsonFile = File.createTempFile("config", "json")

            val writer = JsonWriter(OutputStreamWriter(FileOutputStream(jsonFile)))

            writer.beginArray()

            looperThread?.state?.mediaPlayerList?.forEach { (id, mediaPlayerProxy) ->
                writer.beginObject()
                writer.name("id").value(id)
                writer.name("name").value(mediaPlayerProxy.name)
                writer.name("maxVolume").value(mediaPlayerProxy.maxVolume)
                writer.name("beginFadeDuration").value(mediaPlayerProxy.beginFadeDuration)
                writer.name("endFadeDuration").value(mediaPlayerProxy.endFadeDuration)
                writer.endObject()
            }

            writer.endArray()

            writer.close()

            val zipFileOutputStream = contentResolver.openOutputStream(zipUri)
            val zipStream = ZipOutputStream(zipFileOutputStream)
            val jsonEntry = ZipEntry("config.json")
            zipStream.putNextEntry(jsonEntry)
            val jsonInputStream = FileInputStream(jsonFile)
            val jsonInput = BufferedInputStream(jsonInputStream)
            val data = ByteArray(jsonFile.length().toInt())
            jsonInput.read(data)
            jsonInput.close()
            jsonInputStream.close()
            zipStream.write(data)
            zipStream.flush()

            looperThread?.state?.mediaPlayerList?.forEach { (id, mediaPlayerProxy) ->
                val soundEntry = ZipEntry(id.toString() + "_" + mediaPlayerProxy.name)
                zipStream.putNextEntry(soundEntry)

                val soundInputStream = contentResolver.openInputStream(mediaPlayerProxy.uri!!)
                val soundInput = BufferedInputStream(soundInputStream)
                var soundSize = soundInput.available()

                Log.i("SOUNDMIXER", soundEntry.name)
                Log.i("SOUNDMIXER", "Size: $soundSize")

                while(soundSize > 0) {
                    val bufferSize = min(soundSize, 4096)
                    Log.i("SOUNDMIXER", "Buffer size: $bufferSize")
                    val localData = ByteArray(bufferSize)
                    soundInput.read(localData)

                    zipStream.write(localData)
                    soundSize -= bufferSize
                    Log.i("SOUNDMIXER", "Remaining size: $soundSize")
                }

                zipStream.flush()
                soundInput.close()
                soundInputStream?.close()
            }

            zipStream.close()
            zipFileOutputStream?.close()
        }
    }

    private fun addSound() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }

        resultLauncher.launch(intent)

        Log.i("FILERETURN", "Go")
    }
}
