package com.abfilepro.app.ui.screens.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abfilepro.app.assistant.FamilyChatMessage
import com.abfilepro.app.assistant.FamilyChatService
import com.abfilepro.app.assistant.FamilyLinkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FamilyChatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val service = remember(context) { FamilyChatService(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val profile = remember { FamilyLinkStore.load(context) }
    val myDeviceId = remember { service.currentDeviceId() }
    var messages by remember { mutableStateOf<List<FamilyChatMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (profile.syncState != com.abfilepro.app.assistant.FamilySyncState.ON) return@LaunchedEffect
        while (isActive) {
            val remote = withContext(Dispatchers.IO) { service.pull() }
            if (remote != null) {
                val changed = remote != messages
                messages = remote
                online = true
                if (changed && remote.isNotEmpty()) listState.animateScrollToItem(remote.lastIndex)
            } else {
                online = false
            }
            delay(5_000)
        }
    }

    Scaffold(
        containerColor = adaptSurface(Color(0xFFF8FAFC)),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(L("شات العائلة"), fontWeight = FontWeight.Black)
                        Text(
                            when {
                                // P17: chat needs an explicit family link; say so instead of "reconnecting" forever.
                                profile.syncState != com.abfilepro.app.assistant.FamilySyncState.ON -> "فعّل العائلة من إعداد العائلة لاستخدام الشات"
                                online -> "${profile.familyName} • متصل"
                                else -> "${profile.familyName} • جارٍ إعادة الاتصال"
                            },
                            fontSize = 11.sp,
                            color = if (online) adaptInk(Color(0xFF0DAA60)) else adaptInk(Color(0xFF94A3B8)),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, L("رجوع")) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = adaptSurface(Color.White)),
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().background(adaptSurface(Color.White)).imePadding().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(2000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(L("اكتب رسالة...")) },
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                )
                FilledIconButton(
                    enabled = draft.isNotBlank() && !sending,
                    onClick = {
                        val outgoing = draft.trim()
                        if (outgoing.isBlank()) return@FilledIconButton
                        sending = true
                        scope.launch {
                            val sent = withContext(Dispatchers.IO) { service.send(outgoing) }
                            if (sent != null) {
                                draft = ""
                                messages = (messages + sent).distinctBy { it.messageId }.sortedBy { it.createdAt }
                                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                                online = true
                            } else {
                                online = false
                            }
                            sending = false
                        }
                    },
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                ) {
                    if (sending) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Send, L("إرسال"))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            if (messages.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize().padding(top = 120.dp), contentAlignment = Alignment.TopCenter) {
                        Text(L("ابدأ أول رسالة بين الأجهزة المرتبطة 👋"), color = adaptInk(Color(0xFF94A3B8)))
                    }
                }
            } else {
                items(messages, key = { it.messageId }) { message ->
                    val mine = message.deviceId == myDeviceId
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                    ) {
                        Surface(
                            modifier = Modifier.widthIn(max = 300.dp),
                            shape = RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 18.dp,
                                bottomStart = if (mine) 18.dp else 4.dp,
                                bottomEnd = if (mine) 4.dp else 18.dp,
                            ),
                            color = if (mine) Color(0xFFFFE7EA) else Color.White,
                            shadowElevation = 1.dp,
                        ) {
                            Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                                if (!mine) Text(message.senderName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = adaptInk(Color(0xFFFF2635)))
                                Text(message.body, fontSize = 16.sp, color = adaptInk(Color(0xFF111827)))
                                Text(
                                    formatChatTime(message.createdAt),
                                    fontSize = 10.sp,
                                    color = adaptInk(Color(0xFF94A3B8)),
                                    modifier = Modifier.align(Alignment.End),
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun formatChatTime(timestamp: Long): String = runCatching {
    DateTimeFormatter.ofPattern("h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))
}.getOrDefault("")
