package com.mja.reyamf.manager.sidebar

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.NotificationCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kyuubiran.ezxhelper.utils.runOnMainThread
import com.juanarton.batterysense.batterymonitorservice.ServiceState
import com.juanarton.batterysense.batterymonitorservice.setServiceState
import com.mja.reyamf.R
import com.mja.reyamf.common.gson
import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.common.runMain
import com.mja.reyamf.databinding.SidebarLayoutBinding
import com.mja.reyamf.manager.adapter.SideBarAdapter
import com.mja.reyamf.manager.adapter.VerticalSpaceItemDecoration
import com.mja.reyamf.manager.applist.AppListWindow
import com.mja.reyamf.manager.core.domain.repository.IReyamfRepository
import com.mja.reyamf.manager.services.YAMFManagerProxy
import com.mja.reyamf.xposed.IAppListCallback
import com.mja.reyamf.xposed.utils.animateAlpha
import com.mja.reyamf.xposed.utils.animateResize
import com.mja.reyamf.xposed.utils.componentName
import com.mja.reyamf.xposed.utils.dpToPx
import com.mja.reyamf.xposed.utils.vibratePhone
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.mja.reyamf.common.model.Config as YAMFConfig

@AndroidEntryPoint
class SidebarMenuService() : LifecycleService() {
    companion object {
        const val TAG = "reYAMF_SideBar"
        const val SERVICE_NOTIFICATION_ID = 1
        const val SERVICE_NOTIF_CHANNEL_ID = "BatteryMonitorChannel"
    }

    @Inject
    lateinit var iReyamfRepository: IReyamfRepository

    lateinit var config: YAMFConfig
    private lateinit var binding: SidebarLayoutBinding
    private lateinit var params : WindowManager.LayoutParams
    private lateinit var windowManager: WindowManager

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0.toFloat()
    private var initialTouchY: Float = 0.toFloat()
    private var job: Job? = null
    private var movable = false
    private var swipeX = 0

    var userId = 0
    private lateinit var rvAdapter: SideBarAdapter
    private var orientation = 0
    private var isShown = false
    private var cardBgColor: ColorStateList = ColorStateList.valueOf(Color.WHITE)
    private var colorString = "#FFFFFF"

    private val _showApp: MutableLiveData<List<AppInfo>> = MutableLiveData()
    private val showApp: LiveData<List<AppInfo>> = _showApp
    private var isAnimating = false

    private var isServiceRunning = false

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent != null) {
            when (intent.action) {
                Action.START.name -> {
                    if (!isServiceRunning) {
                        isServiceRunning = true
                        initSidebar()
                    }
                }
                Action.STOP.name -> stopService()
                else -> Log.d("reYAMF", "No action in received intent")
            }
        } else {
            Log.d("reYAMF", "Null intent")
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        val restartServiceIntent = Intent(applicationContext, SidebarMenuService::class.java).also {
            it.setPackage(packageName)
        }

        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(this, 1, restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        applicationContext.getSystemService(ALARM_SERVICE)
        val alarmService: AlarmManager = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
    }

    private fun initSidebar() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_Reyamf)
        val inflater = LayoutInflater.from(themedContext)
        binding = SidebarLayoutBinding.inflate(inflater)

        config = gson.fromJson(YAMFManagerProxy.configJson, YAMFConfig::class.java)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        params.gravity = Gravity.NO_GRAVITY

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(binding.root, params)

        params.x = if (config.sidebarPosition) 100000 else -100000

        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        params.y = when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                orientation = 0
                config.portraitY
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                orientation = 1
                config.landscapeY
            }
            else -> 0
        }

        cardBgColor = binding.cvSideBarMenu.cardBackgroundColor

        colorString = if (config.sidebarTransparency == 100) {
            "#AAAAAA"
        } else {
            val alpha = (config.sidebarTransparency * 255 / 100)
            val alphaHex = String.format("%02X", alpha)
            "#${alphaHex}AAAAAA"
        }

        showApp.observe(this) { sidebarApp ->
            val receivedApps = mutableListOf<AppInfo>()
            YAMFManagerProxy.getAppListAsync(object : IAppListCallback.Stub() {
                override fun onAppListReceived(appList: MutableList<AppInfo>) {
                    receivedApps.addAll(appList)
                }

                override fun onAppListFinished() {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val commonApps = sidebarApp.filter { app1 ->
                            receivedApps.any { app2 ->
                                app1.activityInfo.componentName == app2.activityInfo.componentName
                            }
                        }.toMutableList()

                        rvAdapter.setData(commonApps)
                        rvAdapter.notifyDataSetChanged()
                        binding.rvSideBarMenu.scrollToPosition(0)
                        binding.cpiLoading.isVisible = false
                    }
                }
            })
        }

        handleSidebar()

        setServiceState(this, ServiceState.STARTED)
        startForeground(SERVICE_NOTIFICATION_ID, createNotification())
    }

    private fun handleSidebar() {
        binding.clickMask.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    storeTouchs(event)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    moveSidebar(event)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    openApp(event)
                    v.performClick()
                    true
                }

                else -> false
            }
        }

        binding.ivExtraTool.setOnClickListener {
            hideMenu()
        }

        binding.cvSideBarMenu.post {
            binding.cvSideBarMenu.setCardBackgroundColor(colorString.toColorInt())
            if (config.sidebarPosition) {
                binding.root.layoutDirection = View.LAYOUT_DIRECTION_RTL
                binding.rvSideBarMenu.layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
        }

        binding.ivExtraTool.setOnClickListener {
            animateAlpha(binding.cvExtraTool, 0f, 1f)

            true
        }

        binding.root.setOnClickListener {
            if (isAnimating) hideMenu()
        }

        binding.ibAppList.setOnClickListener {
            runMain {
                startService(Intent(this@SidebarMenuService, AppListWindow::class.java))
            }
            hideMenu()
        }

        binding.ibCurrentToWindow.setOnClickListener {
            YAMFManagerProxy.currentToWindow()
            hideMenu()
        }

        binding.ibKillSidebar.setOnClickListener {
            stopService()
        }

        binding.clickMask.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    storeTouchs(event)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    moveSidebar(event)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    openApp(event)
                    v.performClick()
                    true
                }

                else -> false
            }
        }

        binding.root.addOnLayoutChangeListener {  _, _, _, _, _, _, _, _, _ ->
            var newOrientation = 0
            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            val rotation = display?.rotation ?: Surface.ROTATION_0

            when (rotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> {
                    params.y = config.portraitY
                    windowManager.updateViewLayout(binding.root, params)
                    newOrientation = 0
                }
                Surface.ROTATION_90, Surface.ROTATION_270 -> {
                    params.y = config.landscapeY
                    windowManager.updateViewLayout(binding.root, params)
                    newOrientation = 1
                }
            }


            if (orientation != newOrientation) {
                orientation = newOrientation
            }
        }

        val longClickListener: (AppInfo) -> Unit = {
            lifecycleScope.launch(Dispatchers.IO) {
                iReyamfRepository.deleteAppInfo(it)

                runOnMainThread {
                    getSidebarApp()
                }
            }
        }

        val clickListener: (AppInfo) -> Unit = {
            hideMenu()
            lifecycleScope.launch {
                delay(200)
                YAMFManagerProxy.createWindowUserspace(it)
            }
        }

        rvAdapter = SideBarAdapter(clickListener, arrayListOf(), longClickListener)

        binding.rvSideBarMenu.layoutManager = LinearLayoutManager(this@SidebarMenuService)
        binding.rvSideBarMenu.adapter = rvAdapter
        binding.rvSideBarMenu.isVerticalScrollBarEnabled = false

        if (binding.rvSideBarMenu.itemDecorationCount == 0) {
            binding.rvSideBarMenu.addItemDecoration(VerticalSpaceItemDecoration(8.dpToPx().toInt()))
        }
    }

    private fun openApp(event: MotionEvent): Boolean {
        job?.cancel()
        movable = false
        if (initialTouchY == event.rawY) {
            vibratePhone(this)
            showMenu()
            isShown = false
        }

        if (swipeX > 200) {
            vibratePhone(this)
            showMenu()
            isShown = false
            swipeX = 0
        }
        return true
    }

    private fun moveSidebar(event: MotionEvent): Boolean {
        swipeX = event.rawX.toInt()
        params.y = initialY + (event.rawY - initialTouchY).toInt()

        if (movable) {
            windowManager.updateViewLayout(binding.root, params)

            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

            display?.let {
                when (it.rotation) {
                    Surface.ROTATION_0, Surface.ROTATION_180 -> {
                        config.portraitY = params.y
                    }
                    Surface.ROTATION_90, Surface.ROTATION_270 -> {
                        config.landscapeY = params.y
                    }
                }
            }

            YAMFManagerProxy.updateConfig(gson.toJson(config))
            config = gson.fromJson(YAMFManagerProxy.configJson, YAMFConfig::class.java)
        }
        return true
    }


    private fun storeTouchs(event: MotionEvent): Boolean {
        startCounter()
        initialX = params.x
        initialY = params.y
        initialTouchX = (event.rawX)
        initialTouchY = (event.rawY)
        return true
    }

    private fun startCounter() {
        job = CoroutineScope(Dispatchers.IO).launch {
            delay(200)
            movable = true
            vibratePhone(this@SidebarMenuService)
        }
    }

    private fun showMenu() {
        isAnimating = true
        if (!isShown) {
            isShown = true
            binding.root.elevation = 8.dpToPx()

            binding.cvSideBarMenu.setCardBackgroundColor(Color.TRANSPARENT)
            binding.cvSideBarMenu.strokeWidth = 1.dpToPx().toInt()

            binding.clickMask.visibility = View.GONE
            binding.cvSideBarMenu.setCardBackgroundColor(cardBgColor.defaultColor)

            val params = binding.root.layoutParams
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            binding.root.layoutParams = params

            val layout = binding.test
            val params1 = layout.layoutParams as ConstraintLayout.LayoutParams
            val position = if (orientation == 0) config.portraitY else config.landscapeY
            params1.topMargin =
                (Resources.getSystem().displayMetrics.heightPixels/2 + position) - 75.dpToPx().toInt()
            layout.layoutParams = params1

            lifecycleScope.launch {
                delay(200)
                animateResize(
                    binding.cvSideBarMenu,
                    3.dpToPx().toInt(), 70.dpToPx().toInt(),
                    75.dpToPx().toInt(), 350.dpToPx().toInt(), this@SidebarMenuService
                ) {
                    binding.sideBarMenu.visibility = View.VISIBLE
                    animateAlpha(binding.rvSideBarMenu, 0F, 1F)
                    getSidebarApp()
                }
            }
        }
    }

    private fun hideMenu() {
        isShown = false
        vibratePhone(this)
        binding.root.elevation = 0.dpToPx()

        animateAlpha(binding.rvSideBarMenu, 1F, 0F)

        if (binding.cvExtraTool.isVisible) {
            animateAlpha(binding.cvExtraTool, 1f, 0f) {
                animateResize(
                    binding.cvSideBarMenu,
                    70.dpToPx().toInt(), 3.dpToPx().toInt(),
                    350.dpToPx().toInt(), 75.dpToPx().toInt(), this
                ) {
                    binding.sideBarMenu.visibility = View.GONE
                    binding.clickMask.visibility = View.VISIBLE
                    binding.cvSideBarMenu.setCardBackgroundColor(colorString.toColorInt())
                    binding.cvSideBarMenu.strokeWidth = 0
                    binding.cvSideBarMenu.visibility = View.INVISIBLE

                    val params = binding.root.layoutParams
                    params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    binding.root.layoutParams = params

                    val layout = binding.test
                    val params1 = layout.layoutParams as ConstraintLayout.LayoutParams
                    params1.topMargin = 0
                    layout.layoutParams = params1

                    lifecycleScope.launch {
                        delay(400)
                        binding.cvSideBarMenu.visibility = View.VISIBLE
                    }
                }
            }
        } else {

            animateResize(
                binding.cvSideBarMenu,
                70.dpToPx().toInt(), 3.dpToPx().toInt(),
                350.dpToPx().toInt(), 75.dpToPx().toInt(), this
            ) {
                binding.sideBarMenu.visibility = View.GONE
                binding.clickMask.visibility = View.VISIBLE
                binding.cvSideBarMenu.setCardBackgroundColor(colorString.toColorInt())
                binding.cvSideBarMenu.strokeWidth = 0
                binding.cvSideBarMenu.visibility = View.INVISIBLE

                val params = binding.root.layoutParams
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                binding.root.layoutParams = params

                val layout = binding.test
                val params1 = layout.layoutParams as ConstraintLayout.LayoutParams
                params1.topMargin = 0
                layout.layoutParams = params1

                lifecycleScope.launch {
                    delay(400)
                    binding.cvSideBarMenu.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun getSidebarApp() {
        binding.cpiLoading.isVisible = true
        lifecycleScope.launch {
            iReyamfRepository.getAppInfoList().collect {
                _showApp.value = it
            }
        }
    }

    private fun createNotification(): Notification {
        val channelId = SERVICE_NOTIF_CHANNEL_ID
        val channel = NotificationChannel(
            channelId,
            "reYAMF Sidebar Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sidebar Service Running")
            .setContentText("reYAMF Sidebar Service")
            .build()
    }

    private fun stopService() {
        try {
            windowManager.removeView(binding.root)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.d("reYAMF", "Sidebar killed")
        }
        setServiceState(this, ServiceState.STOPPED)
    }
}