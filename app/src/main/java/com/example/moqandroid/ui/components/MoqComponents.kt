package com.example.moqandroid.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.moqandroid.ui.theme.BorderColor
import com.example.moqandroid.ui.theme.PrimaryColor
import com.example.moqandroid.ui.theme.SurfaceColor
import com.example.moqandroid.ui.theme.SurfaceMuted
import com.example.moqandroid.ui.theme.TextPrimary
import com.example.moqandroid.ui.theme.TextSecondary
import com.example.moqandroid.ui.theme.WorkspaceBackground

@Composable
fun Page(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
            }
            .padding(24.dp),
        content = content,
    )
}

@Composable
fun TopBar(
    title: String,
    actionIcon: ImageVector,
    actionDescription: String,
    onAction: () -> Unit,
) {
    Surface(color = WorkspaceBackground, tonalElevation = 0.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onAction) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = actionDescription,
                    tint = TextPrimary,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, description: String) {
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    Spacer(Modifier.height(6.dp))
    Text(description, fontSize = 15.sp, color = TextSecondary)
    Spacer(Modifier.height(24.dp))
}

@Composable
fun Label(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Label(label)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }, onDone = { onSubmit() }),
        shape = RoundedCornerShape(8.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = SurfaceColor,
            unfocusedContainerColor = SurfaceColor,
            focusedIndicatorColor = PrimaryColor,
            unfocusedIndicatorColor = BorderColor,
            cursorColor = PrimaryColor,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    )
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor, contentColor = Color.White),
    ) {
        Text(text, fontSize = 15.sp)
    }
}

@Composable
fun SecondaryAction(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
    ) {
        Text(text, fontSize = 15.sp)
    }
}

@Composable
fun StatusPanel(text: String) {
    Surface(
        color = SurfaceMuted,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            color = TextPrimary,
            fontSize = 15.sp,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = TextPrimary,
    selectedTextColor = TextPrimary,
    indicatorColor = SurfaceMuted,
    unselectedIconColor = TextSecondary,
    unselectedTextColor = TextSecondary,
)

fun Modifier.bottomSystemInset(): Modifier = navigationBarsPadding()

fun Modifier.topSystemInset(): Modifier = statusBarsPadding()

@Composable
fun ClearFocusOnEntry(key: Any = Unit) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(key) {
        focusManager.clearFocus(force = true)
    }
}
