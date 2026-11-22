package id.tetuko.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HorizontalScrollRow(
    modifier: Modifier = Modifier,
    itemSpacing: Int = 8,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(itemSpacing.dp),
        content = content,
    )
}

@Composable
fun ModalScrollColumn(
    modifier: Modifier = Modifier,
    maxHeightDp: Int = 560,
    itemSpacing: Int = 10,
    content: @Composable ColumnScope.() -> Unit,
) {
    VerticalScrollColumn(
        modifier = modifier.fillMaxWidth().heightIn(max = maxHeightDp.dp),
        itemSpacing = itemSpacing,
        content = content,
    )
}

@Composable
fun ReusableModalSurface(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.widthIn(max = 680.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(content = content)
    }
}

@Composable
fun VerticalScrollColumn(
    modifier: Modifier = Modifier,
    itemSpacing: Int = 12,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(itemSpacing.dp),
        content = content,
    )
}