package com.example.multitimetracker.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.multitimetracker.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    titleContent: (@Composable (() -> Unit))? = null,
    navigationIcon: (@Composable (() -> Unit))? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val openAppMenu = LocalOpenAppMenu.current
    CenterAlignedTopAppBar(
        title = {
            if (titleContent != null) {
                titleContent()
            } else {
                TitleWithVaultIndicator(title = title)
            }
        },
        navigationIcon = {
            when {
                navigationIcon != null -> navigationIcon()
                openAppMenu != null -> {
                    IconButton(onClick = openAppMenu) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.cd_open_app_menu)
                        )
                    }
                }
            }
        },
        actions = {
            actions()
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
    )
}

@Composable
fun TitleWithVaultIndicator(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
