package com.binauralcycles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PresetMenuRow(
    text: String,
    onClick: () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
    optionSize: Int = 1,
    index: Int = 0,
) {
    val dropdownColors: DropdownColors = DropdownDefaults.dropdownColors()
    val additionalTopPadding = if (index == 0) 16.dp else 12.dp
    val additionalBottomPadding = if (index == optionSize - 1) 16.dp else 12.dp

    Row(
        modifier = Modifier
            .clickable { onClick() }
            .background(dropdownColors.containerColor)
            .padding(horizontal = 20.dp)
            .padding(top = additionalTopPadding, bottom = additionalBottomPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingIcon?.invoke()
        if (leadingIcon != null) Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            fontWeight = FontWeight.Medium,
            color = dropdownColors.contentColor,
        )
    }
}
