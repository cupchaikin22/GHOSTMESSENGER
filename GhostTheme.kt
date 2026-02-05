package io.ghostsoftware.ghostchat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

object GhostTheme {

    var accentColor by mutableStateOf(Color.White)


    var backgroundColor by mutableStateOf(Color(0xFF000000))
}