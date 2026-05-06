package com.novahorizon.wanderly.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
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
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp
    )
}
