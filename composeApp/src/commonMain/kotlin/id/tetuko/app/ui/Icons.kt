package id.tetuko.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import id.tetuko.core.domain.model.MenuIcon

fun MenuIcon.toImageVector(): ImageVector = when (this) {
    MenuIcon.HOME -> Icons.Filled.Home
    MenuIcon.CAMERA -> Icons.Filled.PhotoCamera
    MenuIcon.FILE_CHECK -> Icons.AutoMirrored.Filled.FactCheck
    MenuIcon.FILE_TEXT -> Icons.Filled.Description
    MenuIcon.MAP_PIN -> Icons.Filled.Place
    MenuIcon.ZAP -> Icons.Filled.Bolt
    MenuIcon.GLOBE -> Icons.Filled.Language
    MenuIcon.EDIT -> Icons.Filled.Edit
    MenuIcon.ACTIVITY -> Icons.AutoMirrored.Filled.ShowChart
    MenuIcon.BAR_CHART -> Icons.Filled.BarChart
    MenuIcon.BUILDING -> Icons.Filled.Apartment
    MenuIcon.NETWORK -> Icons.Filled.AccountTree
    MenuIcon.BOT -> Icons.Filled.SmartToy
    MenuIcon.DATABASE -> Icons.Filled.Storage
    MenuIcon.LOG_OUT -> Icons.AutoMirrored.Filled.Logout
    MenuIcon.KEY -> Icons.Filled.Key
    MenuIcon.CODE -> Icons.Filled.Code
}
