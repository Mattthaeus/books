package com.example.reader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

val Accent = Color(0xFF4C6EF5)

data class Book(val id: String, val title: String, val author: String, val path: String,
                val ch: Int = 0, val off: Int = 0, val prog: Float = 0f)
class Chapter(val title: String, val paras: List<String>)
class Parsed(val title: String, val author: String, val chapters: List<Chapter>)

class Store(c: Context) {
    private val p = c.getSharedPreferences("reader", 0)
    fun books(): List<Book> {
        val a = JSONArray(p.getString("b", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Book(o.getString("id"), o.getString("t"), o.getString("a"), o.getString("p"),
                o.optInt("c"), o.optInt("o"), o.optDouble("g", 0.0).toFloat())
        }
    }
    fun save(l: List<Book>) = p.edit().putString("b", JSONArray(l.map {
        JSONObject().put("id", it.id).put("t", it.title).put("a", it.author).put("p", it.path)
            .put("c", it.ch).put("o", it.off).put("g", it.prog.toDouble())
    }).toString()).apply()
    fun update(b: Book) = save(books().map { if (it.id == b.id) b else it })
    var theme: Int get() = p.getInt("theme", 0); set(v) = p.edit().putInt("theme", v).apply()
    var size: Int get() = p.getInt("size", 20); set(v) = p.edit().putInt("size", v).apply()
    var spacing: Int get() = p.getInt("sp", 8); set(v) = p.edit().putInt("sp", v).apply()
}

// ---------- Парсинг (EPUB, TXT) ----------
fun attr(tag: String, n: String) = Regex("""\b$n\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)

fun htmlToParas(html: String) = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()
    .replace('\uFFFC', ' ').lines().map { it.trim() }.filter { it.isNotEmpty() }

fun toChapter(i: Int, paras: List<String>) =
    Chapter(paras.firstOrNull()?.takeIf { it.length <= 40 } ?: "Глава ${i + 1}", paras)

fun parseEpub(f: File): Parsed = ZipFile(f).use { z ->
    fun read(p: String) = z.getEntry(p)?.let { z.getInputStream(it).readBytes().toString(Charsets.UTF_8) } ?: ""
    val opfPath = attr(read("META-INF/container.xml"), "full-path") ?: error("Некорректный EPUB")
    val dir = opfPath.substringBeforeLast('/', "")
    val opf = read(opfPath)
    val title = Regex("<dc:title[^>]*>([^<]*)<").find(opf)?.groupValues?.get(1)?.trim() ?: f.nameWithoutExtension
    val author = Regex("<dc:creator[^>]*>([^<]*)<").find(opf)?.groupValues?.get(1)?.trim() ?: ""
    val manifest = Regex("<item\\s[^>]*>").findAll(opf).mapNotNull {
        val t = it.value; (attr(t, "id") ?: return@mapNotNull null) to (attr(t, "href") ?: return@mapNotNull null)
    }.toMap()
    val spine = Regex("<itemref[^>]*>").findAll(opf).mapNotNull { attr(it.value, "idref") }
    val chapters = spine.mapNotNull { manifest[it] }.map {
        val h = URLDecoder.decode(it, "UTF-8"); if (dir.isEmpty()) h else "$dir/$h"
    }.map { htmlToParas(read(it)) }.filter { it.isNotEmpty() }
        .mapIndexed { i, p -> toChapter(i, p) }.toList()
    Parsed(title, author, chapters)
}

fun parseTxt(f: File): Parsed {
    val paras = f.readText().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val chunks = mutableListOf<List<String>>(); var cur = mutableListOf<String>(); var n = 0
    for (p in paras) { cur.add(p); n += p.length; if (n > 15000) { chunks.add(cur); cur = mutableListOf(); n = 0 } }
    if (cur.isNotEmpty()) chunks.add(cur)
    return Parsed(f.nameWithoutExtension, "", chunks.mapIndexed { i, c -> Chapter("Часть ${i + 1}", c) })
}

fun parse(f: File) = if (f.name.endsWith(".epub")) parseEpub(f) else parseTxt(f)

fun importBook(c: Context, uri: Uri): Book {
    val name = c.contentResolver.query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    } ?: "book.txt"
    val id = System.currentTimeMillis().toString()
    val ext = if (name.endsWith(".epub", true)) "epub" else "txt"
    val f = File(c.filesDir, "$id.$ext")
    c.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
    val p = parse(f)
    return Book(id, p.title, p.author, f.absolutePath)
}

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { MaterialTheme { Root(Store(this)) } }
    }
}

@Composable fun Root(store: Store) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf(store.books()) }
    var open by remember { mutableStateOf<Book?>(null) }
    open?.let { b ->
        Reader(store, b) { books = store.books(); open = null }
    } ?: Library(books, onOpen = { open = it }, onAdd = { uri ->
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { importBook(ctx, uri) } }
                .onSuccess { store.save(store.books() + it); books = store.books() }
                .onFailure { android.widget.Toast.makeText(ctx, "Не удалось открыть файл", android.widget.Toast.LENGTH_LONG).show() }
        }
    })
}

@Composable fun Cover(b: Book, w: Dp, h: Dp) {
    val colors = listOf(0xFF1E88E5, 0xFF8E1B1B, 0xFFB08D57, 0xFF2E7D32, 0xFF6A1B9A)
    Box(Modifier.size(w, h).clip(RoundedCornerShape(10.dp))
        .background(Color(colors[Math.abs(b.id.hashCode()) % colors.size])).padding(8.dp)) {
        Text(b.title, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Serif, maxLines = 6)
    }
}

@Composable fun Library(books: List<Book>, onOpen: (Book) -> Unit, onAdd: (Uri) -> Unit) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onAdd) }
    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { pick.launch(arrayOf("application/epub+zip", "text/plain", "*/*")) },
            containerColor = Accent, contentColor = Color.White) { Text("+", fontSize = 28.sp) }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Мои книги", fontSize = 34.sp, modifier = Modifier.padding(vertical = 24.dp)) }
            if (books.isEmpty()) item { Text("Нажмите «+», чтобы загрузить EPUB или TXT", color = Color.Gray) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(books.filter { it.prog > 0 }.sortedByDescending { it.id }) { b ->
                        Column(Modifier.clickable { onOpen(b) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${(b.prog * 100).toInt()}%", color = Color.Gray)
                            Spacer(Modifier.height(6.dp)); Cover(b, 120.dp, 180.dp)
                        }
                    }
                }
            }
            items(books) { b ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(b) }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Cover(b, 70.dp, 105.dp)
                        Column {
                            Text(b.author, color = Color.Gray); Text(b.title, fontSize = 18.sp)
                            Text("${(b.prog * 100).toInt()}%", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Reader(store: Store, book: Book, onBack: () -> Unit) {
    var parsed by remember { mutableStateOf<Parsed?>(null) }
    LaunchedEffect(book.id) { parsed = withContext(Dispatchers.IO) { parse(File(book.path)) } }
    val p = parsed
    if (p == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; BackHandler(onBack = onBack); return }

    var ch by remember { mutableIntStateOf(book.ch.coerceIn(0, p.chapters.size - 1)) }
    var theme by remember { mutableIntStateOf(store.theme) }
    var size by remember { mutableIntStateOf(store.size) }
    var spacing by remember { mutableIntStateOf(store.spacing) }
    var bars by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(0) } // 0 нет, 1 оглавление, 2 настройки
    var restored by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val chapter = p.chapters[ch]
    val prog = (ch + list.firstVisibleItemIndex.toFloat() / maxOf(1, chapter.paras.size)) / p.chapters.size

    val bg = listOf(Color.White, Color(0xFFF5ECD9), Color(0xFF121212))[theme]
    val fg = listOf(Color(0xFF1A1A1A), Color(0xFF3B2F1E), Color(0xFFCFCFCF))[theme]

    fun persist() = store.update(book.copy(ch = ch, off = list.firstVisibleItemIndex, prog = prog))
    LaunchedEffect(ch) { list.scrollToItem(if (!restored) book.off else 0); restored = true }
    LaunchedEffect(Unit) { snapshotFlow { ch to list.firstVisibleItemIndex }.collect { if (restored) persist() } }
    BackHandler { persist(); onBack() }

    Box(Modifier.fillMaxSize().background(bg)) {
        LazyColumn(list, Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { bars = !bars }
            .statusBarsPadding(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            items(chapter.paras) { t ->
                Text(t, color = fg, modifier = Modifier.padding(bottom = spacing.dp), style = TextStyle(
                    fontFamily = FontFamily.Serif, fontSize = size.sp, lineHeight = (size * 1.45).sp,
                    textAlign = TextAlign.Justify, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph))
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), Arrangement.SpaceBetween) {
                    TextButton({ if (ch > 0) ch-- }, enabled = ch > 0) { Text("← Назад") }
                    TextButton({ if (ch < p.chapters.size - 1) ch++ }, enabled = ch < p.chapters.size - 1) { Text("Далее →") }
                }
            }
        }
        if (bars) {
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) {
                FilledIconButton({ persist(); onBack() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent)) { Text("←", color = Color.White, fontSize = 20.sp) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xFFF7F7F7)).navigationBarsPadding().padding(16.dp)) {
                Text("${(prog * 100).toInt()}%", Modifier.align(Alignment.CenterHorizontally))
                Slider(prog, { f -> ch = (f * p.chapters.size).toInt().coerceIn(0, p.chapters.size - 1) }, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
                Text(chapter.title, color = Color.DarkGray)
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    TextButton({ sheet = 1 }) { Text("☰ Оглавление", color = Accent) }
                    TextButton({ sheet = 2 }) { Text("Aa Настройки", color = Accent) }
                }
            }
        }
    }

    if (sheet != 0) ModalBottomSheet({ sheet = 0 }, containerColor = Color(0xFFF7F7F7)) {
        if (sheet == 1) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            item { Text("Содержание", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, fontSize = 20.sp) }
            itemsIndexed(p.chapters) { i, c ->
                Text(c.title, Modifier.fillMaxWidth().clickable { ch = i; sheet = 0; bars = false }.padding(20.dp),
                    color = if (i == ch) Accent else Color.Black, fontSize = 18.sp)
            }
        } else Column(Modifier.padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                listOf("White", "Sepia", "Black").forEachIndexed { i, n ->
                    OutlinedButton({ theme = i; store.theme = i }, Modifier.weight(1f),
                        border = BorderStroke(2.dp, if (theme == i) Accent else Color.Gray)) { Text(n) }
                }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Размер шрифта: $size", fontSize = 18.sp)
                Row { OutlinedButton({ size = (size - 1).coerceAtLeast(12); store.size = size }) { Text("−") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton({ size = (size + 1).coerceAtMost(40); store.size = size }) { Text("+") } }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Отступ абзацев: $spacing", fontSize = 18.sp)
                Row { OutlinedButton({ spacing = (spacing - 2).coerceAtLeast(0); store.spacing = spacing }) { Text("−") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton({ spacing = (spacing + 2).coerceAtMost(30); store.spacing = spacing }) { Text("+") } }
            }
        }
    }
}
