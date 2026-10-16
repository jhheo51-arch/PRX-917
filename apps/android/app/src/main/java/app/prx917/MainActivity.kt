package app.prx917

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.platform.LocalContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

// 전술 팔레트 (국방색 섀시 + 앰버 OLED)
private val SCR = Color(0xFF0C0F0A)
private val FG = Color(0xFFECC978)
private val DIM = Color(0xFF8F8A5E)
private val LINE = Color(0xFF2A2D1D)
private val GRN = Color(0xFF8FAE5A)
private val RED = Color(0xFFD4703F)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: RadioViewModel = viewModel()
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { }
            LaunchedEffect(Unit) { launcher.launch(neededPermissions()) }
            App(vm)
        }
    }
}

private fun neededPermissions(): Array<String> {
    val p = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        p += Manifest.permission.BLUETOOTH_ADVERTISE
        p += Manifest.permission.BLUETOOTH_CONNECT
        p += Manifest.permission.BLUETOOTH_SCAN
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        p += Manifest.permission.NEARBY_WIFI_DEVICES
    }
    return p.toTypedArray()
}

@Composable
private fun App(vm: RadioViewModel) {
    val screen = vm.screen.value
    val showChrome = screen == Screen.CHANNEL || screen == Screen.DISCOVERY ||
        screen == Screen.PAIRING || screen == Screen.SETTINGS

    Column(Modifier.fillMaxSize().background(SCR)) {
        if (showChrome) StatusBar(vm)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (screen) {
                Screen.LOCK -> LockScreen(vm)
                Screen.CHANNEL -> ChannelScreen(vm)
                Screen.DISCOVERY -> DiscoveryScreen(vm)
                Screen.SAFETY -> SafetyScreen(vm)
                Screen.CHAT -> ChatScreen(vm)
                Screen.PAIRING -> PairingScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
            }
        }
        if (showChrome) TabBar(vm, screen)
    }
}

@Composable
private fun StatusBar(vm: RadioViewModel) {
    Row(
        Modifier.fillMaxWidth().background(SCR).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("PRX-917", color = FG, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Text("CH ${vm.channel.value}", color = DIM, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text("▮▮▮▯", color = GRN, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text("85%", color = DIM, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
    Divider(color = LINE)
}

@Composable
private fun TabBar(vm: RadioViewModel, screen: Screen) {
    Divider(color = LINE)
    Row(Modifier.fillMaxWidth().background(SCR)) {
        Tab("📡", "무전", screen == Screen.CHANNEL || screen == Screen.DISCOVERY) { vm.goChannel() }
        Tab("🪪", "페어링", screen == Screen.PAIRING) { vm.goPairing() }
        Tab("⚙", "설정", screen == Screen.SETTINGS) { vm.goSettings() }
    }
}

@Composable
private fun RowScope.Tab(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 17.sp)
        Text(label, color = if (active) FG else DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LockScreen(vm: RadioViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🙂", fontSize = 54.sp)
        Spacer(Modifier.height(12.dp))
        Text("PRX-917 잠금", color = FG, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("얼굴/지문으로 신원키 잠금 해제", color = DIM, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        val ctx = LocalContext.current
        Primary("얼굴/지문으로 잠금 해제") {
            (ctx as? FragmentActivity)?.let { act -> bioAuth(act) { vm.unlock() } } ?: vm.unlock()
        }
        Spacer(Modifier.height(10.dp))
        Ghost("기기 비밀번호 사용") { vm.unlock() }
        Spacer(Modifier.height(10.dp))
        Text("생체정보는 기기를 벗어나지 않습니다", color = DIM, fontSize = 11.sp)
    }
}

@Composable
private fun ChannelScreen(vm: RadioViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Eyebrow("무전 · 채널")
        Title("통신 채널")
        Spacer(Modifier.height(12.dp))
        vm.channels.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (n, f) ->
                    Column(
                        Modifier.weight(1f)
                            .background(if (vm.channel.value == n) FG.copy(alpha = 0.2f) else LINE, RoundedCornerShape(12.dp))
                            .clickable { vm.selectChannel(n) }.padding(14.dp)
                    ) {
                        Text("CH $n", color = FG, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("$f MHz", color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("같은 채널끼리만 교신합니다", color = DIM, fontSize = 11.sp)
    }
}

@Composable
private fun DiscoveryScreen(vm: RadioViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Eyebrow("무전 · 탐색")
        Title("주변 국(局) · CH ${vm.channel.value}")
        Spacer(Modifier.height(12.dp))
        if (vm.discovered.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("교신 가능한 국 수신 중…", color = DIM)
            }
        } else {
            vm.discovered.forEach { name ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(LINE, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("● ", color = GRN)
                    Text(name, color = FG, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(if (vm.linked.value) "연결됨" else "발견", color = DIM, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
        }
        Ghost("← 채널 변경") { vm.goChannel() }
    }
}

@Composable
private fun SafetyScreen(vm: RadioViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Eyebrow("중간자 방어")
        Title("안전번호 확인")
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth().background(LINE, RoundedCornerShape(14.dp)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SAFETY NUMBER · ${vm.peerName.value}", color = DIM, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                vm.safetyNumber.value.ifEmpty { "— — —" },
                color = FG, fontFamily = FontFamily.Monospace,
                fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("상대 기기 화면의 숫자와 완전히 같나요?\n같으면 중간자(도청자)가 없습니다.", color = FG, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Danger("불일치") { vm.rejectSafety() } }
            Box(Modifier.weight(1f)) { Primary("일치 · 확인") { vm.confirmSafety() } }
        }
    }
}

@Composable
private fun ChatScreen(vm: RadioViewModel) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(LINE).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = FG, modifier = Modifier.clickable { vm.screen.value = Screen.DISCOVERY }.padding(end = 10.dp))
            Column {
                Text(vm.peerName.value.ifEmpty { "상대" }, color = FG, fontWeight = FontWeight.Bold)
                Text("CH ${vm.channel.value} · 암호화됨", color = DIM, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(if (vm.linked.value) "🔒 E2E" else "🔴 끊김", color = if (vm.linked.value) GRN else RED, fontSize = 12.sp)
        }
        if (!vm.linked.value) {
            Row(
                Modifier.fillMaxWidth().background(RED.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("링크 끊김 — 상대 신호 소실", color = RED, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("재연결", color = FG, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { vm.reconnect() })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(14.dp)) {
            items(vm.messages) { msg ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start
                ) {
                    Text(
                        msg.text,
                        color = if (msg.mine) SCR else FG,
                        modifier = Modifier
                            .background(if (msg.mine) FG else LINE, RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(LINE.copy(alpha = 0.5f)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("메시지 입력…", color = DIM) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SCR, unfocusedContainerColor = SCR,
                    focusedTextColor = FG, unfocusedTextColor = FG,
                    focusedIndicatorColor = FG, unfocusedIndicatorColor = LINE
                )
            )
            Spacer(Modifier.width(8.dp))
            Primary("전송") { vm.send(draft); draft = "" }
        }
    }
}

@Composable
private fun PairingScreen(vm: RadioViewModel) {
    val qr = remember(vm.myPublicKey()) { qrBitmap(vm.myPublicKey()) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { res ->
        res.contents?.let { vm.onScannedPeer(it) }
    }
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScrollSafe(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Eyebrow("페어링 · 신원")
        Title("내 신원 (QR)")
        Spacer(Modifier.height(12.dp))
        Text(vm.callsign, color = FG, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(vm.myFingerprint(), color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(16.dp))
        Image(bitmap = qr, contentDescription = "내 신원 QR", modifier = Modifier.size(200.dp))
        Spacer(Modifier.height(16.dp))
        Text("상대가 이 QR을 스캔하면 서로의 신원키를 교환합니다.", color = DIM, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Ghost("상대 QR 스캔") {
            scanLauncher.launch(
                ScanOptions().setPrompt("상대 QR을 비추세요").setBeepEnabled(false).setOrientationLocked(false)
            )
        }
        if (vm.scannedInfo.value.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(vm.scannedInfo.value, color = GRN, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SettingsScreen(vm: RadioViewModel) {
    var bio by remember { mutableStateOf(true) }
    var autoDel by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(true) }
    var confirmRegen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScrollSafe()) {
        Eyebrow("설정")
        Title("설정")
        Spacer(Modifier.height(12.dp))
        KV("내 콜사인", vm.callsign)
        KV("기본 채널", "CH ${vm.channel.value}")
        ToggleRow("생체인증 잠금 (지문/얼굴)", bio) { bio = it }
        ToggleRow("메시지 24h 후 자동 삭제", autoDel) { autoDel = it }
        ToggleRow("수신 알림음", sound) { sound = it }
        KV("암호 스택", "X25519 · ChaCha20")
        KV("버전", "0.1 · debug")
        Spacer(Modifier.height(16.dp))
        if (!confirmRegen) {
            Danger("신원키 재생성") { confirmRegen = true }
        } else {
            Text("정말 재생성할까요? 기존 세션·신뢰가 초기화됩니다.", color = RED, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { Ghost("취소") { confirmRegen = false } }
                Box(Modifier.weight(1f)) { Danger("재생성") { vm.regenerate(); confirmRegen = false } }
            }
        }
    }
}

// ── 공통 컴포저블 ──
@Composable private fun Eyebrow(t: String) =
    Text(t.uppercase(), color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

@Composable private fun Title(t: String) =
    Text(t, color = FG, fontSize = 20.sp, fontWeight = FontWeight.Bold)

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(k, color = DIM, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(v, color = FG, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
    Divider(color = LINE)
}

@Composable
private fun ToggleRow(k: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = DIM, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SCR, checkedTrackColor = GRN,
                uncheckedThumbColor = DIM, uncheckedTrackColor = LINE
            )
        )
    }
    Divider(color = LINE)
}

@Composable
private fun Primary(label: String, onClick: () -> Unit) =
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = FG, contentColor = SCR)) {
        Text(label, fontWeight = FontWeight.Bold)
    }

@Composable
private fun Ghost(label: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, colors = ButtonDefaults.outlinedButtonColors(contentColor = FG)) {
        Text(label)
    }

@Composable
private fun Danger(label: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, colors = ButtonDefaults.outlinedButtonColors(contentColor = RED)) {
        Text(label)
    }

// 세로 스크롤 헬퍼 (설정 화면)
@Composable
private fun Modifier.verticalScrollSafe(): Modifier =
    this.then(Modifier.verticalScroll(rememberScrollState()))

// 생체인증 (BiometricPrompt). 사용 불가/실패 시 호출측이 대체 버튼을 둔다.
private fun bioAuth(activity: FragmentActivity, onOk: () -> Unit) {
    val mgr = BiometricManager.from(activity)
    val can = mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    if (can != BiometricManager.BIOMETRIC_SUCCESS) { onOk(); return }  // 등록된 생체 없으면 바로 통과(데모)
    val prompt = BiometricPrompt(
        activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("PRX-917 잠금 해제")
        .setSubtitle("생체인증으로 신원키 접근")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .setNegativeButtonText("취소")
        .build()
    prompt.authenticate(info)
}

// QR 생성 (ZXing)
private fun qrBitmap(text: String, size: Int = 360): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF131510.toInt() else 0xFFFFFFFF.toInt())
    }
    return bmp.asImageBitmap()
}
