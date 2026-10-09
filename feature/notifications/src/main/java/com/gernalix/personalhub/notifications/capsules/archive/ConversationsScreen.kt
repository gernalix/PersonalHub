package com.gernalix.personalhub.notifications.capsules.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.notifications.R
import com.gernalix.personalhub.core.ui.HubTimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.Instant

@Composable internal fun ConversationsScreen(onBack: () -> Unit) {
    val context=LocalContext.current
    val archive=remember { ArchiveStore.get(context) }; val store=remember { ConversationsStore(archive) }
    val people=remember { PeopleBridge(context) }; val profile=people.profile
    val changes by ArchiveStore.changes.collectAsState(); val scope=rememberCoroutineScope()
    var conversations by remember { mutableStateOf(emptyList<ConversationRow>()) };var apps by remember { mutableStateOf(emptyList<String>()) }
    var messages by remember { mutableStateOf(emptyList<MessageRow>()) }
    var selected by remember { mutableStateOf<ConversationRow?>(null) }
    var person by remember { mutableStateOf<PersonChoice?>(null) }
    var search by remember { mutableStateOf("") };var app by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") };var until by remember { mutableStateOf("") };var page by remember(search,app,from,until,selected,person,changes,profile) { mutableStateOf(0) }
    var conversationPage by remember(app,changes) { mutableStateOf(0) }
    var failure by remember { mutableStateOf(false) };var pickPerson by remember { mutableStateOf(false) }
    var mapping by remember { mutableStateOf<Pair<String,String>?>(null) }
    var sources by remember { mutableStateOf<List<ArchiveEvent>?>(null) }
    var senderChoices by remember { mutableStateOf<List<IdentityRow>?>(null) }
    var normalized by remember { mutableStateOf(0L) }
    LaunchedEffect(changes) {
        try { withContext(Dispatchers.IO) { while(archive.normalizePending()==100) yield() };normalized++;failure=false }
        catch(_:Exception){failure=true}
    }
    LaunchedEffect(normalized,app,conversationPage) {
        try {
            val result=withContext(Dispatchers.IO){store.conversations(after=if(conversationPage==0)null else conversations.lastOrNull(),app=app)}
            conversations=if(conversationPage==0)result else (conversations+result).distinctBy{it.id}
            apps=withContext(Dispatchers.IO){store.apps()}
        }catch(_:Exception){failure=true}
    }
    LaunchedEffect(normalized,selected,person,search,app,from,until,page,profile) {
        try {
            val bounds=archiveDateBounds(from,until)
            val result=withContext(Dispatchers.IO){store.messages(selected?.id.orEmpty(),person?.id.orEmpty(),profile,app,search,bounds.first,bounds.second,before=if(page==0)null else messages.lastOrNull())}
            messages=if(page==0)result else (messages+result).distinctBy{it.id}
        }catch(_:Exception){failure=true}
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row { TextButton(onClick=onBack){Text(stringResource(R.string.back))};Text(stringResource(R.string.conversations_title),style=MaterialTheme.typography.titleLarge) }
        Text(stringResource(R.string.conversations_limits),style=MaterialTheme.typography.bodySmall)
        if(failure)Text(stringResource(R.string.archive_error))
        OutlinedTextField(search,{search=it;page=0},label={Text(stringResource(R.string.search))},modifier=Modifier.fillMaxWidth())
        var appMenu by remember { mutableStateOf(false) }
        Row {
            Box { TextButton(onClick={appMenu=true}){Text(if(app.isBlank())stringResource(R.string.all_apps)else app)}
                DropdownMenu(appMenu,{appMenu=false}) {
                    DropdownMenuItem(text={Text(stringResource(R.string.all_apps))},onClick={app="";selected=null;conversationPage=0;page=0;appMenu=false})
                    apps.forEach { value->DropdownMenuItem(text={Text(value)},onClick={app=value;selected=null;conversationPage=0;page=0;appMenu=false}) }
                }
            }
            TextButton(onClick={pickPerson=true}){Text(person?.label ?: stringResource(R.string.all_people))}
            if(person!=null)TextButton(onClick={person=null;selected=null}){Text(stringResource(R.string.clear))}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(from,{from=it;page=0},label={Text(stringResource(R.string.date_from))},modifier=Modifier.weight(1f),singleLine=true)
            OutlinedTextField(until,{until=it;page=0},label={Text(stringResource(R.string.date_until))},modifier=Modifier.weight(1f),singleLine=true)
        }
        selected?.let { c ->
            Text(c.title)
            Row {
                TextButton(onClick={selected=null}){Text(stringResource(R.string.all_conversations))}
                TextButton(onClick={mapping="conversation" to c.id}){Text(stringResource(R.string.link_conversation))}
                TextButton(onClick={scope.launch { try { senderChoices=withContext(Dispatchers.IO){store.identities(c.id)} }catch(_:Exception){failure=true} }}){Text(stringResource(R.string.link_identity))}
            }
        }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(selected==null && person==null && search.isBlank() && from.isBlank() && until.isBlank()) {
                items(conversations.filter { app.isBlank() || it.app==app },key={it.id}) { c -> Card(Modifier.fillMaxWidth().clickable { selected=c;page=0 }) { Column(Modifier.padding(12.dp)) {
                    Text(c.app,style=MaterialTheme.typography.labelMedium);Text(c.title)
                    if(c.group)Text(stringResource(R.string.group_conversation))
                    if(c.uncertain)Text(stringResource(R.string.uncertain_thread))
                    TextButton(onClick={mapping="conversation" to c.id}){Text(stringResource(R.string.link_conversation))}
                } } }
                item { TextButton(onClick={conversationPage++}){Text(stringResource(R.string.more))} }
            } else {
                items(messages,key={it.id}) { m -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text("${m.app} · ${m.title}",Modifier.clickable { scope.launch { try { selected=withContext(Dispatchers.IO){store.conversation(m.conversation)} }catch(_:Exception){failure=true} } })
                    Text(if(m.sender.isBlank()) stringResource(R.string.unknown_sender) else m.sender)
                    Text(if(m.text.isBlank()) stringResource(R.string.attachment_only) else m.text)
                    Text(HubTimeFormat.dateTime(Instant.parse(m.time).toEpochMilli()))
                    Text(stringResource(when(m.confidence){"HIGH"->R.string.confidence_high;"MEDIUM"->R.string.confidence_medium;else->R.string.confidence_low}))
                    if(m.partial)Text(stringResource(R.string.partial_content))
                    if(m.revisionOf!=null)Text(stringResource(R.string.possible_revision))
                    Row {
                        TextButton(onClick={scope.launch { try { sources=withContext(Dispatchers.IO){store.sources(m.id).mapNotNull { archive.event(it.event) }} }catch(_:Exception){failure=true} }}){Text(stringResource(R.string.provenance))}
                        TextButton(onClick={mapping="identity" to m.identity}){Text(stringResource(R.string.link_identity))}
                    }
                } } }
                item { if(messages.isEmpty())Text(stringResource(R.string.no_messages))else TextButton(onClick={page++}){Text(stringResource(R.string.more))} }
            }
        }
    }
    if(pickPerson) PeoplePicker(people, emptyList(),onDismiss={pickPerson=false}) { choice -> person=choice;selected=null;page=0;pickPerson=false }
    mapping?.let { target ->
        MappingDialog(people,store,profile,target,onDismiss={mapping=null},onFailure={failure=true})
    }
    senderChoices?.let { choices -> AlertDialog(onDismissRequest={senderChoices=null},title={Text(stringResource(R.string.link_identity))},text={LazyColumn { items(choices,key={it.id}) { row -> TextButton(onClick={mapping="identity" to row.id;senderChoices=null}){Text(if(row.name.isBlank())stringResource(R.string.unknown_sender)else row.name);if(!row.reliable)Text(stringResource(R.string.uncertain_identity))} } }},confirmButton={TextButton(onClick={senderChoices=null}){Text(stringResource(R.string.close))}}) }
    sources?.let { rows -> AlertDialog(onDismissRequest={sources=null},title={Text(stringResource(R.string.provenance))},text={Column { Text(stringResource(R.string.latest_sources));LazyColumn { items(rows,key={it.id}) { row->Text("${stringResource(kindLabel(row.kind))} · ${eventTime(row)}\n${row.snapshot.toString(2)}") } } }},confirmButton={TextButton(onClick={sources=null}){Text(stringResource(R.string.close))}}) }
}

@Composable private fun PeoplePicker(people: PeopleBridge, include: List<String>, onDismiss: () -> Unit, onSelect: (PersonChoice) -> Unit) {
    var search by remember { mutableStateOf("") };var rows by remember { mutableStateOf(emptyList<PersonChoice>()) };var failure by remember { mutableStateOf(false) }
    LaunchedEffect(search,include) { try { rows=withContext(Dispatchers.IO){(people.resolve(include)+people.search(search)).distinctBy { it.id }};failure=false }catch(_:Exception){failure=true} }
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.choose_person))},text={Column {
        OutlinedTextField(search,{search=it},label={Text(stringResource(R.string.search_people))})
        if(failure)Text(stringResource(R.string.archive_error))
        LazyColumn { items(rows,key={it.id}) { p->TextButton(onClick={onSelect(p)}){Text(p.label)} } }
    }},confirmButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.close))}})
}

@Composable private fun MappingDialog(people: PeopleBridge,store: ConversationsStore,profile: String,target: Pair<String,String>,onDismiss: () -> Unit,onFailure: () -> Unit) {
    val scope=rememberCoroutineScope()
    var linked by remember { mutableStateOf(emptyList<String>()) };var resolved by remember { mutableStateOf(emptyList<PersonChoice>()) };var pick by remember { mutableStateOf(false) };var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { try { linked=withContext(Dispatchers.IO){store.linked(profile,target.first,target.second)};resolved=withContext(Dispatchers.IO){people.resolve(linked)} }catch(_:Exception){onFailure()} }
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.people_links))},text={Column {
        Text(stringResource(R.string.manual_link_help))
        LazyColumn {
            items(linked,key={it}) { id->Row {
                Text(resolved.firstOrNull{it.id==id}?.label ?: stringResource(R.string.person_unavailable),Modifier.weight(1f))
                TextButton(onClick={scope.launch { try { withContext(Dispatchers.IO){store.map(profile,id,target.first,target.second,false)};reload++ }catch(_:Exception){onFailure()} }}){Text(stringResource(R.string.unlink))}
            } }
        }
        TextButton(onClick={pick=true}){Text(stringResource(R.string.add_person))}
    }},confirmButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.close))}})
    if(pick)PeoplePicker(people,linked,onDismiss={pick=false}) { choice -> scope.launch {
        try { withContext(Dispatchers.IO) { check(people.exists(profile,choice.id));store.map(profile,choice.id,target.first,target.second,true) };reload++;pick=false }catch(_:Exception){onFailure()}
    } }
}
