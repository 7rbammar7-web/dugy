@file:OptIn(ExperimentalMaterial3Api::class)

package app.dugy.ui.quotes

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dugy.AppContainer
import app.dugy.data.Quotes
import java.time.LocalDate

@Composable
fun QuotesTab(container: AppContainer, modifier: Modifier = Modifier) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val todayIndex = LocalDate.now().dayOfYear % Quotes.all.size
    val indexed = Quotes.all.withIndex().toList()
    val shown = if (onlyFavorites) indexed.filter { it.index in settings.favoriteQuotes } else indexed

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("خاطرة اليوم", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(Quotes.all[todayIndex], style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !onlyFavorites, onClick = { onlyFavorites = false }, label = { Text("الكل") })
                FilterChip(selected = onlyFavorites, onClick = { onlyFavorites = true }, label = { Text("المفضلة") })
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    "لم تُضف خواطر إلى المفضلة بعد. اضغط القلب بجانب أي خاطرة لحفظها هنا.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        items(shown, key = { it.index }) { (index, text) ->
            val fav = index in settings.favoriteQuotes
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "نُسخت الخاطرة", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "نسخ")
                    }
                    IconButton(onClick = { container.settings.toggleFavorite(index) }) {
                        Icon(
                            if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (fav) "إزالة من المفضلة" else "إضافة إلى المفضلة",
                            tint = if (fav) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
