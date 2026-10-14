@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.appproject //

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/* ───────────────────────────── COLOURS ───────────────────────────── */

private val Green = Color(0xFF1B9E6A)
private val GreenDark = Color(0xFF0B5E40)
private val Orange = Color(0xFFFF7A2F)
private val OrangeDark = Color(0xFFD9531A)
private val Amber = Color(0xFFF5A524)
private val AmberDark = Color(0xFFD27F00)
private val Purple = Color(0xFF7C5CFC)
private val PurpleDark = Color(0xFF5237D1)
private val Blue = Color(0xFF2F8DF5)
private val BlueDark = Color(0xFF1560C4)

/* ───────────────────────────── DATA ───────────────────────────── */

data class Food(val meal: Int, val name: String, val kcal: Int)

data class Lift(val name: String, val sets: Int, val reps: Int, val kg: Float) {
    val volume: Float get() = sets * reps * kg
}

data class Insight(val emoji: String, val title: String, val body: String, val color: Color)

private val MEALS = listOf("Breakfast" to "🍳", "Lunch" to "🍛", "Dinner" to "🍽️", "Snacks" to "🍎")

private fun Float.fmt(d: Int = 0): String = String.format(Locale.US, "%.${d}f", this)
private fun String.clean(): String = replace("|", " ").replace("\n", " ").trim()
private fun sha(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/* Rough estimates, not medical advice */
fun calcDelta(w: Float, g: Float, wk: Int): Float =
    if (w > 0f && g > 0f && wk > 0) ((g - w) * 7700f / (wk * 7f)).coerceIn(-1000f, 700f) else 0f

fun calcTarget(w: Float, g: Float, wk: Int): Float = (w * 26f + calcDelta(w, g, wk)).coerceAtLeast(1200f)
fun calcWater(w: Float): Int = (w * 35f).coerceAtLeast(1500f).toInt()

/* ───────────────────────────── STORE ─────────────────────────────
 * State holder backed by SharedPreferences (everything stays on the device).
 */

class FitStore(context: Context) {
    private val prefs = context.getSharedPreferences("fit_prefs", Context.MODE_PRIVATE)
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    var loggedIn by mutableStateOf(prefs.getBoolean("loggedIn", false))
        private set
    var email by mutableStateOf(prefs.getString("email", "") ?: "")
        private set
    var weight by mutableStateOf(prefs.getFloat("weight", 0f))
        private set
    var goal by mutableStateOf(prefs.getFloat("goal", 0f))
        private set
    var weeks by mutableStateOf(prefs.getInt("weeks", 0))
        private set

    var steps by mutableStateOf(prefs.getInt("steps_$day", 0))
        @JvmName("setStepsInternal") private set
    var waterMl by mutableStateOf(prefs.getInt("water_$day", 0))
        private set
    val foods = mutableStateListOf<Food>()
    val lifts = mutableStateListOf<Lift>()

    init {
        prefs.getString("foods_$day", "")!!.lines().filter { it.isNotBlank() }.forEach {
            val p = it.split("|")
            if (p.size == 3) {
                val m = p[0].toIntOrNull(); val k = p[2].toIntOrNull()
                if (m != null && k != null) foods.add(Food(m.coerceIn(0, 3), p[1], k))
            } else if (p.size == 2) {
                val k = p[1].toIntOrNull()
                if (k != null) foods.add(Food(3, p[0], k))
            }
        }
        prefs.getString("lifts_$day", "")!!.lines().filter { it.isNotBlank() }.forEach {
            val p = it.split("|")
            if (p.size == 4) {
                val s = p[1].toIntOrNull(); val r = p[2].toIntOrNull(); val k = p[3].toFloatOrNull()
                if (s != null && r != null && k != null) lifts.add(Lift(p[0], s, r, k))
            }
        }
    }

    val hasProfile get() = weight > 0f && goal > 0f && weeks > 0
    val displayName: String
        get() = email.substringBefore("@").ifBlank { "there" }.replaceFirstChar { it.uppercase() }

    /* ---- auth (local only) ---- */
    fun hasAccount() = prefs.contains("pass")

    /** Returns null on success, or an error message. */
    fun loginOrSignUp(mail: String, pass: String): String? {
        if (!mail.contains("@") || mail.length < 5) return "Enter a valid email"
        if (pass.length < 4) return "Password must be at least 4 characters"
        if (!hasAccount()) {
            prefs.edit().putString("email", mail.trim()).putString("pass", sha(pass)).putBoolean("loggedIn", true).apply()
        } else {
            if (prefs.getString("email", "") != mail.trim() || prefs.getString("pass", "") != sha(pass))
                return "Wrong email or password"
            prefs.edit().putBoolean("loggedIn", true).apply()
        }
        email = mail.trim(); loggedIn = true
        return null
    }

    fun logout() {
        prefs.edit().putBoolean("loggedIn", false).apply()
        loggedIn = false
    }

    fun saveProfile(w: Float, g: Float, wk: Int) {
        weight = w; goal = g; weeks = wk
        prefs.edit().putFloat("weight", w).putFloat("goal", g).putInt("weeks", wk).apply()
    }

    /* ---- calculations ---- */
    fun targetKcal() = calcTarget(weight, goal, weeks)
    fun eatenKcal() = foods.sumOf { it.kcal }.toFloat()
    fun stepKcal() = steps * weight * 0.00057f // ~0.04 kcal/step at 70 kg
    fun budgetKcal() = targetKcal() + stepKcal()
    fun remainingKcal() = budgetKcal() - eatenKcal()
    fun waterGoalMl() = calcWater(weight)
    fun weeklyChange() = if (weeks > 0) abs(goal - weight) / weeks else 0f
    fun totalVolume() = lifts.sumOf { it.volume.toDouble() }.toFloat()

    /* ---- mutations ---- */
    fun addFood(meal: Int, name: String, kcal: Int) { foods.add(Food(meal, name.clean(), kcal)); persist() }
    fun removeFood(f: Food) { foods.remove(f); persist() }
    fun setSteps(v: Int) { steps = v.coerceAtLeast(0); persist() }
    fun addWater(ml: Int) { waterMl = (waterMl + ml).coerceAtLeast(0); persist() }
    fun addLift(l: Lift) { lifts.add(l.copy(name = l.name.clean())); persist() }
    fun removeLift(l: Lift) { lifts.remove(l); persist() }

    private fun persist() {
        prefs.edit()
            .putInt("steps_$day", steps)
            .putInt("water_$day", waterMl)
            .putString("foods_$day", foods.joinToString("\n") { "${it.meal}|${it.name}|${it.kcal}" })
            .putString("lifts_$day", lifts.joinToString("\n") { "${it.name}|${it.sets}|${it.reps}|${it.kg}" })
            .apply()
    }

    /* ---- insights ---- */
    private val tips = listOf(
        "Protein at every meal keeps hunger down and protects muscle while you cut.",
        "Progressive overload: add 1 rep or 1–2 kg to a lift each week.",
        "Sleep 7–9 hours. Poor sleep raises hunger hormones and hurts recovery.",
        "Drink a glass of water before meals. It helps you notice real hunger.",
        "A 10-minute walk after meals helps digestion and blood sugar.",
        "Rest days are when muscles actually grow. Don't skip them.",
        "Weigh yourself at the same time each morning and watch the weekly trend.",
        "Prep tomorrow's meals tonight. Planning beats willpower.",
        "Warm up with 2 light sets before your first heavy set to protect your joints.",
        "Fibre (dal, vegetables, oats) is the cheapest way to feel full on fewer calories."
    )

    fun insights(): List<Insight> {
        val out = mutableListOf<Insight>()
        val rem = remainingKcal()
        out += when {
            foods.isEmpty() -> Insight("🍽️", "Calories", "No meals logged yet. Log your first meal to see how today's budget is going.", Orange)
            rem < 0 -> Insight("⚠️", "Over budget", "You're ${(-rem).toInt()} kcal over. A 20–30 min walk or a lighter dinner balances it.", Orange)
            else -> Insight("✅", "On track", "You have ${rem.toInt()} kcal left. Spread them across your remaining meals.", Orange)
        }
        val wPct = waterMl * 100 / waterGoalMl()
        out += when {
            wPct >= 100 -> Insight("💧", "Hydrated", "Water goal reached. Nice work.", Blue)
            wPct >= 50 -> Insight("💧", "Water", "You're $wPct% of the way. Keep sipping through the afternoon.", Blue)
            else -> Insight("💧", "Water", "Only $wPct% so far. Try a glass right now.", Blue)
        }
        out += when {
            steps >= 10000 -> Insight("👟", "Steps", "10,000+ steps today, about ${stepKcal().toInt()} kcal burned.", Amber)
            steps > 0 -> Insight("👟", "Steps", "${10000 - steps} more steps to hit 10,000 today.", Amber)
            else -> Insight("👟", "Steps", "No steps entered yet. Even a short walk adds up.", Amber)
        }
        out += if (lifts.isEmpty()) Insight("🏋️", "Strength", "No session logged today. Even 3 compound lifts count.", Purple)
        else Insight("🏋️", "Strength", "${totalVolume().toInt()} kg total volume lifted. Get some protein in within a few hours.", Purple)
        out += Insight("💡", "Tip of the day", tips[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % tips.size], Green)
        return out
    }
}

/* ───────────────────────────── ACTIVITY ───────────────────────────── */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = FitStore(applicationContext)
        setContent {
            val scheme = if (isSystemInDarkTheme())
                darkColorScheme(
                    primary = Color(0xFF4CC38A), background = Color(0xFF0E1411), surface = Color(0xFF18201B),
                    onBackground = Color(0xFFE6EFEA), onSurface = Color(0xFFE6EFEA)
                )
            else
                lightColorScheme(
                    primary = Green, background = Color(0xFFF3F7F5), surface = Color.White,
                    onBackground = Color(0xFF14201A), onSurface = Color(0xFF14201A)
                )
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { FitApp(store) }
            }
        }
    }
}

@Composable
fun FitApp(s: FitStore) {
    var editingProfile by remember { mutableStateOf(false) }
    when {
        !s.loggedIn -> LoginScreen(s)
        !s.hasProfile || editingProfile -> SetupScreen(s, onDone = { editingProfile = false })
        else -> MainScaffold(s, onEditGoal = { editingProfile = true })
    }
}

/* ───────────────────────────── REUSABLE UI ───────────────────────────── */

@Composable
fun GradientHeader(
    top: Color, bottom: Color, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        content = content
    )
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun Ring(
    progress: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 12.dp,
    track: Color = color.copy(alpha = 0.18f), content: @Composable BoxScope.() -> Unit = {}
) {
    val anim by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val tl = Offset(sw / 2, sw / 2)
            val sz = Size(size.width - sw, size.height - sw)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            if (anim > 0.001f)
                drawArc(color, -90f, 360f * anim, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
fun NumField(
    value: String, onChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, decimal: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && it == '.') }) },
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
fun EmojiBadge(emoji: String, color: Color, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) { Text(emoji, fontSize = (size.value * 0.45f).sp) }
}

@Composable
fun HeaderStat(label: String, value: String) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatBlock(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
fun EmptyState(emoji: String, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 40.sp)
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(
            body, textAlign = TextAlign.Center, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when { h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; else -> "Good evening" }
}

/* ───────────────────────────── LOGIN ───────────────────────────── */

@Composable
fun LoginScreen(s: FitStore) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val newUser = !s.hasAccount()

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(GreenDark, Green))).imePadding()
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().statusBarsPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) { Text("💪", fontSize = 42.sp) }
            Spacer(Modifier.height(12.dp))
            Text("FitTrack", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            Text("Eat smart. Move more. Hit your goal.", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (newUser) "Create your account" else "Welcome back",
                fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                email, { email = it; error = null }, label = { Text("Email") }, singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                pass, { pass = it; error = null }, label = { Text("Password") }, singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Button(
                onClick = { error = s.loginOrSignUp(email, pass) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text(if (newUser) "Sign up" else "Log in", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Text(
                "Your account is stored only on this device.",
                fontSize = 12.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/* ───────────────────────────── GOAL SETUP ───────────────────────────── */

@Composable
fun SetupScreen(s: FitStore, onDone: () -> Unit) {
    var w by remember { mutableStateOf(if (s.weight > 0) s.weight.fmt(1) else "") }
    var g by remember { mutableStateOf(if (s.goal > 0) s.goal.fmt(1) else "") }
    var wk by remember { mutableStateOf(if (s.weeks > 0) s.weeks.toString() else "") }

    val wf = w.toFloatOrNull(); val gf = g.toFloatOrNull(); val wki = wk.toIntOrNull()
    val valid = wf != null && gf != null && wki != null && wf > 20 && gf > 20 && wki > 0
    val rate = if (valid) abs(wf!! - gf!!) / wki!! else 0f

    Column(Modifier.fillMaxSize().imePadding()) {
        GradientHeader(GreenDark, Green) {
            Text("Let's set your goal", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Tell us where you are and where you want to be. We'll build your daily plan.",
                color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp
            )
            Spacer(Modifier.height(6.dp))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumField(w, { w = it }, "Current (kg)", Modifier.weight(1f), decimal = true)
                NumField(g, { g = it }, "Goal (kg)", Modifier.weight(1f), decimal = true)
            }
            Text("Time to reach your goal", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(4, 8, 12, 16, 24)) { n ->
                    FilterChip(
                        selected = wk == n.toString(), onClick = { wk = n.toString() },
                        label = { Text("$n weeks") }
                    )
                }
            }
            NumField(wk, { wk = it }, "Or enter weeks", Modifier.fillMaxWidth())

            if (valid) {
                val diff = gf!! - wf!!
                AppCard {
                    Text(
                        when {
                            abs(diff) < 0.1f -> "Maintain your weight"
                            diff < 0 -> "Lose ${(-diff).fmt(1)} kg"
                            else -> "Gain ${diff.fmt(1)} kg"
                        },
                        fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatBlock("kg / week", rate.fmt(2), Green)
                        StatBlock("kcal / day", calcTarget(wf, gf, wki!!).toInt().toString(), Orange)
                        StatBlock("water / day", (calcWater(wf) / 1000f).fmt(1) + " L", Blue)
                    }
                    if (rate > 1f) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "⚠️ That's an aggressive pace. We cap your daily deficit for safety, so 0.25–0.75 kg/week is easier to sustain.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { s.saveProfile(wf!!, gf!!, wki!!); onDone() },
                enabled = valid,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text("Start my plan", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/* ───────────────────────────── MAIN SCAFFOLD ───────────────────────────── */

@Composable
fun MainScaffold(s: FitStore, onEditGoal: () -> Unit) {
    val tabs = listOf("Home" to "🏠", "Food" to "🍽️", "Steps" to "👟", "Lift" to "🏋️", "Water" to "💧")
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, (name, emoji) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(emoji, fontSize = 22.sp) },
                        label = { Text(name, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(bottom = pad.calculateBottomPadding()).fillMaxSize()) {
            Crossfade(tab, label = "tabs") { t ->
                when (t) {
                    0 -> HomeTab(s, onEditGoal, onNavigate = { tab = it })
                    1 -> FoodTab(s)
                    2 -> StepsTab(s)
                    3 -> LiftTab(s)
                    else -> WaterTab(s)
                }
            }
        }
    }
}

/* ───────────────────────────── HOME ───────────────────────────── */

@Composable
fun HomeTab(s: FitStore, onEditGoal: () -> Unit, onNavigate: (Int) -> Unit) {
    val budget = s.budgetKcal()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            GradientHeader(GreenDark, Green) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                        Text(s.displayName, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) { Text(s.displayName.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Ring(
                        progress = s.eatenKcal() / budget, color = Color.White,
                        track = Color.White.copy(alpha = 0.25f), stroke = 14.dp,
                        modifier = Modifier.size(156.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                s.remainingKcal().toInt().toString(), color = Color.White,
                                fontSize = 32.sp, fontWeight = FontWeight.ExtraBold
                            )
                            Text("kcal left", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.width(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeaderStat("Daily target", "${s.targetKcal().toInt()}")
                        HeaderStat("Eaten", "${s.eatenKcal().toInt()}")
                        HeaderStat("Burned (steps)", "+${s.stepKcal().toInt()}")
                    }
                }
                Spacer(Modifier.height(14.dp))
                Surface(color = Color.White.copy(alpha = 0.18f), shape = CircleShape) {
                    Text(
                        "🎯  ${s.weight.fmt(1)} → ${s.goal.fmt(1)} kg  ·  ${s.weeks} weeks",
                        color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MiniTile("👟", "Steps", s.steps.toString(), s.steps / 10000f, Amber, Modifier.weight(1f)) { onNavigate(2) }
                MiniTile("💧", "Water", (s.waterMl / 1000f).fmt(1) + " L", s.waterMl / s.waterGoalMl().toFloat(), Blue, Modifier.weight(1f)) { onNavigate(4) }
                MiniTile("🏋️", "Lifts", s.lifts.size.toString(), s.lifts.size / 5f, Purple, Modifier.weight(1f)) { onNavigate(3) }
            }
        }
        item {
            Text(
                "Today's insights", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        items(s.insights()) { ins ->
            AppCard(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmojiBadge(ins.emoji, ins.color)
                    Column {
                        Text(ins.title, fontWeight = FontWeight.SemiBold, color = ins.color)
                        Text(ins.body, fontSize = 14.sp)
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onEditGoal, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Edit goal") }
                OutlinedButton(onClick = { s.logout() }, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Log out") }
            }
        }
    }
}

@Composable
fun MiniTile(
    emoji: String, label: String, value: String, progress: Float, color: Color,
    modifier: Modifier, onClick: () -> Unit
) {
    Card(
        onClick = onClick, modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Ring(progress, color, Modifier.size(58.dp), stroke = 6.dp) { Text(emoji, fontSize = 20.sp) }
            Spacer(Modifier.height(8.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

/* ───────────────────────────── FOOD ───────────────────────────── */

@Composable
fun FoodTab(s: FitStore) {
    var dialogMeal by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(OrangeDark, Orange) {
            Text("Food diary", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HeaderStat("Eaten", "${s.eatenKcal().toInt()} kcal")
                HeaderStat("Budget", "${s.budgetKcal().toInt()} kcal")
                HeaderStat(
                    if (s.remainingKcal() >= 0) "Left" else "Over",
                    "${abs(s.remainingKcal()).toInt()} kcal"
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (s.eatenKcal() / s.budgetKcal()).coerceIn(0f, 1f) },
                color = Color.White, trackColor = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
            )
            Spacer(Modifier.height(4.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MEALS.forEachIndexed { idx, (mealName, emoji) ->
                val items = s.foods.filter { it.meal == idx }
                item {
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EmojiBadge(emoji, Orange, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(mealName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(
                                    "${items.sumOf { it.kcal }} kcal", fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            FilledTonalButton(onClick = { dialogMeal = idx }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                                Text("+ Add")
                            }
                        }
                        items.forEach { f ->
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name, Modifier.weight(1f))
                                Text("${f.kcal} kcal", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "  ✕", Modifier.clickableNoRipple { s.removeFood(f) },
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    dialogMeal?.let { m ->
        AddFoodDialog(
            mealName = MEALS[m].first,
            onDismiss = { dialogMeal = null },
            onAdd = { n, k -> s.addFood(m, n, k); dialogMeal = null }
        )
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null, onClick = onClick
        )
    )

@Composable
fun AddFoodDialog(mealName: String, onDismiss: () -> Unit, onAdd: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    val quick = listOf(
        "Banana" to 105, "Egg" to 78, "Roti" to 120, "Rice (1 cup)" to 200,
        "Dal (1 bowl)" to 180, "Paneer (100g)" to 265, "Apple" to 95, "Milk (1 glass)" to 120
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to $mealName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text("Food name") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                )
                NumField(kcal, { kcal = it }, "Calories", Modifier.fillMaxWidth())
                Text("Quick pick", fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quick) { (n, k) ->
                        AssistChip(onClick = { name = n; kcal = k.toString() }, label = { Text("$n · $k") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = kcal.toIntOrNull() != null,
                onClick = { onAdd(name.ifBlank { "Food" }, kcal.toInt()) }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/* ───────────────────────────── STEPS ───────────────────────────── */

@Composable
fun StepsTab(s: FitStore) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        GradientHeader(AmberDark, Amber) {
            Text("Steps", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("Enter your steps and we'll estimate the calories.", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Ring(s.steps / 10000f, Amber, Modifier.size(210.dp).padding(top = 8.dp), stroke = 18.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("👟", fontSize = 28.sp)
                        Text(s.steps.toString(), fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                        Text("of 10,000", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    }
                }
            }
            item {
                AppCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatBlock("kcal burned", "~${s.stepKcal().toInt()}", Orange)
                        StatBlock("distance", "~${(s.steps * 0.00075f).fmt(2)} km", Green)
                        StatBlock("active min", "~${s.steps / 100}", Purple)
                    }
                }
            }
            item {
                AppCard {
                    Text("Log steps", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    NumField(input, { input = it }, "Steps", Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { s.setSteps(input.toInt()); input = "" },
                            enabled = input.toIntOrNull() != null,
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)
                        ) { Text("Set total") }
                        OutlinedButton(
                            onClick = { s.setSteps(s.steps + input.toInt()); input = "" },
                            enabled = input.toIntOrNull() != null,
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)
                        ) { Text("Add to total") }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(500, 1000, 2000, 5000)) { n ->
                            AssistChip(onClick = { s.setSteps(s.steps + n) }, label = { Text("+$n") })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Estimate based on your body weight (about 0.04 kcal per step at 70 kg). Actual burn varies with pace and terrain.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}

/* ───────────────────────────── WORKOUT ───────────────────────────── */

@Composable
fun LiftTab(s: FitStore) {
    var showDialog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            GradientHeader(PurpleDark, Purple) {
                Text("Strength", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HeaderStat("Exercises", s.lifts.size.toString())
                    HeaderStat("Total sets", s.lifts.sumOf { it.sets }.toString())
                    HeaderStat("Volume", "${s.totalVolume().toInt()} kg")
                }
                Spacer(Modifier.height(4.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (s.lifts.isEmpty()) item {
                    EmptyState("🏋️", "Start today's session", "Tap Add exercise to log your first lift.")
                }
                items(s.lifts.toList()) { l ->
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EmojiBadge("🏋️", Purple)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(l.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(
                                    "${l.sets} × ${l.reps} @ ${l.kg.fmt(1)} kg",
                                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${l.volume.toInt()} kg", fontWeight = FontWeight.Bold, color = Purple)
                                Text(
                                    "✕", Modifier.clickableNoRipple { s.removeLift(l) }.padding(top = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { showDialog = true },
            containerColor = Purple, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) { Text("＋  Add exercise", fontWeight = FontWeight.SemiBold) }
    }

    if (showDialog) AddLiftDialog(onDismiss = { showDialog = false }, onAdd = { s.addLift(it); showDialog = false })
}

@Composable
fun AddLiftDialog(onDismiss: () -> Unit, onAdd: (Lift) -> Unit) {
    var name by remember { mutableStateOf("") }
    var sets by remember { mutableStateOf("3") }
    var reps by remember { mutableStateOf("10") }
    var kg by remember { mutableStateOf("") }
    val suggestions = listOf("Squat", "Bench Press", "Deadlift", "Overhead Press", "Barbell Row", "Pull-up", "Lunges", "Bicep Curl")
    val valid = name.isNotBlank() && sets.toIntOrNull() != null && reps.toIntOrNull() != null && kg.toFloatOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text("Exercise") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions) { n -> AssistChip(onClick = { name = n }, label = { Text(n) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(sets, { sets = it }, "Sets", Modifier.weight(1f))
                    NumField(reps, { reps = it }, "Reps", Modifier.weight(1f))
                    NumField(kg, { kg = it }, "Kg", Modifier.weight(1f), decimal = true)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onAdd(Lift(name, sets.toInt(), reps.toInt(), kg.toFloat())) }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/* ───────────────────────────── WATER ───────────────────────────── */

@Composable
fun WaterTab(s: FitStore) {
    var custom by remember { mutableStateOf("") }
    val goal = s.waterGoalMl()
    val frac by animateFloatAsState((s.waterMl / goal.toFloat()).coerceIn(0f, 1f), tween(800), label = "water")
    val glassShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 44.dp, bottomEnd = 44.dp)

    Column(Modifier.fillMaxSize()) {
        GradientHeader(BlueDark, Blue) {
            Text("Hydration", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (s.waterMl >= goal) "Goal reached 🎉" else "${goal - s.waterMl} ml to go today",
                color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .size(width = 160.dp, height = 210.dp)
                        .clip(glassShape)
                        .background(Blue.copy(alpha = 0.12f))
                        .border(3.dp, Blue.copy(alpha = 0.55f), glassShape),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(frac).background(Blue.copy(alpha = 0.85f)))
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "${(frac * 100).toInt()}%", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                            color = if (frac > 0.5f) Color.White else Blue
                        )
                        Text(
                            "${s.waterMl} / $goal ml", fontSize = 13.sp,
                            color = if (frac > 0.5f) Color.White else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(200 to "🥛", 250 to "🥤", 500 to "🍶").forEach { (ml, emoji) ->
                        Card(
                            onClick = { s.addWater(ml) }, modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Blue.copy(alpha = 0.12f))
                        ) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(emoji, fontSize = 26.sp)
                                Text("+$ml ml", fontWeight = FontWeight.SemiBold, color = Blue)
                            }
                        }
                    }
                }
            }
            item {
                AppCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NumField(custom, { custom = it }, "Custom (ml)", Modifier.weight(1f))
                        FilledTonalButton(
                            onClick = { s.addWater(custom.toInt()); custom = "" },
                            enabled = custom.toIntOrNull() != null
                        ) { Text("Add") }
                    }
                    TextButton(onClick = { s.addWater(-250) }) { Text("Undo last 250 ml") }
                    Text(
                        "Your daily goal is about 35 ml per kg of body weight.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}