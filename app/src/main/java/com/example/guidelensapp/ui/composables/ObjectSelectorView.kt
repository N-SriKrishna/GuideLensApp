package com.example.guidelensapp.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.guidelensapp.Config


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectSelectorView(
    currentTarget: String,
    onTargetSelected: (String) -> Unit,
    onStartNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDropdown by remember { mutableStateOf(false) }
    var selectedObject by remember { mutableStateOf(currentTarget) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Select Object to Navigate",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        // Dropdown Button
        OutlinedButton(
            onClick = { showDropdown = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White.copy(alpha = 0.9f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedObject.replaceFirstChar { it.uppercase() },
                    color = Color.Black,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select Object",
                    tint = Color.Black
                )
            }
        }

        // Start Navigation Button
        Button(
            onClick = {
                onTargetSelected(selectedObject)
                onStartNavigation()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Start Navigation",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // Dropdown Dialog
    if (showDropdown) {
        Dialog(onDismissRequest = { showDropdown = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color.White
            ) {
                Column {
                    Text(
                        text = "Choose Target Object",
                        modifier = Modifier.padding(16.dp),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Divider()

                    LazyColumn {
                        items(Config.NAVIGABLE_OBJECTS) { obj ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = obj.replaceFirstChar { it.uppercase() },
                                        fontSize = 16.sp,
                                        fontWeight = if (obj == selectedObject) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier.clickable {
                                    selectedObject = obj
                                    showDropdown = false
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (obj == selectedObject)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

// Alternative: Text Input Version
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectSelectorTextInput(
    currentTarget: String,
    onTargetSelected: (String) -> Unit,
    onStartNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    var textInput by remember { mutableStateOf(currentTarget) }
    var showSuggestions by remember { mutableStateOf(false) }

    val filteredSuggestions = Config.NAVIGABLE_OBJECTS.filter {
        it.contains(textInput, ignoreCase = true)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Enter Object to Navigate",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        // Text Field with autocomplete
        // Text Field with autocomplete
        OutlinedTextField(
            value = textInput,
            onValueChange = {
                textInput = it
                showSuggestions = it.isNotEmpty()
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g., chair, door, table") },
            colors = TextFieldDefaults.colors( // <-- Corrected to use colors
                unfocusedContainerColor = Color.White,
                focusedContainerColor = Color.White,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = Color.Gray
            ),
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )


        // Suggestions dropdown
        if (showSuggestions && filteredSuggestions.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.heightIn(max = 200.dp)) {
                    filteredSuggestions.take(5).forEach { suggestion ->
                        Text(
                            text = suggestion.replaceFirstChar { it.uppercase() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    textInput = suggestion
                                    showSuggestions = false
                                }
                                .padding(12.dp),
                            fontSize = 16.sp
                        )
                        if (suggestion != filteredSuggestions.last()) {
                            Divider()
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                onTargetSelected(textInput)
                onStartNavigation()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = textInput.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Start Navigation",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
