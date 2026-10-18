package com.example.taskassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.taskassistant.ui.MainViewModel
import com.example.taskassistant.ui.Screen
import com.example.taskassistant.ui.WorkoutServiceController
import com.example.taskassistant.ui.screens.AddTaskScreen
import com.example.taskassistant.ui.screens.BacklogScreen
import com.example.taskassistant.ui.screens.HistoryDetailScreen
import com.example.taskassistant.ui.screens.HistoryScreen
import com.example.taskassistant.ui.screens.HomeScreen
import com.example.taskassistant.ui.screens.PlanDetailScreen
import com.example.taskassistant.ui.screens.PlanEditScreen
import com.example.taskassistant.ui.screens.PlansScreen
import com.example.taskassistant.ui.screens.SettingsScreen
import com.example.taskassistant.ui.screens.WeekAssignScreen
import com.example.taskassistant.ui.screens.WorkoutScreen
import com.example.taskassistant.ui.theme.AppBackground
import com.example.taskassistant.ui.theme.TaskAssistantTheme
import com.example.taskassistant.workout.WorkoutTimerService
import kotlinx.coroutines.launch

/** 把前台服务 + 震动包装成 ViewModel 可用的桥接实现。 */
private class AndroidWorkoutServiceController(private val context: Context) : WorkoutServiceController {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    override fun canPostNotifications(): Boolean = WorkoutTimerService.canPostNotifications(context)

    override fun start(title: String, text: String): Boolean =
        WorkoutTimerService.start(context, title, text, canPostNotifications())

    override fun stop() = WorkoutTimerService.stop(context)

    override fun vibrateShort() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            @Suppress("DEPRECATION")
            VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { v.vibrate(effect) }
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val app = application as TaskAssistantApp
        MainViewModel.Factory(
            repository = app.repository,
            settingsRepository = app.settingsRepository,
            workoutService = AndroidWorkoutServiceController(applicationContext),
        )
    }

    /** Android 13+ 通知权限请求（前台服务需要可见通知才符合规范）。 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onNotificationPermissionResult(granted)
        }

    /** 数据导出：用 SAF 让用户选择保存位置（不需要存储权限）。 */
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = runCatching {
                val json = viewModel.buildExportJson()
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("无法打开目标文件")
            }
            if (result.isSuccess) viewModel.onExportSucceeded()
            else viewModel.onExportFailed(result.exceptionOrNull()?.message ?: "未知错误")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            TaskAssistantTheme {
                AppRoot(viewModel) { exportLauncher.launch(exportFileName()) }
            }
        }
    }

    private fun exportFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        return "task-assistant-$stamp.json"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * 顶层容器：对应原型的 switchTab / showPage 机制。
 *
 * 用状态驱动的 Compose 页面切换，而不是 Navigation 图 —— 这些页面是「同一数据集的不同视图」，
 * 不需要深链接/独立返回栈，状态驱动更简单，也不会出现返回栈错乱。
 */
@Composable
fun AppRoot(viewModel: MainViewModel, onExportRequest: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        val message = state.toast
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        when (val screen = state.screen) {
            Screen.Home -> HomeScreen(viewModel, state)
            Screen.Plans -> PlansScreen(viewModel, state)
            Screen.History -> HistoryScreen(viewModel, state)
            Screen.Settings -> SettingsScreen(viewModel, state, onExportRequest)
            is Screen.PlanDetail -> PlanDetailScreen(viewModel, state, screen.taskId)
            is Screen.PlanEdit -> PlanEditScreen(viewModel, state)
            is Screen.WeekAssign -> WeekAssignScreen(viewModel, state)
            is Screen.AddTask -> AddTaskScreen(viewModel, state)
            Screen.Workout -> WorkoutScreen(viewModel, state)
            is Screen.HistoryDetail -> HistoryDetailScreen(viewModel, state)
            Screen.Backlog -> BacklogScreen(viewModel, state)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) { data -> Snackbar(snackbarData = data, containerColor = Color(0xFF333333)) }
    }
}
