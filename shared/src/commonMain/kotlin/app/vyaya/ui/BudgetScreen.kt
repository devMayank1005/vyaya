package app.vyaya.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vyaya.core.data.BudgetStore
import app.vyaya.core.engine.BudgetProgress
import app.vyaya.core.engine.BudgetState
import app.vyaya.core.model.Budget
import app.vyaya.core.model.Category
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Money
import kotlinx.coroutines.launch

/**
 * Add a budget (a spending category and its monthly limit), or change or remove one. A suggestion from the last
 * finished months fills the limit with one tap. [onSave] answers with the message to show, or null when saved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BudgetForm(
    existing: Budget?,
    categoryName: String?,
    categories: List<Category>,
    suggestion: suspend (EntityId) -> BudgetStore.Suggestion?,
    onSave: suspend (EntityId?, String) -> String?,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    var categoryId by remember { mutableStateOf(existing?.categoryId) }
    var amount by remember { mutableStateOf(existing?.limit?.let(::plainRupees) ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var suggested by remember { mutableStateOf<BudgetStore.Suggestion?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(categoryId) { suggested = categoryId?.let { suggestion(it) } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (existing == null) "Add budget" else categoryName.orEmpty(), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ scope.launch { error = onSave(categoryId, amount) } }) { Text("Save") }
            OutlinedButton(onClose) { Text("Cancel") }
            if (existing != null) TextButton(onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (existing == null) {
            if (categories.isEmpty()) Text("Every spending category has a budget already.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c -> FilterChip(categoryId == c.id, { categoryId = c.id; error = null }, { Text(c.name) }) }
            }
        }
        OutlinedTextField(amount, { amount = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Monthly limit") }, prefix = { Text("₹") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        suggested?.let { s ->
            val months = if (s.months == 1) "last month" else "the last ${s.months} months"
            Text("Suggested ${s.limit.format()}: your average over $months", style = MaterialTheme.typography.bodySmall)
            AssistChip({ amount = plainRupees(s.limit); error = null }, { Text("Use ${s.limit.format()}") })
        }
        Text("A fresh limit each month: what isn't spent doesn't carry over.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Home: each budget against this month's spending, fullest first. */
@Composable
fun BudgetsCard(progress: List<BudgetProgress>, names: Map<EntityId, String>) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Budgets", style = MaterialTheme.typography.titleMedium)
            progress.forEach { p ->
                val colour = when (p.state) {
                    BudgetState.OVER -> MaterialTheme.colorScheme.error
                    BudgetState.NEAR -> MaterialTheme.colorScheme.tertiary
                    BudgetState.OK -> MaterialTheme.colorScheme.primary
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(names[p.budget.categoryId].orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        Text("${p.spent.format()} of ${p.limit.format()}", style = MaterialTheme.typography.bodySmall)
                    }
                    // Drawing only: the bar's length from the whole percent (money stays in paise).
                    LinearProgressIndicator(progress = { (p.percent.coerceIn(0, 100) / 100f) }, Modifier.fillMaxWidth(), color = colour)
                    Text(
                        if (p.state == BudgetState.OVER) "${Money(-p.left.paise).format()} over" else "${p.left.format()} left",
                        style = MaterialTheme.typography.bodySmall, color = colour,
                    )
                }
            }
        }
    }
}
