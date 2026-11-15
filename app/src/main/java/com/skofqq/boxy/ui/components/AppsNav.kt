package com.skofqq.boxy.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Opens the Apps page (the module's black / white list of apps) over the tabs, from Settings or Config. */
object AppsNav {
    var open by mutableStateOf(false)
}
