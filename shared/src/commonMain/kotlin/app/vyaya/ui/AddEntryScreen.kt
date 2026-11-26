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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vyaya.core.data.MerchantStore
import app.vyaya.core.model.Category
import app.vyaya.core.model.CategoryKind
import app.vyaya.core.model.Entry
import app.vyaya.core.model.EntrySource
import app.vyaya.core.model.EntryType
import app.vyaya.core.model.Money
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Add an entry by hand, or edit one ([existing]). [onSave] gets what was typed and says whether it was saved (false:
 * the amount or a field isn't valid). Expense or income; a category of that kind; payee, note and the day.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddEntryScreen(
    existing: Entry?,
    categories: List<Category>,
    today: LocalDate,
    onSave: (EntryInput) -> Boolean,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    accounts: List<app.vyaya.core.model.Account> = emptyList(),
    rules: List<app.vyaya.core.model.MerchantRule> = emptyList(),
    /** Merchant memory: the payee's payments of a type so far (null: not known). */
    matches: suspend (String, EntryType) -> MerchantStore.Matches? = { _, _ -> null },
    keyOf: (String) -> String = { it.lowercase().filter(Char::isLetterOrDigit) },
    /** A new entry's amount and day, filled in (from the Ignored tab's "This was a payment"). */
    initialAmount: Money? = null,
    initialDate: LocalDate? = null,
    /** Money in or out, for a new entry filled in from a message (Ignored tab FLAW-004). */
    initialType: EntryType? = null,
    /** Split bills: the people to share with, adding one, and saving a split (its refusal message, or null when saved). */
    people: List<app.vyaya.core.model.Person> = emptyList(),
    onAddPerson: suspend (String) -> app.vyaya.core.model.Person? = { null },
    onSaveSplit: suspend (EntryInput) -> String? = { "Splitting isn't available here" },
    /** Split-bills #3: the friends' shares of [existing] (empty when none), filled in to change; null while being read. */
    shares: List<Pair<app.vyaya.core.model.EntityId, Money>>? = emptyList(),
) {
    var accountId by remember { mutableStateOf(existing?.accountId) }
    var type by remember { mutableStateOf(if ((existing?.type ?: initialType) == EntryType.INCOME) EntryType.INCOME else EntryType.EXPENSE) }
    var amount by remember { mutableStateOf(existing?.amount?.let(::plainAmount) ?: initialAmount?.let(::plainAmount) ?: "") }
    var categoryId by remember { mutableStateOf(existing?.categoryId) }
    var payee by remember { mutableStateOf(existing?.payee ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var date by remember { mutableStateOf(existing?.occurredAt?.let { it.instant.toLocalDateTime(it.timeZone).date } ?: initialDate ?: today) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var always by remember { mutableStateOf(false) }
    // Merchant memory is offered for a payment Vyaya detected: those are the ones capture will see again.
    val detected = existing?.source in setOf(EntrySource.SMS, EntrySource.NOTIFICATION, EntrySource.SCREENSHOT)
    val kindNow = if (type == EntryType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val chosen = categories.firstOrNull { it.id == categoryId && !it.deleted && it.kind == kindNow }
    val key = keyOf(payee)
    // The payee's rule now (merchant memory report #2, #7): the latest live one whose category still exists.
    val ruleCategory = rules.filter { !it.deleted && keyOf(it.payee) == key && key.isNotEmpty() }.maxByOrNull { it.updatedAt }
        ?.let { r -> categories.firstOrNull { it.id == r.categoryId && !it.deleted } }
    val alreadyRemembered = chosen != null && ruleCategory?.id == chosen.id
    val canRemember = detected && key.isNotEmpty() && chosen != null && !alreadyRemembered
    var counts by remember { mutableStateOf<MerchantStore.Matches?>(null) }
    LaunchedEffect(key, type, detected) { counts = if (detected && key.isNotEmpty()) matches(payee.trim(), type) else null }
    var alsoEarlier by remember { mutableStateOf(false) }
    // This entry itself, if still uncategorised in the database, is saved with its category first: not one of the "other" ones.
    val selfOpen = existing != null && existing.categoryId == null && !existing.deleted && existing.type == type && existing.payee?.let(keyOf) == key
    val others = ((counts?.uncategorised ?: 0) - if (selfOpen) 1 else 0).coerceAtLeast(0)
    // Split bills (option A): across the user's categories, and/or with friends (their shares go to People).
    // Split-bills #3: a split payment can be changed (its parts and shares are filled in; Save replaces them).
    val canSplit = type == EntryType.EXPENSE && existing?.generatedBy == null
    val wasSplit = !existing?.splits.isNullOrEmpty()
    val ownParts = existing?.splits.orEmpty().filter { it.categoryId != app.vyaya.core.model.SystemCategories.LENT.id }
    var acrossCategories by remember { mutableStateOf(ownParts.size > 1) }
    var withFriends by remember { mutableStateOf(false) }
    val parts = remember { androidx.compose.runtime.mutableStateListOf<app.vyaya.core.model.EntityId>().apply { if (ownParts.size > 1) addAll(ownParts.map { it.categoryId }) } }
    val partText = remember {
        androidx.compose.runtime.mutableStateMapOf<app.vyaya.core.model.EntityId, String>().apply {
            if (ownParts.size > 1) ownParts.forEach { put(it.categoryId, plainAmount(it.amount)) }
        }
    }
    val friends = remember { androidx.compose.runtime.mutableStateListOf<app.vyaya.core.model.EntityId>() }
    val shareText = remember { androidx.compose.runtime.mutableStateMapOf<app.vyaya.core.model.EntityId, String>() }
    val added = remember { androidx.compose.runtime.mutableStateListOf<app.vyaya.core.model.Person>() }
    var sharesEdited by remember { mutableStateOf(false) }
    var sharesFilled by remember { mutableStateOf(false) }
    LaunchedEffect(shares) {
        if (!sharesFilled && !shares.isNullOrEmpty()) {
            sharesFilled = true; withFriends = true; sharesEdited = true // what was saved stays as it was, until changed
            shares.forEach { (id, share) -> if (id !in friends) friends += id; shareText[id] = plainAmount(share) }
        }
    }
    var newPerson by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val total = Money.parse(amount.trim())
    // Equal shares, until the user types one: any odd paise stay with the user.
    LaunchedEffect(amount, friends.toList()) {
        if (total == null || total.paise <= 0 || friends.isEmpty()) return@LaunchedEffect
        if (!sharesEdited) {
            val equal = app.vyaya.core.engine.SplitShares.equal(total, friends.size)
            friends.forEachIndexed { i, id -> shareText[id] = plainAmount(equal.friends[i]) }
        } else {
            // Split-bills #5: a friend added after a share was typed gets an equal part of what's left (with you); typed ones stay.
            val empty = friends.filter { shareText[it].isNullOrBlank() }
            val left = total.paise - friends.filter { it !in empty }.sumOf { Money.parse(shareText[it].orEmpty().trim())?.paise ?: 0L }
            if (empty.isNotEmpty() && left > 0) {
                val equal = app.vyaya.core.engine.SplitShares.equal(Money(left), empty.size)
                empty.forEachIndexed { i, id -> shareText[id] = plainAmount(equal.friends[i]) }
            }
        }
    }
    val friendsTotal = friends.sumOf { Money.parse(shareText[it].orEmpty().trim())?.paise ?: 0L }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (existing == null) "Add entry" else "Edit entry", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                val input = EntryInput(type, amount, categoryId, payee, note, date, accountId, alwaysForPayee = always && canRemember,
                    alsoEarlier = always && canRemember && alsoEarlier && others > 0,
                    splitCategories = if (canSplit && acrossCategories) parts.map { it to partText[it].orEmpty() } else emptyList(),
                    friendShares = if (canSplit && withFriends) friends.map { it to shareText[it].orEmpty() } else emptyList())
                val problem = input.problem(existing, TimeZone.currentSystemDefault())
                when {
                    problem != null -> error = problem
                    wasSplit && shares == null -> error = "One moment: still reading who shared it"
                    // A split payment saved without its parts and friends isn't split any more: the split store takes the shares back.
                    input.isSplit || wasSplit -> scope.launchSafely({ error = ACTION_FAILED }) { error = onSaveSplit(input) }
                    else -> error = if (onSave(input)) null else "This entry can't be saved"
                }
            }) { Text("Save") }
            OutlinedButton(onClose) { Text("Cancel") }
            if (existing != null) TextButton(onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        val fromRule = existing?.generatedBy != null
        if (fromRule) Text("From a recurring rule: its type stays as the rule's", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(type == EntryType.EXPENSE, { type = EntryType.EXPENSE; categoryId = null }, { Text("Expense") }, enabled = !fromRule)
            FilterChip(type == EntryType.INCOME, { type = EntryType.INCOME; categoryId = null }, { Text("Income") }, enabled = !fromRule)
        }
        OutlinedTextField(amount, { amount = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Amount") },
            textStyle = MaterialTheme.typography.headlineMedium,
            prefix = { Text("₹") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
            isError = error != null, supportingText = error?.let { { Text(it) } })
        val kind = if (type == EntryType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        val shown = categories.filter { it.kind == kind && (!it.deleted || it.id == existing?.categoryId) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Catalog report #6: an entry's own deleted category stays visible, so what's saved is what's shown.
            shown.forEach { c ->
                val label = if (c.deleted) "${c.name} (deleted)" else c.name
                if (canSplit && acrossCategories) {
                    FilterChip(c.id in parts, { if (c.id in parts) { parts -= c.id; partText -= c.id } else parts += c.id }, { Text(label) })
                } else {
                    FilterChip(categoryId == c.id, { categoryId = if (categoryId == c.id) null else c.id }, { Text(label) })
                }
            }
        }
        if (canSplit) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(acrossCategories, { acrossCategories = !acrossCategories; error = null }, { Text("Split across categories") })
                FilterChip(withFriends, { withFriends = !withFriends; error = null }, { Text("Split with friends") })
            }
            if (acrossCategories) {
                Text("Tap the categories, then what went to each" + if (withFriends) " (your share only)." else ".", style = MaterialTheme.typography.bodySmall)
                parts.forEach { id ->
                    val c = categories.firstOrNull { it.id == id } ?: return@forEach
                    OutlinedTextField(partText[id].orEmpty(), { partText[id] = it; error = null }, Modifier.fillMaxWidth(), label = { Text("${c.name} ₹") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                }
            }
            if (withFriends) {
                val everyone = (people + added).distinctBy { it.id }
                if (everyone.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    everyone.forEach { p ->
                        FilterChip(p.id in friends, { if (p.id in friends) { friends -= p.id; shareText -= p.id } else friends += p.id }, { Text(p.name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newPerson, { newPerson = it.take(100) }, Modifier.weight(1f), label = { Text("New person's name") }, singleLine = true)
                    TextButton({
                        val name = newPerson.trim()
                        if (name.isNotEmpty()) scope.launchSafely({ error = ACTION_FAILED }) { onAddPerson(name)?.let { p -> added += p; friends += p.id; newPerson = "" } }
                    }) { Text("Add person") }
                }
                friends.forEach { id ->
                    val who = everyone.firstOrNull { it.id == id }?.name.orEmpty()
                    OutlinedTextField(shareText[id].orEmpty(), { shareText[id] = it; sharesEdited = true; error = null }, Modifier.fillMaxWidth(),
                        label = { Text("$who's share") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                }
                if (total != null && friends.isNotEmpty()) {
                    Text("Your share: ${Money(total.paise - friendsTotal).format()}. Their shares go to People, as money they owe you.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        val name = payee.trim()
        if (detected && alreadyRemembered) {
            Text("${chosen!!.name}: remembered for $name", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (canRemember) {
            CheckRow(always, { always = it },
                if (ruleCategory != null) "$name goes in ${ruleCategory.name}. Always put it in ${chosen!!.name} instead"
                else "Always put $name in ${chosen!!.name}")
            // Merchant memory report #4: how far it reaches, and a warning when the name is too short to be sure.
            val reach = counts?.total?.let { if (it == 1) "1 payment to $name so far" else "$it payments to $name so far" }
            val short = if (key.length < 3) "Short name: other payees may match" else null
            listOfNotNull(reach, short).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(". "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (always && others > 0) CheckRow(alsoEarlier, { alsoEarlier = it },
                "Also put the $others other $name ${if (others == 1) "payment" else "payments"} with no category in ${chosen.name}")
        }
        // FLAW-005: the limits stop the typing, rather than a refusal at Save.
        if (accounts.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            accounts.filter { !it.deleted || it.id == existing?.accountId }.forEach { a ->
                FilterChip(accountId == a.id, { accountId = if (accountId == a.id) null else a.id }, { Text(accountLabel(a)) })
            }
        }
        OutlinedTextField(payee, { payee = it.take(EntryInput.MAX_PAYEE) }, Modifier.fillMaxWidth(), label = { Text("Payee") }, singleLine = true)
        OutlinedTextField(note, { note = it.take(EntryInput.MAX_NOTE) }, Modifier.fillMaxWidth(), label = { Text("Note") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(date == today, { date = today }, { Text("Today") })
            val yesterday = today.minus(1, DateTimeUnit.DAY)
            FilterChip(date == yesterday, { date = yesterday }, { Text("Yesterday") })
            FilterChip(date != today && date != yesterday, { picking = true }, { Text(if (date != today && date != yesterday) date.toString() else "Pick date") })
        }
    }

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
        DatePickerDialog(onDismissRequest = { picking = false }, confirmButton = {
            TextButton({
                state.selectedDateMillis?.let { date = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
                picking = false
            }) { Text("OK") }
        }) { DatePicker(state) }
    }
}

/** An amount as the user would type it: `250.5` → "250.50", `120` → "120". */
private fun plainAmount(m: Money): String = if (m.paise % 100 == 0L) "${m.paise / 100}" else "${m.paise / 100}.${(m.paise % 100).toString().padStart(2, '0')}"

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(label)
    }
}
