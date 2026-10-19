package com.orcaphone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orcaphone.ui.cloud.OrcaLoginScreen
import com.orcaphone.ui.device.DeviceScreen
import com.orcaphone.ui.prepare.PrepareScreen
import com.orcaphone.ui.preview.PreviewScreen
import com.orcaphone.ui.settings.EditorTarget
import com.orcaphone.ui.settings.PresetEditorScreen

@Composable
fun OrcaPhoneAppUi(vm: AppViewModel) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val slice by vm.slice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editor by rememberSaveable { mutableStateOf<EditorTarget?>(null) }
    var login by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.cloud.messages.collect { snackbar.showSnackbar(it) }
    }

    if (login) {
        BackHandler { login = false }
        OrcaLoginScreen(vm.cloud, onClose = { login = false })
        return
    }

    val ed = editor
    if (ed != null) {
        BackHandler { editor = null }
        PresetEditorScreen(vm, ed, onClose = { editor = null })
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.PREPARE, onClick = { vm.setTab(Tab.PREPARE) },
                    icon = { Icon(Icons.Outlined.ViewInAr, null) }, label = { Text("Prepare") },
                )
                NavigationBarItem(
                    selected = tab == Tab.PREVIEW, onClick = { vm.setTab(Tab.PREVIEW) },
                    icon = {
                        BadgedBox(badge = {
                            val s = slice
                            if (s is SliceUiState.Ready && s.stale) Badge()
                        }) { Icon(Icons.Outlined.Layers, null) }
                    },
                    label = { Text("Preview") },
                )
                NavigationBarItem(
                    selected = tab == Tab.DEVICE, onClick = { vm.setTab(Tab.DEVICE) },
                    icon = { Icon(Icons.Outlined.Print, null) }, label = { Text("Device") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.PREPARE -> PrepareScreen(vm, onEdit = { editor = it })
                Tab.PREVIEW -> PreviewScreen(vm)
                Tab.DEVICE -> DeviceScreen(vm, onSignIn = { login = true })
            }
        }
    }
}
