package com.example.ioslikelauncher

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class LaunchApp(
    val label: String,
    val packageName: String,
    val resolveInfo: ResolveInfo
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LauncherApp() }
    }
}

@Composable
private fun LauncherApp() {
    val context = LocalContext.current
    val pm = context.packageManager
    var apps by remember { mutableStateOf(emptyList<LaunchApp>()) }
    var search by remember { mutableStateOf("") }
    var showLibrary by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var showControlPanel by remember { mutableStateOf(false) }
    var showWidgets by remember { mutableStateOf(false) }

    fun loadApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val packageName = ri.activityInfo?.packageName ?: return@mapNotNull null
                LaunchApp(ri.loadLabel(pm).toString(), packageName, ri)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    LaunchedEffect(Unit) { loadApps() }
    BackHandler(showLibrary || showControlPanel || showWidgets || search.isNotEmpty()) {
        when {
            showControlPanel -> showControlPanel = false
            showWidgets -> showWidgets = false
            showLibrary -> { showLibrary = false; search = "" }
            else -> search = ""
        }
    }

    val wallpaper = Brush.linearGradient(
        listOf(Color(0xFF8BC7F5), Color(0xFFB8B6F4), Color(0xFFF0B7D3), Color(0xFFF5D4B0))
    )

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF087EFF),
        surface = Color(0xFFF7F7FA),
        background = Color(0xFFF7F7FA)
    )) {
        Box(
            Modifier.fillMaxSize().background(wallpaper)
                .pointerInput(showLibrary) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, amount -> drag += amount },
                        onDragEnd = {
                            if (drag < -80) page = (page + 1).coerceAtMost(2)
                            if (drag > 80) page = (page - 1).coerceAtLeast(0)
                            drag = 0f
                        }
                    )
                }
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                StatusBar(
                    onOpenControl = { showControlPanel = true },
                    onOpenSearch = { showLibrary = true; search = "" }
                )
                if (showControlPanel) {
                    ControlPanel(onClose = { showControlPanel = false })
                } else if (showWidgets) {
                    WidgetScreen(onClose = { showWidgets = false })
                } else if (showLibrary) {
                    AppLibrary(
                        apps = apps,
                        search = search,
                        onSearch = { search = it },
                        onLaunch = { launchApp(context, it.packageName) },
                        onClose = { showLibrary = false; search = "" }
                    )
                } else {
                    if (page == 0) {
                        WidgetCard(onClick = { showWidgets = true })
                    } else {
                        Spacer(Modifier.height(30.dp))
                        Text(
                            if (page == 1) "Seus apps favoritos" else "Biblioteca e atalhos",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 19.sp
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    val visibleApps = when (page) {
                        0 -> apps.take(8)
                        1 -> apps.drop(8).take(16)
                        else -> apps.drop(24).take(16)
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleApps, key = { it.packageName }) { app ->
                            AppIcon(app = app, onClick = { launchApp(context, app.packageName) })
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(3) { index ->
                            Box(
                                Modifier.padding(4.dp).size(if (page == index) 7.dp else 5.dp)
                                    .clip(CircleShape)
                                    .background(if (page == index) Color.White else Color.White.copy(alpha = .48f))
                            )
                        }
                    }
                    Dock(
                        apps = apps.take(4),
                        onLaunch = { launchApp(context, it.packageName) },
                        onLibrary = { showLibrary = true }
                    )
                }
            }
        }
    }
}

private fun launchApp(context: android.content.Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

@Composable
private fun StatusBar(onOpenControl: () -> Unit, onOpenSearch: () -> Unit) {
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(time, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SignalCellularAlt, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Default.Wifi, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Default.BatteryFull, null, tint = Color.White, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { onOpenControl() })
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Text(
            "Buscar",
            color = Color.White.copy(alpha = .88f),
            fontSize = 12.sp,
            modifier = Modifier.clickable { onOpenSearch() }.padding(4.dp)
        )
    }
}

@Composable
private fun AppIcon(app: LaunchApp, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Box(
            Modifier.size(58.dp).clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = .96f)),
            contentAlignment = Alignment.Center
        ) {
            val drawable = remember(app.packageName) { app.resolveInfo.loadIcon(context.packageManager) }
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { android.widget.ImageView(it).apply { setImageDrawable(drawable); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
                modifier = Modifier.size(46.dp)
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            app.label, color = Color.White, fontSize = 11.sp, maxLines = 1,
            textAlign = TextAlign.Center, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Dock(apps: List<LaunchApp>, onLaunch: (LaunchApp) -> Unit, onLibrary: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Color.White.copy(alpha = .30f))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        apps.forEach { app ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AppIcon(app = app, onClick = { onLaunch(app) })
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onLibrary() }) {
                Box(Modifier.size(54.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = .95f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Apps, null, tint = Color(0xFF3478F6), modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(5.dp))
                Text("Apps", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun WidgetCard(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = .24f))
            .clickable { onClick() }.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("TERÇA-FEIRA", color = Color.White.copy(alpha = .9f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                SimpleDateFormat("d 'de' MMMM", Locale("pt", "BR")).format(Date()).replaceFirstChar { it.uppercase() },
                color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold
            )
            Text("Toque para ver widgets", color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
        }
        Icon(Icons.Default.CalendarMonth, null, tint = Color.White, modifier = Modifier.size(42.dp))
    }
}

@Composable
private fun AppLibrary(
    apps: List<LaunchApp>,
    search: String,
    onSearch: (String) -> Unit,
    onLaunch: (LaunchApp) -> Unit,
    onClose: () -> Unit
) {
    val filtered = apps.filter { it.label.contains(search, ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Biblioteca de Apps", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null, tint = Color.White) }
        }
        OutlinedTextField(
            value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Buscar") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true, shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = .94f),
                unfocusedContainerColor = Color.White.copy(alpha = .88f),
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent
            )
        )
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(filtered, key = { it.packageName }) { app ->
                AppIcon(app = app, onClick = { onLaunch(app) })
            }
        }
    }
}

@Composable
private fun ControlPanel(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(18.dp).clip(RoundedCornerShape(30.dp))
            .background(Color(0xEDECEFF5)).padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Central de controle", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null) }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickToggle("Wi-Fi", Icons.Default.Wifi, Modifier.weight(1f))
            QuickToggle("Bluetooth", Icons.Default.Bluetooth, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickToggle("Foco", Icons.Default.DoNotDisturbOn, Modifier.weight(1f))
            QuickToggle("Lanterna", Icons.Default.FlashlightOn, Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        Text("Brilho", fontWeight = FontWeight.SemiBold)
        var brightness by remember { mutableFloatStateOf(.75f) }
        Slider(value = brightness, onValueChange = { brightness = it })
        Text("Volume", fontWeight = FontWeight.SemiBold)
        var volume by remember { mutableFloatStateOf(.55f) }
        Slider(value = volume, onValueChange = { volume = it })
        Text("Nota: estes controles são apenas visuais nesta versão.", fontSize = 12.sp, color = Color.DarkGray)
    }
}

@Composable
private fun QuickToggle(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    var active by remember { mutableStateOf(label == "Wi-Fi") }
    Column(
        modifier.height(94.dp).clip(RoundedCornerShape(20.dp))
            .background(if (active) Color(0xFF1877F2) else Color.White)
            .clickable { active = !active }.padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(icon, null, tint = if (active) Color.White else Color.DarkGray, modifier = Modifier.size(26.dp))
        Text(label, color = if (active) Color.White else Color.DarkGray, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WidgetScreen(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(18.dp).clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = .88f)).padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Widgets", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null) }
        }
        Text("Data", color = Color.Gray)
        Text(SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR")).format(Date()).replaceFirstChar { it.uppercase() }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Text("Sugestões", fontWeight = FontWeight.Bold)
        Text("• Adicione widgets de relógio, calendário e clima nas próximas versões.")
        Spacer(Modifier.height(12.dp))
        Text("Os widgets aqui são demonstrativos; widgets reais exigem integrações adicionais.", color = Color.Gray, fontSize = 12.sp)
    }
}
