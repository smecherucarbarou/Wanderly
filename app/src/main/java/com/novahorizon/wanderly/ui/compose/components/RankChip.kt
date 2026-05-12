package com.novahorizon.wanderly.ui.compose.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme

@Composable
fun RankChip(
    rankName: String,
    modifier: Modifier = Modifier
) {
    val colors = WanderlyTheme.extendedColors
    Text(
        text = rankName,
        modifier = modifier
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(colors.gradientStart, colors.gradientEnd)
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp
    )
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewRankChip() {
    WanderlyTheme {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RankChip(rankName = "Explorer")
            RankChip(rankName = "Trailblazer")
            RankChip(rankName = "Legend")
        }
    }
}
