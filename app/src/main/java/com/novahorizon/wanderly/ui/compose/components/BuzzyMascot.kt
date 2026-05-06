package com.novahorizon.wanderly.ui.compose.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novahorizon.wanderly.R

@Composable
fun BuzzyMascot(
    modifier: Modifier = Modifier,
    size: Dp = 60.dp
) {
    Image(
        painter = painterResource(id = R.drawable.ui_buzzy_mascot),
        contentDescription = "Buzzy mascot",
        modifier = modifier.size(size)
    )
}
