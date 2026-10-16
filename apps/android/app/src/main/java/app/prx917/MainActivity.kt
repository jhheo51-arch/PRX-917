package app.prx917

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

// 전술 팔레트 (국방색 섀시 + 앰버 OLED)
private val SCR = Color(0xFF0C0F0A)
private val FG = Color(0xFFECC978)
private val DIM = Color(0xFF8F8A5E)
private val LINE = Color(0xFF2A2D1D)
private val GRN = Color(0xFF8FAE5A)
private val RED = Color(0xFFD4703F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: RadioViewModel = viewModel()
            // 권한 요청 (최초 1회)
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
    Box(Modifier.fillMaxSize().background(SCR)) {
        when (vm.screen.value) {
            Screen.LOCK -> LockScreen(vm)
            Screen.CHANNEL -> ChannelScreen(vm)
            Screen.DISCOVERY -> DiscoveryScreen(vm)
            Screen.SAFETY -> SafetyScreen(vm)
            Screen.CHAT -> ChatScreen(vm)
        }
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
        Primary("얼굴 인식으로 잠금 해제") { vm.unlock() }   // 실제: BiometricPrompt 연결
    }
}

@Composable
private fun ChannelScreen(vm: RadioViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Title("통신 채널")
        Spacer(Modifier.height(12.dp))
        vm.channels.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (n, f) ->
                    Column(
                        Modifier.weight(1f)
                            .background(if (vm.channel.value == n) FG.copy(alpha = 0.2f) else LINE, RoundedCornerShape(12.dp))
                            .clickablePad { vm.selectChannel(n) }
                    ) {
                        Text("CH $n", color = FG, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("$f MHz", color = DIM, fontSize = 11.sp)
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
        Title("주변 국(局) 탐색 · CH ${vm.channel.value}")
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
        Ghost("← 채널 변경") { vm.screen.value = Screen.CHANNEL }
    }
}

@Composable
private fun SafetyScreen(vm: RadioViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
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
            Text("← ", color = FG, modifier = Modifier.clickablePad { vm.screen.value = Screen.DISCOVERY })
            Column {
                Text(vm.peerName.value, color = FG, fontWeight = FontWeight.Bold)
                Text("CH ${vm.channel.value} · 암호화됨", color = DIM, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(if (vm.linked.value) "🔒 E2E" else "🔴 끊김", color = if (vm.linked.value) GRN else RED, fontSize = 12.sp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(14.dp)) {
            items(vm.messages) { msg ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start) {
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
        Row(Modifier.fillMaxWidth().background(LINE.copy(alpha = 0.5f)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("메시지 입력…", color = DIM) },
                keyboardOptions = KeyboardOptions.Default,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SCR, unfocusedContainerColor = SCR,
                    focusedTextColor = FG, unfocusedTextColor = FG
                )
            )
            Spacer(Modifier.width(8.dp))
            Primary("전송") { vm.send(draft); draft = "" }
        }
    }
}

// 공통 컴포저블
@Composable private fun Title(t: String) =
    Text(t, color = FG, fontSize = 20.sp, fontWeight = FontWeight.Bold)

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

// 클릭 + 패딩 헬퍼
private fun Modifier.clickablePad(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick).padding(14.dp)
