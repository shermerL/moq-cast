package com.example.moqandroid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.moqandroid.ui.theme.BorderColor
import com.example.moqandroid.ui.theme.PrimaryColor
import com.example.moqandroid.ui.theme.SurfaceColor
import com.example.moqandroid.ui.theme.SurfaceMuted
import com.example.moqandroid.ui.theme.TextPrimary
import com.example.moqandroid.ui.theme.TextSecondary

private val DisabledSourceSurface = Color(0xFFF0F2F0)
private val DisabledSourceIcon = Color(0xFFDADFDA)
private val DisabledSourceBorder = Color(0xFFE1E3E1)

@Composable
fun MoqBrandHeader(
    appName: String,
    relayLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = PrimaryColor,
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 0.dp,
            modifier = Modifier.size(30.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "M",
                    color = SurfaceColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            appName,
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        MoqPill(
            text = relayLabel,
            selected = false,
            textColor = PrimaryColor,
        )
    }
}

@Composable
fun MoqPill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick == null) {
        modifier
    } else {
        modifier.clickable(enabled = enabled, onClick = onClick)
    }

    Surface(
        color = if (selected) PrimaryColor else SurfaceMuted,
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 0.dp,
        modifier = clickModifier
            .widthIn(min = 72.dp, max = 128.dp)
            .height(30.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                color = textColor ?: if (selected) SurfaceColor else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun MoqSourceCard(
    marker: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = when {
        selected -> BorderColor
        enabled -> BorderColor
        else -> DisabledSourceBorder
    }

    Surface(
        color = if (selected) SurfaceColor else DisabledSourceSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, border),
        tonalElevation = 0.dp,
        modifier = modifier
            .height(142.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(top = 14.dp, start = 8.dp, end = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            SourceMarker(marker, selected)
            Spacer(Modifier.height(14.dp))
            Text(
                title,
                color = if (selected) TextPrimary else TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                color = TextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun MoqStatusCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    heightDp: Int = 76,
) {
    Surface(
        color = SurfaceMuted,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                title,
                color = TextPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
            )
        }
    }
}

@Composable
fun MoqInfoRow(
    label: String,
    note: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    note,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        trailing()
    }
}

@Composable
fun MoqSettingSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = SurfaceColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderColor),
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text(
                title,
                color = TextPrimary,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SourceMarker(text: String, selected: Boolean) {
    Surface(
        color = if (selected) PrimaryColor else DisabledSourceIcon,
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                color = if (selected) SurfaceColor else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
