package com.example.ilauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil

data class AppEntry(
    val key: String, val label: String, val pkg: String,
    val component: ComponentName, val icon: Bitmap, val category: String
)

class Drag(val item: Item, val fromDock: Boolean)

/** Host de widget que detecta toque longo sem atrapalhar o widget. */
class LPHostView(ctx: Context, private val onLong: (View) -> Unit) : AppWidgetHostView(ctx) {
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) { onLong(this@LPHostView) }
    })
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean { gd.onTouchEvent(ev); return false }
}

class MaxHeightRV(ctx: Context, private val maxH: Int) : RecyclerView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var editBar: LinearLayout
    private lateinit var trash: TextView
    private lateinit var overlayHost: FrameLayout

    private val handler = Handler(Looper.getMainLooper())
    private val pagerAdapter = PagerAdapter()

    private var apps: Map<String, AppEntry> = emptyMap()
    private var home = HomeLayout(mutableListOf(mutableListOf()), mutableListOf())
    private var editMode = false
    private var drag: Drag? = null
    private var edgeRunnable: Runnable? = null
    private var pendingWidget = -1
    private var downX = 0f
    private var downY = 0f

    private val REQ_BIND = 11
    private val REQ_CONFIG = 12
    private val iconSizePx by lazy { dp(60) }
    private val categoryOrder = listOf(
        "Social", "Entretenimento", "Jogos", "Produtividade",
        "Finanças", "Compras e comida", "Viagens e trânsito", "Utilitários"
    )

    private val awm by lazy { AppWidgetManager.getInstance(this) }
    private val widgetHost by lazy {
        object : AppWidgetHost(this, 1024) {
            override fun onCreateView(
                context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?
            ): AppWidgetHostView = LPHostView(context) { v -> onWidgetLong(appWidgetId, v) }
        }
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = loadApps()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    // =====================================================================
    // Ciclo de vida
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, ime.bottom))
            insets
        }

        pager = findViewById(R.id.pager)
        dots = findViewById(R.id.dots)
        dock = findViewById(R.id.dock)
        editBar = findViewById(R.id.editBar)
        trash = findViewById(R.id.trash)
        overlayHost = findViewById(R.id.overlayHost)

        findViewById<TextView>(R.id.btnWidgets).apply {
            background = roundBg(0x44FFFFFF, 16); setOnClickListener { pickWidget() }
        }
        findViewById<TextView>(R.id.btnDone).apply {
            background = roundBg(0x44FFFFFF, 16); setOnClickListener { exitEdit() }
        }
        trash.background = roundBg(0x55FF3B30, 16)

        pager.offscreenPageLimit = 1
        pager.adapter = pagerAdapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { updateDots(position); updateBlur() }
        })

        setupDragTargets()

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        loadApps()
    }

    override fun onStart() { super.onStart(); try { widgetHost.startListening() } catch (_: Exception) {} }
    override fun onStop() { try { widgetHost.stopListening() } catch (_: Exception) {}; super.onStop() }
    override fun onDestroy() { unregisterReceiver(pkgReceiver); super.onDestroy() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            if (overlayHost.visibility == View.VISIBLE) closeOverlay()
            if (editMode) exitEdit()
            pager.setCurrentItem(0, true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            overlayHost.visibility == View.VISIBLE -> closeOverlay()
            editMode -> exitEdit()
            else -> pager.setCurrentItem(0, true)
        }
    }

    /** Puxar para baixo na tela inicial abre a busca. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!editMode && overlayHost.visibility != View.VISIBLE) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                MotionEvent.ACTION_UP -> {
                    val dy = ev.y - downY
                    val dx = ev.x - downX
                    if (dy > dp(110) && abs(dy) > abs(dx) * 2 &&
                        downY < resources.displayMetrics.heightPixels * 0.7f &&
                        pager.currentItem < home.pages.size
                    ) openSearch()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // =====================================================================
    // Carregar apps e sincronizar layout
    // =====================================================================

    private fun loadApps() {
        thread {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(main, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { ri ->
                    val ai = ri.activityInfo
                    AppEntry(
                        "${ai.packageName}/${ai.name}",
                        ri.loadLabel(pm).toString(),
                        ai.packageName,
                        ComponentName(ai.packageName, ai.name),
                        iosIcon(ri.loadIcon(pm)),
                        categoryOf(ai.packageName, ai.applicationInfo.category)
                    )
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { sync(list) }
        }
    }

    private fun sync(list: List<AppEntry>) {
        apps = list.associateBy { it.key }
        var l = Store.load(this)
        val firstRun = l == null
        if (l == null) l = HomeLayout(mutableListOf(mutableListOf()), mutableListOf())

        for (p in l.pages) {
            p.removeAll { (it is AppItem && it.key !in apps) || (it is WidgetItem && awm.getAppWidgetInfo(it.id) == null) }
            p.filterIsInstance<FolderItem>().forEach { f -> f.keys.removeAll { it !in apps } }
        }
        l.dock.removeAll { it !in apps }
        if (firstRun) l.dock.addAll(defaultDock(list))

        val placed = HashSet<String>(l.dock)
        for (p in l.pages) for (i in p) {
            if (i is AppItem) placed.add(i.key)
            if (i is FolderItem) placed.addAll(i.keys)
        }
        list.filter { it.key !in placed }.forEach { l.place(AppItem(it.key), 0) }

        l.normalize()
        l.trimEmpty()
        home = l
        Store.save(this, home)
        render()
    }

    private fun defaultDock(list: List<AppEntry>): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        return intents.mapNotNull {
            val pkg = packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
            list.firstOrNull { e -> e.pkg == pkg }?.key
        }.distinct().take(4)
    }

    private fun categoryOf(pkg: String, cat: Int): String {
        val p = pkg.lowercase()
        fun has(r: String) = Regex(r).containsMatchIn(p)
        return when {
            cat == ApplicationInfo.CATEGORY_GAME || has("game|defense|bullet|goose|proximabeta|horubrain") -> "Jogos"
            cat == ApplicationInfo.CATEGORY_SOCIAL || has("facebook|instagram|whatsapp|telegram|musically|twitter|snapchat|discord|dating|wattpad") -> "Social"
            has("bank|santander|picpay|mercadopago|intermedium|nubank|serasa|wallet|itau|bradesco") -> "Finanças"
            has("ifood|shopee|mercadolibre|mcdo|gringo|bomnegocio|amazon") -> "Compras e comida"
            cat == ApplicationInfo.CATEGORY_MAPS || has("uber|taxis|app99|waze|maps|sankuai|courier") -> "Viagens e trânsito"
            cat == ApplicationInfo.CATEGORY_VIDEO || cat == ApplicationInfo.CATEGORY_AUDIO ||
                cat == ApplicationInfo.CATEGORY_IMAGE || cat == ApplicationInfo.CATEGORY_NEWS ||
                has("netshort|opentune|music|video") -> "Entretenimento"
            cat == ApplicationInfo.CATEGORY_PRODUCTIVITY || has("office|word|docs|termux|openai") -> "Produtividade"
            else -> "Utilitários"
        }
    }

    // =====================================================================
    // Renderização
    // =====================================================================

    private fun curPage() = pager.currentItem.coerceIn(0, home.pages.size - 1)

    private fun persist() { home.normalize(); Store.save(this, home) }
    private fun commit() { persist(); render() }

    private fun render() {
        pagerAdapter.notifyDataSetChanged()
        if (pager.currentItem > home.pages.size) pager.setCurrentItem(home.pages.size, false)
        buildDots()
        renderDock()
        updateBlur()
    }

    private fun renderDock() {
        dock.removeAllViews()
        for (key in home.dock) {
            val e = apps[key] ?: continue
            val iv = ImageView(this).apply {
                setImageBitmap(e.icon); scaleType = ImageView.ScaleType.FIT_CENTER
            }
            wire(iv, AppItem(key), true, { launch(e) }, { showAppMenu(e, true) })
            dock.addView(iv, LinearLayout.LayoutParams(0, dp(60), 1f))
        }
    }

    private fun buildDots() {
        dots.removeAllViews()
        repeat(home.pages.size + 1) {
            dots.addView(View(this).apply {
                setBackgroundResource(R.drawable.dot); alpha = 0.4f
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
            })
        }
        updateDots(pager.currentItem)
    }

    private fun updateDots(sel: Int) {
        for (i in 0 until dots.childCount) dots.getChildAt(i).alpha = if (i == sel) 1f else 0.4f
    }

    /** Blur do papel de parede (Android 12+) na Biblioteca e nos painéis. */
    private fun updateBlur() {
        val want = overlayHost.visibility == View.VISIBLE || pager.currentItem == home.pages.size
        if (Build.VERSION.SDK_INT >= 31) {
            try { window.setBackgroundBlurRadius(if (want) 80 else 0) } catch (_: Throwable) {}
        }
    }

    // ---------- Pager ----------

    inner class PagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = home.pages.size + 1
        override fun getItemViewType(position: Int) = if (position == home.pages.size) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val lp = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val v: View = if (viewType == 0) PageView(parent.context).apply {
                layoutParams = lp; setPadding(dp(8), dp(8), dp(8), 0)
            } else ScrollView(parent.context).apply {
                layoutParams = lp; isVerticalScrollBarEnabled = false
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) == 0) bindPage(holder.itemView as PageView, position)
            else bindLibrary(holder.itemView as ScrollView)
        }
    }

    private fun bindPage(pv: PageView, page: Int) {
        pv.removeAllViews()
        pv.setOnDragListener { v, e ->
            if (e.action == DragEvent.ACTION_DROP) onPageDrop(page, v as PageView, e) else true
        }
        for (item in home.pages[page]) {
            viewFor(item)?.let { pv.addView(it, CellLP(item.row, item.col, item.sc, item.sr)) }
        }
    }

    private fun viewFor(item: Item): View? = when (item) {
        is AppItem -> apps[item.key]?.let { e ->
            appView(e.icon, e.label).also { v -> wire(v, item, false, { launch(e) }, { showAppMenu(e, false) }) }
        }
        is FolderItem -> appView(folderIcon(item.keys), item.name).also { v ->
            wire(v, item, false, { openFolder(item) }, { showFolderMenu(item) })
        }
        is WidgetItem -> widgetView(item)
    }

    private fun appView(icon: Bitmap, label: String): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_app, null, false)
        v.findViewById<ImageView>(R.id.icon).setImageBitmap(icon)
        v.findViewById<TextView>(R.id.label).text = label
        return v
    }

    private fun widgetView(item: WidgetItem): View? {
        val info = awm.getAppWidgetInfo(item.id) ?: return null
        val hv = widgetHost.createView(this, item.id, info)
        hv.setAppWidget(item.id, info)
        hv.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val d = resources.displayMetrics.density
            val w = ((r - l) / d).toInt(); val h = ((b - t) / d).toInt()
            (v as AppWidgetHostView).updateAppWidgetSize(null, w, h, w, h)
        }
        return hv
    }

    private fun wire(v: View, item: Item, fromDock: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
        v.setOnClickListener { if (!editMode || item is FolderItem) onClick() }
        v.setOnLongClickListener {
            if (editMode) startDrag(v, item, fromDock) else onMenu()
            true
        }
        if (editMode) v.startAnimation(RotateAnimation(
            -1.5f, 1.5f, Animation.RELATIVE_TO_SELF, .5f, Animation.RELATIVE_TO_SELF, .5f
        ).apply { duration = 110; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE })
    }

    // =====================================================================
    // Ações
    // =====================================================================

    private fun launch(e: AppEntry) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(e.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(i) } catch (_: Exception) {}
    }

    private fun MutableList<Pair<String, () -> Unit>>.opt(t: String, a: () -> Unit) { add(t to a) }

    private fun menu(title: String, opts: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }.show()
    }

    private fun showAppMenu(e: AppEntry, fromDock: Boolean) {
        val o = mutableListOf<Pair<String, () -> Unit>>()
        o.opt("Editar tela") { enterEdit() }
        if (fromDock) o.opt("Remover do dock") {
            home.dock.remove(e.key); home.place(AppItem(e.key), curPage()); commit()
        } else if (home.dock.size < 4) o.opt("Fixar no dock") {
            home.pages.forEach { p -> p.removeAll { it is AppItem && it.key == e.key } }
            home.dock.add(e.key); commit()
        }
        o.opt("Informações do app") { appInfo(e) }
        o.opt("Desinstalar") { uninstall(e) }
        menu(e.label, o)
    }

    private fun entryMenu(e: AppEntry) {
        menu(e.label, listOf("Informações do app" to { appInfo(e) }, "Desinstalar" to { uninstall(e) }))
    }

    private fun appInfo(e: AppEntry) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")))
    }

    private fun uninstall(e: AppEntry) {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.pkg}")))
    }

    private fun showFolderMenu(f: FolderItem) {
        val o = mutableListOf<Pair<String, () -> Unit>>()
        o.opt("Editar tela") { enterEdit() }
        o.opt("Renomear") { renameFolder(f) }
        o.opt("Desfazer pasta") { dissolveFolder(f) }
        menu(f.name, o)
    }

    private fun dissolveFolder(f: FolderItem) {
        val page = home.pages.indexOfFirst { f in it }.coerceAtLeast(0)
        home.remove(f)
        f.keys.forEach { home.place(AppItem(it), page) }
        commit()
    }

    private fun renameFolder(f: FolderItem) {
        val et = EditText(this).apply { setText(f.name) }
        AlertDialog.Builder(this).setTitle("Renomear pasta").setView(et)
            .setPositiveButton("OK") { _, _ ->
                f.name = et.text.toString().ifBlank { "Pasta" }
                closeOverlay(); commit()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun onWidgetLong(id: Int, v: View) {
        val w = home.pages.flatten().filterIsInstance<WidgetItem>().firstOrNull { it.id == id } ?: return
        if (editMode) { startDrag(v, w, false); return }
        val o = mutableListOf<Pair<String, () -> Unit>>()
        o.opt("Editar tela") { enterEdit() }
        o.opt("Remover widget") {
            home.remove(w); widgetHost.deleteAppWidgetId(w.id); commit()
        }
        menu("Widget", o)
    }

    // =====================================================================
    // Modo de edição + arrastar e soltar
    // =====================================================================

    private fun enterEdit() { editMode = true; editBar.visibility = View.VISIBLE; render() }

    private fun exitEdit() {
        editMode = false; editBar.visibility = View.GONE
        home.trimEmpty(); persist(); render()
    }

    private fun startDrag(v: View, item: Item, fromDock: Boolean) {
        drag = Drag(item, fromDock)
        v.startDragAndDrop(ClipData.newPlainText("i", "i"), View.DragShadowBuilder(v), null, 0)
    }

    private fun detach(d: Drag) {
        if (d.fromDock) home.dock.remove((d.item as AppItem).key) else home.remove(d.item)
    }

    private fun onPageDrop(page: Int, pv: PageView, e: DragEvent): Boolean {
        val d = drag ?: return false
        val item = d.item
        var c = ((e.x - pv.paddingLeft) / pv.cw).toInt().coerceIn(0, COLS - 1)
        var r = ((e.y - pv.paddingTop) / pv.ch).toInt().coerceIn(0, ROWS - 1)
        c = c.coerceAtMost(COLS - item.sc)
        r = r.coerceAtMost(ROWS - item.sr)
        val target = home.itemAt(page, r, c, item)
        when {
            target == null -> {
                if (!home.isFree(page, r, c, item.sc, item.sr, item)) return false
                detach(d); item.row = r; item.col = c; home.pages[page].add(item)
            }
            target is AppItem && item is AppItem && target !== item -> {
                detach(d)
                val f = FolderItem("Pasta", mutableListOf(target.key, item.key))
                f.row = target.row; f.col = target.col
                home.pages[page].remove(target); home.pages[page].add(f)
            }
            target is FolderItem && item is AppItem -> { detach(d); target.keys.add(item.key) }
            else -> return false
        }
        persist()
        return true
    }

    private fun setupDragTargets() {
        // Dock
        dock.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> drag?.item is AppItem
                DragEvent.ACTION_DROP -> {
                    val d = drag
                    val item = d?.item as? AppItem
                    if (d == null || item == null || (!d.fromDock && home.dock.size >= 4)) false
                    else {
                        detach(d)
                        val idx = (e.x / (dock.width / 4f)).toInt().coerceIn(0, home.dock.size)
                        home.dock.add(idx, item.key); persist(); true
                    }
                }
                else -> true
            }
        }
        // Lixeira
        trash.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DROP -> {
                    val d = drag
                    if (d != null) when (val it = d.item) {
                        is WidgetItem -> { detach(d); widgetHost.deleteAppWidgetId(it.id) }
                        is FolderItem -> { detach(d); it.keys.forEach { k -> home.place(AppItem(k), curPage()) } }
                        is AppItem -> apps[it.key]?.let { app -> uninstall(app) }
                    }
                    persist(); true
                }
                else -> true
            }
        }
        // Bordas: segurar para trocar de página
        findViewById<View>(R.id.edgeL).setOnDragListener(edgeListener(-1))
        findViewById<View>(R.id.edgeR).setOnDragListener(edgeListener(1))
    }

    private fun edgeListener(dir: Int) = View.OnDragListener { _, e ->
        when (e.action) {
            DragEvent.ACTION_DRAG_ENTERED -> startEdge(dir)
            DragEvent.ACTION_DRAG_EXITED -> stopEdge()
            DragEvent.ACTION_DRAG_ENDED -> {
                stopEdge()
                if (dir < 0) { drag = null; render() }
            }
        }
        true
    }

    private fun startEdge(dir: Int) {
        stopEdge()
        val r = object : Runnable {
            override fun run() { edgeStep(dir); handler.postDelayed(this, 900) }
        }
        edgeRunnable = r
        handler.postDelayed(r, 600)
    }

    private fun stopEdge() { edgeRunnable?.let { handler.removeCallbacks(it) }; edgeRunnable = null }

    private fun edgeStep(dir: Int) {
        val cur = pager.currentItem
        val n = home.pages.size
        if (dir < 0) { if (cur > 0) pager.setCurrentItem(cur - 1, true) }
        else if (cur < n - 1) pager.setCurrentItem(cur + 1, true)
        else if (cur == n - 1 && home.pages[cur].isNotEmpty() && n < 8) {
            home.pages.add(mutableListOf())
            pagerAdapter.notifyDataSetChanged(); buildDots()
            pager.setCurrentItem(cur + 1, true)
        }
    }

    // =====================================================================
    // Widgets
    // =====================================================================

    private fun pickWidget() {
        val list = awm.installedProviders.sortedBy { it.loadLabel(packageManager).lowercase() }
        val names = list.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Adicionar widget")
            .setItems(names) { _, i -> bindWidget(list[i]) }.show()
    }

    private fun bindWidget(p: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidget = id
        if (awm.bindAppWidgetIdIfAllowed(id, p.provider)) configureOrAdd(id)
        else startActivityForResult(
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, p.provider), REQ_BIND
        )
    }

    private fun configureOrAdd(id: Int) {
        val info = awm.getAppWidgetInfo(id)
        if (info?.configure != null) {
            widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null)
        } else addWidget(id)
    }

    private fun addWidget(id: Int) {
        val info = awm.getAppWidgetInfo(id) ?: return
        val cwPx = pager.width / COLS.toFloat()
        val chPx = pager.height / ROWS.toFloat()
        var sc = ceil(info.minWidth / cwPx).toInt()
        var sr = ceil(info.minHeight / chPx).toInt()
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            sc = info.targetCellWidth; sr = info.targetCellHeight
        }
        val w = WidgetItem(id, sc.coerceIn(1, COLS), sr.coerceIn(1, 4))
        home.place(w, curPage())
        commit()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val ok = resultCode == RESULT_OK
        when (requestCode) {
            REQ_BIND -> if (ok) configureOrAdd(pendingWidget) else widgetHost.deleteAppWidgetId(pendingWidget)
            REQ_CONFIG -> if (ok) addWidget(pendingWidget) else widgetHost.deleteAppWidgetId(pendingWidget)
        }
    }

    // =====================================================================
    // Pastas, Biblioteca de Apps e Busca
    // =====================================================================

    private fun showOverlay() { overlayHost.visibility = View.VISIBLE; updateBlur() }

    private fun closeOverlay() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(overlayHost.windowToken, 0)
        overlayHost.visibility = View.GONE
        overlayHost.removeAllViews()
        overlayHost.setOnClickListener(null)
        overlayHost.isClickable = false
        updateBlur()
    }

    private fun openFolder(f: FolderItem) {
        openGridOverlay(f.name, f.keys.mapNotNull { apps[it] }, f)
    }

    private fun openGridOverlay(title: String, list: List<AppEntry>, folder: FolderItem?) {
        overlayHost.removeAllViews()
        overlayHost.setBackgroundColor(0x99000000.toInt())
        overlayHost.setOnClickListener { closeOverlay() }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(0xCC3A3A3C.toInt(), 28)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
        }
        panel.addView(TextView(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
            if (folder != null) setOnClickListener { renameFolder(folder) }
        })
        val rv = MaxHeightRV(this, (resources.displayMetrics.heightPixels * 0.6f).toInt()).apply {
            layoutManager = GridLayoutManager(context, 3)
            adapter = AppRows(list, true, { closeOverlay(); launch(it) }, { e ->
                if (folder == null) entryMenu(e) else menu(e.label, listOf(
                    "Remover da pasta" to {
                        folder.keys.remove(e.key)
                        val pg = home.pages.indexOfFirst { folder in it }.coerceAtLeast(0)
                        home.place(AppItem(e.key), pg)
                        closeOverlay(); commit()
                    },
                    "Informações do app" to { appInfo(e) },
                    "Desinstalar" to { uninstall(e) }
                ))
            })
        }
        panel.addView(rv, LinearLayout.LayoutParams(-1, -2))
        overlayHost.addView(panel, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(), -2, Gravity.CENTER))
        showOverlay()
    }

    private fun openSearch() {
        overlayHost.removeAllViews()
        overlayHost.setBackgroundColor(0xB3000000.toInt())
        overlayHost.setOnClickListener(null)
        overlayHost.isClickable = true

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), 0)
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(this).apply {
            hint = "Buscar"; setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE)
            background = roundBg(0x33FFFFFF, 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "Cancelar"; setTextColor(Color.WHITE); setPadding(dp(12), dp(8), 0, dp(8))
            setOnClickListener { closeOverlay() }
        })
        val rows = AppRows(apps.values.sortedBy { it.label.lowercase() }, false,
            { closeOverlay(); launch(it) }, { entryMenu(it) })
        val rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context); adapter = rows }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { rows.filter(s?.toString() ?: "") }
        })
        col.addView(row)
        col.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        overlayHost.addView(col, FrameLayout.LayoutParams(-1, -1))
        showOverlay()
        et.requestFocus()
        et.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(et, 0) }
    }

    private fun bindLibrary(sv: ScrollView) {
        sv.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(16))
        }
        col.addView(TextView(this).apply {
            text = "Biblioteca de Apps"; setTextColor(Color.WHITE); textSize = 26f
            setTypeface(typeface, Typeface.BOLD); setPadding(dp(6), dp(4), 0, dp(10))
        })
        col.addView(TextView(this).apply {
            text = "Buscar"; setTextColor(0xCCFFFFFF.toInt()); textSize = 16f
            background = roundBg(0x40FFFFFF, 16)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { openSearch() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val groups = apps.values.groupBy { it.category }.toList().sortedBy {
            categoryOrder.indexOf(it.first).let { i -> if (i < 0) 99 else i }
        }
        for (pair in groups.chunked(2)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((name, list) in pair) {
                line.addView(categoryCard(name, list.sortedBy { it.label.lowercase() }),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(6), dp(4), dp(10)) })
            }
            if (pair.size == 1) line.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
            col.addView(line)
        }
        sv.addView(col, FrameLayout.LayoutParams(-1, -2))
    }

    private fun categoryCard(name: String, list: List<AppEntry>): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val card = GridLayout(this).apply {
            columnCount = 2; rowCount = 2
            background = roundBg(0x40FFFFFF, 26)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { openGridOverlay(name, list, null) }
        }
        fun cellLp() = GridLayout.LayoutParams().apply { width = dp(44); height = dp(44); setMargins(dp(4), dp(4), dp(4), dp(4)) }
        list.take(if (list.size > 4) 3 else 4).forEach { e ->
            card.addView(ImageView(this).apply {
                setImageBitmap(e.icon)
                setOnClickListener { launch(e) }
                setOnLongClickListener { entryMenu(e); true }
            }, cellLp())
        }
        if (list.size > 4) card.addView(TextView(this).apply {
            text = "+${list.size - 3}"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 14f
            background = roundBg(0x33FFFFFF, 10)
            setOnClickListener { openGridOverlay(name, list, null) }
        }, cellLp())
        wrap.addView(card)
        wrap.addView(TextView(this).apply {
            text = name; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        return wrap
    }

    /** Adapter reutilizável: grade (item_app) ou lista (ícone + nome). */
    inner class AppRows(
        private val source: List<AppEntry>, private val grid: Boolean,
        private val onClick: (AppEntry) -> Unit, private val onLong: (AppEntry) -> Unit
    ) : RecyclerView.Adapter<AppRows.VH>() {

        private var shown = source

        inner class VH(v: View, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(v)

        fun filter(q: String) {
            val s = q.trim().lowercase()
            shown = if (s.isEmpty()) source else source.filter { it.label.lowercase().contains(s) }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            if (grid) {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
                return VH(v, v.findViewById(R.id.icon), v.findViewById(R.id.label))
            }
            val ctx = parent.context
            val icon = ImageView(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)) }
            val label = TextView(ctx).apply {
                setTextColor(Color.WHITE); textSize = 16f; setPadding(dp(14), 0, 0, 0)
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                addView(icon); addView(label)
            }
            return VH(row, icon, label)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = shown[position]
            h.icon.setImageBitmap(e.icon)
            h.label.text = e.label
            h.itemView.setOnClickListener { onClick(e) }
            h.itemView.setOnLongClickListener { onLong(e); true }
        }

        override fun getItemCount() = shown.size
    }

    // =====================================================================
    // Ícones estilo iOS
    // =====================================================================

    private fun iosIcon(d: Drawable): Bitmap {
        val s = iconSizePx
        val raw = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(raw)
        if (d is AdaptiveIconDrawable) {
            val off = (s * 0.25f).toInt()
            d.background?.let { it.setBounds(-off, -off, s + off, s + off); it.draw(c) }
            d.foreground?.let { it.setBounds(-off, -off, s + off, s + off); it.draw(c) }
        } else {
            c.drawColor(Color.WHITE)
            val pad = (s * 0.14f).toInt()
            d.setBounds(pad, pad, s - pad, s - pad); d.draw(c)
        }
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = s * 0.2237f
        Canvas(out).drawRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), r, r, paint)
        return out
    }

    private fun folderIcon(keys: List<String>): Bitmap {
        val s = iconSizePx
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = s * 0.2237f
        c.drawRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF })
        val pad = s * 0.1f
        val gap = s * 0.04f
        val m = (s - 2 * pad - 2 * gap) / 3f
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        keys.take(9).forEachIndexed { i, k ->
            val e = apps[k] ?: return@forEachIndexed
            val x = pad + (i % 3) * (m + gap)
            val y = pad + (i / 3) * (m + gap)
            c.drawBitmap(e.icon, null, RectF(x, y, x + m, y + m), p)
        }
        return bmp
    }
}
