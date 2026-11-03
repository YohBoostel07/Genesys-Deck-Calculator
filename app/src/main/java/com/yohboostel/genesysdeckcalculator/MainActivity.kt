package com.yohboostel.genesysdeckcalculator

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.text.Normalizer
import java.util.Locale

private val Navy = Color(0xFF080D18)
private val Panel = Color(0xFF121B2B)
private val Blue = Color(0xFF55B7FF)
private val Muted = Color(0xFF91A2B9)
private val SurfaceRaised = Color(0xFF19263A)
private val BorderSoft = Color(0xFF26364D)
private val AccentGreen = Color(0xFF65D6B0)

private const val OFFICIAL_GENESYS_URL = "https://www.yugioh-card.com/en/genesys/"
private fun normalizeCardName(value:String):String = Normalizer.normalize(value.lowercase(Locale.ROOT),Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"),"").replace(Regex("[^a-z0-9]"),"")

data class YgoCard(val id:Int,val name:String,val type:String,val image:String,val points:Int?,val desc:String="")
enum class DeckSection { MAIN, EXTRA, SIDE }
data class DeckCard(val card:YgoCard,val section:DeckSection)
data class SavedDeck(val id:String,val name:String,val limit:Int,val cards:List<DeckCard>)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GenesysApp() }
    }
}

private fun isExtraDeckMonster(card:YgoCard):Boolean = listOf("fusion","synchro","xyz","link").any { card.type.contains(it,ignoreCase=true) }
private fun isForbiddenGenesysCard(card:YgoCard):Boolean = card.type.contains("link",ignoreCase=true) || card.type.contains("pendulum",ignoreCase=true)

private fun parseCard(obj: JSONObject): YgoCard {
    val images=obj.optJSONArray("card_images")
    val image=if(images!=null && images.length()>0) images.getJSONObject(0).optString("image_url") else ""
    val raw=obj.opt("genesys_points")
    val points=when(raw) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }
    return YgoCard(obj.optInt("id"),obj.optString("name"),obj.optString("type"),image,points,obj.optString("desc"))
}

private fun encodeYdkePart(ids:List<Int>):String {
    val buffer=ByteBuffer.allocate(ids.size*4).order(ByteOrder.LITTLE_ENDIAN)
    ids.forEach { buffer.putInt(it) }
    return Base64.encodeToString(buffer.array(),Base64.NO_WRAP)
}
private fun buildYdke(cards:List<DeckCard>):String {
    val main=cards.filter { it.section==DeckSection.MAIN }.map { it.card.id }
    val extra=cards.filter { it.section==DeckSection.EXTRA }.map { it.card.id }
    val side=cards.filter { it.section==DeckSection.SIDE }.map { it.card.id }
    return "ydke://"+encodeYdkePart(main)+"!"+encodeYdkePart(extra)+"!"+encodeYdkePart(side)+"!"
}

private fun decodeYdkePart(part:String): List<Int> {
    if(part.isBlank()) return emptyList()
    val bytes=try {
        Base64.decode(part,Base64.DEFAULT)
    } catch(_:IllegalArgumentException) {
        throw IllegalArgumentException("O código YDKE contém uma seção Base64 inválida.")
    }
    require(bytes.size % 4 == 0) { "O código YDKE está incompleto ou corrompido." }
    val ids= mutableListOf<Int>()
    var offset=0
    while(offset+4<=bytes.size) {
        val id=ByteBuffer.wrap(bytes,offset,4).order(ByteOrder.LITTLE_ENDIAN).int
        require(id>0) { "O código YDKE contém um identificador de carta inválido." }
        ids.add(id)
        offset+=4
    }
    return ids
}

private fun parseDeckCode(raw:String): Map<DeckSection,List<Int>> {
    val text=raw.trim()
    if(text.startsWith("ydke://",true)) {
        val parts=text.substringAfter("://").split("!")
        require(parts.size>=3) { "Código YDKE incompleto. Ele precisa ter Main, Extra e Side." }
        return mapOf(
            DeckSection.MAIN to decodeYdkePart(parts[0]),
            DeckSection.EXTRA to decodeYdkePart(parts[1]),
            DeckSection.SIDE to decodeYdkePart(parts[2])
        )
    }
    var section=DeckSection.MAIN
    val parsed=mutableMapOf(
        DeckSection.MAIN to mutableListOf<Int>(),
        DeckSection.EXTRA to mutableListOf<Int>(),
        DeckSection.SIDE to mutableListOf<Int>()
    )
    var foundMarker=false
    text.lines().forEach { original ->
        val line=original.trim()
        when(line.lowercase()) {
            "#main" -> { section=DeckSection.MAIN; foundMarker=true }
            "#extra" -> { section=DeckSection.EXTRA; foundMarker=true }
            "!side" -> { section=DeckSection.SIDE; foundMarker=true }
            else -> {
                if(line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("!")) {
                    line.toIntOrNull()?.takeIf { it>0 }?.let { parsed.getValue(section).add(it) }
                }
            }
        }
    }
    require(foundMarker && parsed.values.any { it.isNotEmpty() }) {
        "Formato não reconhecido. Cole o código YDKE (ydke://...) ou o conteúdo de um arquivo .ydk."
    }
    return parsed
}

@Composable
private fun TechCircuitBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 34.dp.toPx()
        val grid = Color(0xFF17365B).copy(alpha = 0.20f)
        var x = 0f
        while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = 0f
        while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); y += step }
        val glow = Color(0xFF168BFF).copy(alpha = 0.22f)
        val nodes = listOf(Offset(size.width*.08f,size.height*.18f), Offset(size.width*.91f,size.height*.28f), Offset(size.width*.78f,size.height*.82f), Offset(size.width*.14f,size.height*.76f))
        nodes.forEachIndexed { index, point ->
            val next = nodes[(index + 1) % nodes.size]
            val midX = (point.x + next.x) / 2f
            drawLine(glow, point, Offset(midX, point.y), 2f)
            drawLine(glow, Offset(midX, point.y), Offset(midX, next.y), 2f)
            drawCircle(glow, 5.dp.toPx(), point)
            drawCircle(glow.copy(alpha=.10f), 18.dp.toPx(), point, style=Stroke(1.dp.toPx()))
        }
    }
}
@Composable
fun GenesysApp() {
    val context=androidx.compose.ui.platform.LocalContext.current
    val clipboard=LocalClipboardManager.current
    val deck= remember { mutableStateListOf<DeckCard>() }
    var catalogue by remember { mutableStateOf<List<YgoCard>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var deckName by remember { mutableStateOf("Meu Deck") }
    var loading by remember { mutableStateOf(false) }
    var importLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(4) }
    fun goBack() { tab=when(tab) { 0->2; 1->4; 2->1; 3->4; 5->4; else->4 } }
    BackHandler(enabled=tab!=4) { goBack() }
    var section by remember { mutableStateOf(DeckSection.MAIN) }
    var limit by remember { mutableIntStateOf(100) }
    val savedDecks= remember {
        mutableStateListOf<SavedDeck>().apply {
            val raw=context.getSharedPreferences("genesys_decks",0).getString("saved_decks","[]") ?: "[]"
            try {
                val array=org.json.JSONArray(raw)
                for(i in 0 until array.length()) {
                    val item=array.getJSONObject(i)
                    val cardsJson=item.optJSONArray("cards") ?: org.json.JSONArray()
                    val cards=buildList {
                        for(j in 0 until cardsJson.length()) {
                            val card=cardsJson.getJSONObject(j)
                            add(DeckCard(YgoCard(card.optInt("id"),card.optString("name"),card.optString("type"),card.optString("image"),if(card.isNull("points")) null else card.optInt("points"),card.optString("desc")),DeckSection.valueOf(card.optString("section","MAIN"))))
                        }
                    }
                    add(SavedDeck(item.optString("id"),item.optString("name"),item.optInt("limit",100),cards))
                }
            } catch(_:Exception) { }
        }
    }
    var activeDeckId by remember { mutableStateOf<String?>(null) }
    var editingDeck by remember { mutableStateOf(false) }
    var deckSearchActive by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<YgoCard?>(null) }
    fun persistSavedDecks() {
        val array=org.json.JSONArray()
        savedDecks.forEach { saved ->
            val cardsJson=org.json.JSONArray()
            saved.cards.forEach { entry ->
                cardsJson.put(JSONObject().put("id",entry.card.id).put("name",entry.card.name).put("type",entry.card.type).put("image",entry.card.image).put("points",entry.card.points ?: JSONObject.NULL).put("desc",entry.card.desc).put("section",entry.section.name))
            }
            array.put(JSONObject().put("id",saved.id).put("name",saved.name).put("limit",saved.limit).put("cards",cardsJson))
        }
        context.getSharedPreferences("genesys_decks",0).edit().putString("saved_decks",array.toString()).apply()
    }
    fun addCardToDeck(card:YgoCard, preferredSection:DeckSection) {
        if(isForbiddenGenesysCard(card)) {
            message="Esta carta não é permitida no formato Genesys (Link/Pendulum)."
            return
        }
        val copies=deck.count { it.card.id==card.id }
        if(copies>=3) {
            message="Limite atingido: máximo de 3 cópias desta carta."
            return
        }
        val extraMonster=isExtraDeckMonster(card)
        val destination=if(extraMonster) {
            if(deck.count { it.section==DeckSection.EXTRA }<15) DeckSection.EXTRA else DeckSection.SIDE
        } else preferredSection
        deck.add(DeckCard(card,destination))
        message=if(extraMonster && destination==DeckSection.SIDE)
            "${card.name} enviada ao Side Deck: Extra Deck cheio (15 cartas)."
        else "${card.name} adicionada ao ${destination.name}."
    }
    fun saveCurrentDeck() {
        if(deck.isEmpty()) { message="Adicione cartas antes de salvar o deck."; return }
        if(deckName.isBlank()) { message="Digite um nome para o deck."; return }
        val id=activeDeckId ?: System.currentTimeMillis().toString()
        val saved=SavedDeck(id,deckName.trim(),limit,deck.toList())
        val index=savedDecks.indexOfFirst { it.id==id }
        if(index>=0) savedDecks[index]=saved else savedDecks.add(saved)
        activeDeckId=id
        persistSavedDecks()
        query=""
        deckSearchActive=false
        message="Deck salvo: ${saved.name}"
    }
    val scope= rememberCoroutineScope()
    val total=deck.sumOf { it.card.points ?: 0 }
    val filtered=remember(query,catalogue) {
        if(query.isBlank()) emptyList() else {
            val searchTerms=query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            catalogue.asSequence()
                .filter { card ->
                    val cardName=card.name
                    (cardName.contains(query.trim(),ignoreCase=true) ||
                        searchTerms.all { term -> cardName.contains(term,ignoreCase=true) }) ||
                        card.id.toString()==query.trim()
                }
                .toList()
        }
    }

    fun loadCatalogue(force:Boolean=false) {
        if(loading || (catalogue.isNotEmpty() && !force)) return
        loading=true
        message=if(catalogue.isEmpty()) "Carregando cartas e pontuação oficial..." else "Verificando atualizações oficiais da Konami..."
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    val cache=context.getFileStreamPath("genesys_cards_all.json")
                    val client=OkHttpClient.Builder().callTimeout(120,TimeUnit.SECONDS).connectTimeout(30,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).build()
                    fun fetchText(url:String):String {
                        val request=Request.Builder().url(url).header("User-Agent","GenesysDeckCalculator/1.0 (Android)").build()
                        return client.newCall(request).execute().use { response ->
                            if(!response.isSuccessful) throw Exception("HTTP "+response.code)
                            response.body?.string() ?: throw Exception("Resposta vazia")
                        }
                    }
                    val root:JSONObject
                    if(cache.exists()) root=JSONObject(cache.readText())
                    else {
                        val allJson=JSONObject(fetchText("https://db.ygoprodeck.com/api/v7/cardinfo.php"))
                        val allData=allJson.optJSONArray("data") ?: throw Exception("A base completa de cartas não foi retornada.")
                        val byId=linkedMapOf<Int,JSONObject>()
                        for(i in 0 until allData.length()) {
                            val obj=allData.getJSONObject(i)
                            val saved=JSONObject(obj.toString())
                            saved.put("name_en",obj.optString("name"))
                            byId[obj.optInt("id")]=saved
                        }
                        val portuguese=JSONObject(fetchText("https://db.ygoprodeck.com/api/v7/cardinfo.php?language=pt")).optJSONArray("data")
                        if(portuguese!=null) for(i in 0 until portuguese.length()) {
                            val translated=portuguese.getJSONObject(i)
                            byId[translated.optInt("id")]?.let { target ->
                                listOf("name","type","desc").forEach { key -> if(translated.has(key)) target.put(key,translated.opt(key)) }
                            }
                        }
                        root=JSONObject().put("data",org.json.JSONArray().apply { byId.values.forEach { put(it) } })
                    }
                    val cardArray=root.optJSONArray("data") ?: throw Exception("O cache do catálogo está inválido.")
                    if((0 until cardArray.length()).any { !cardArray.getJSONObject(it).has("name_en") }) {
                        val englishData=JSONObject(fetchText("https://db.ygoprodeck.com/api/v7/cardinfo.php")).optJSONArray("data")
                            ?: throw Exception("Não foi possível atualizar os nomes originais das cartas.")
                        val englishNames=HashMap<Int,String>()
                        for(i in 0 until englishData.length()) {
                            val item=englishData.getJSONObject(i)
                            englishNames[item.optInt("id")]=item.optString("name")
                        }
                        for(i in 0 until cardArray.length()) {
                            val item=cardArray.getJSONObject(i)
                            englishNames[item.optInt("id")]?.let { item.put("name_en",it) }
                        }
                    }
                    var officialCount=0
                    var officialSync=true
                    try {
                        val html=fetchText(OFFICIAL_GENESYS_URL)
                        val officialPoints=linkedMapOf<String,Int>()
                        Jsoup.parse(html).select("table tr").forEach { row ->
                            val cells=row.select("th, td")
                            if(cells.size>=2) {
                                val cardName=cells[0].text().trim()
                                val points=cells[cells.size-1].text().trim().toIntOrNull()
                                if(cardName.isNotBlank() && points!=null) officialPoints[normalizeCardName(cardName)]=points
                            }
                        }
                        require(officialPoints.size>=100) { "A tabela oficial da Konami não foi lida integralmente." }
                        for(i in 0 until cardArray.length()) {
                            val card=cardArray.getJSONObject(i)
                            card.put("genesys_points",0)
                            val officialName=card.optString("name_en",card.optString("name"))
                            officialPoints[normalizeCardName(officialName)]?.let { points ->
                                card.put("genesys_points",points)
                                officialCount++
                            }
                        }
                        root.put("points_source",OFFICIAL_GENESYS_URL)
                        root.put("points_synced_at",System.currentTimeMillis())
                    } catch(e:Exception) {
                        officialSync=false
                        if(root.optString("points_source")==OFFICIAL_GENESYS_URL) {
                            officialCount=(0 until cardArray.length()).count { cardArray.getJSONObject(it).optInt("genesys_points",0)>0 }
                        } else {
                            for(i in 0 until cardArray.length()) cardArray.getJSONObject(i).remove("genesys_points")
                        }
                    }
                    context.openFileOutput("genesys_cards_all.json",0).bufferedWriter().use { it.write(root.toString()) }
                    val cards=buildList { for(i in 0 until cardArray.length()) add(parseCard(cardArray.getJSONObject(i))) }
                    Triple(cards,officialCount,officialSync)
                }
                catalogue=result.first
                val updatedById=catalogue.associateBy { it.id }
                for(index in deck.indices) {
                    val current=deck[index]
                    updatedById[current.card.id]?.let { deck[index]=current.copy(card=it) }
                }
                message=when {
                    result.third -> "Pontuação oficial da Konami sincronizada: ${result.second} cartas com pontos definidos."
                    result.second>0 -> "Sem conexão com a Konami. Mantida a última pontuação oficial salva."
                    else -> "Catálogo carregado, mas não foi possível verificar a lista oficial de pontos. Tente atualizar novamente."
                }
            } catch(e:Exception) {
                message=e.message ?: "Não foi possível carregar o catálogo."
            } finally { loading=false }
        }
    }

    fun importDeck() {
        if(importText.isBlank()) { message="Cole o código do deck primeiro."; return }
        importLoading=true
        message=""
        scope.launch {
            try {
                val ids=parseDeckCode(importText)
                val imported=withContext(Dispatchers.Default) {
                    val byId=catalogue.associateBy { it.id }
                    ids.flatMap { (area,cardIds) -> cardIds.mapNotNull { id -> byId[id]?.let { DeckCard(it,area) } } }
                }
                val allIds=ids.values.flatten()
                val overLimit=allIds.groupingBy { it }.eachCount().filterValues { it>3 }
                require(overLimit.isEmpty()) { "O deck importado contém mais de 3 cópias de uma mesma carta. Corrija a lista antes de importar." }
                val missing=allIds.size-imported.size
                deck.clear()
                deck.addAll(imported)
                val id=System.currentTimeMillis().toString()
                activeDeckId=id
                savedDecks.add(SavedDeck(id,deckName.ifBlank { "Meu Deck" }.trim(),limit,imported))
                persistSavedDecks()
                tab=1
                importText=""
                message=if(missing>0) "Deck importado e salvo. $missing código(s) não foram encontrados no catálogo." else "Deck importado e salvo: ${imported.size} cartas."
            } catch(e:Exception) { message=e.message ?: "Não foi possível importar o deck." }
            finally { importLoading=false }
        }
    }

    LaunchedEffect(Unit) { loadCatalogue() }

    MaterialTheme(colorScheme=darkColorScheme(primary=Blue,secondary=AccentGreen,background=Navy,surface=Panel,onBackground=Color.White,onSurface=Color.White)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF07101F), Color(0xFF0A1830), Color(0xFF050B16))))) {
            TechCircuitBackground()
            Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
            Spacer(Modifier.height(if(tab==4) 5.dp else 8.dp))
            Spacer(Modifier.height(if(tab==1) 4.dp else 12.dp))
            if(message.isNotEmpty() && tab!=3) Text(message,color=if(message.contains("não") || message.contains("Falha") || message.contains("Erro")) Color(0xFFFFA0A0) else AccentGreen,fontSize=11.sp,modifier=Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(10.dp)).padding(horizontal=11.dp,vertical=8.dp))
            when(tab) {
                5 -> {
                    Spacer(Modifier.height(48.dp))
                    Text("NOVO DECK",color=Blue,fontSize=13.sp,fontWeight=FontWeight.Bold,letterSpacing=3.sp)
                    Text("Como vamos chamar seu deck?",color=Color.White,fontSize=25.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.padding(top=12.dp,bottom=8.dp))
                    Text("Escolha um nome para identificar sua lista. Você poderá alterá-lo depois.",color=Muted,fontSize=13.sp)
                    Spacer(Modifier.height(28.dp))
                    OutlinedTextField(value=deckName,onValueChange={deckName=it},label={Text("Nome do deck")},placeholder={Text("Ex.: Meu Deck Genesys")},singleLine=true,modifier=Modifier.fillMaxWidth(),colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedLabelColor=Blue,unfocusedLabelColor=Muted,cursorColor=Blue))
                    Spacer(Modifier.height(18.dp))
                    Button(onClick={
                        if(deckName.isBlank()) message="Digite um nome para continuar."
                        else { deckName=deckName.trim(); query=""; section=DeckSection.MAIN; editingDeck=false; tab=2 }
                    },enabled=deckName.isNotBlank(),modifier=Modifier.fillMaxWidth().height(54.dp),colors=ButtonDefaults.buttonColors(containerColor=Blue)) {
                        Text("CONTINUAR PARA PESQUISA DE CARTAS",color=Navy,fontWeight=FontWeight.ExtraBold,fontSize=12.sp)
                    }
                    TextButton(onClick={tab=1},modifier=Modifier.align(Alignment.CenterHorizontally)) { Text("Cancelar",color=Muted) }
                }
                4 -> {
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(Brush.linearGradient(listOf(Color(0xFF168BFF),Color(0xFF42D7FF))),RoundedCornerShape(15.dp)),contentAlignment=Alignment.Center) { Text("G",color=Navy,fontSize=29.sp,fontWeight=FontWeight.Black) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("GENESYS",color=Blue,fontSize=12.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=4.sp)
                            Text("DECK CALCULATOR",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Black,letterSpacing=1.sp)
                        }
                        Text("● ONLINE",color=AccentGreen,fontSize=9.sp,fontWeight=FontWeight.Bold)
                    }
                    Spacer(Modifier.height(26.dp))
                    Box(Modifier.fillMaxWidth().height(190.dp).background(Brush.linearGradient(listOf(Color(0xFF123D68),Color(0xFF101E38),Color(0xFF102B42))),RoundedCornerShape(24.dp))) {
                        Canvas(Modifier.fillMaxSize()) {
                            val c=Offset(size.width*.79f,size.height*.48f)
                            for(i in 1..5) drawCircle(Color(0xFF49B9FF).copy(alpha=.16f),i*25.dp.toPx(),c,style=Stroke(1.2.dp.toPx()))
                            drawLine(Color(0xFF4BC8FF).copy(alpha=.55f),Offset(size.width*.52f,size.height*.78f),Offset(size.width*.93f,size.height*.20f),2.dp.toPx())
                        }
                        Column(Modifier.align(Alignment.CenterStart).padding(start=19.dp,end=95.dp)) {
                            Text("DUEL SYSTEM",color=Color(0xFF71D8FF),fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=3.sp)
                            Text("Monte seu deck perfeito.",color=Color.White,fontSize=25.sp,lineHeight=29.sp,fontWeight=FontWeight.Black)
                            Text("Construa • Analise • Domine",color=Color(0xFFB7D5F0),fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
                        }
                        Text("GX",Modifier.align(Alignment.CenterEnd).padding(end=15.dp),color=Color(0xFF6BCBFF).copy(alpha=.72f),fontSize=39.sp,fontWeight=FontWeight.Black)
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("CENTRAL DE COMANDO",color=Color.White,fontSize=12.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=2.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                        Column(Modifier.weight(1f).height(140.dp).background(Brush.linearGradient(listOf(Color(0xFF168BFF),Color(0xFF07518E))),RoundedCornerShape(19.dp)).clickable { tab=1 }.padding(15.dp),verticalArrangement=Arrangement.SpaceBetween) {
                            Icon(Icons.Default.Add,null,tint=Color.White,modifier=Modifier.size(27.dp))
                            Column { Text("MEUS DECKS",color=Color.White,fontSize=14.sp,fontWeight=FontWeight.Black); Text("${savedDecks.size} listas salvas",color=Color.White.copy(alpha=.78f),fontSize=10.sp) }
                        }
                        Column(Modifier.weight(1f).height(140.dp).background(Color(0xFF14243A),RoundedCornerShape(19.dp)).clickable { deckName="";importText="";tab=3 }.padding(15.dp),verticalArrangement=Arrangement.SpaceBetween) {
                            Icon(Icons.Default.ArrowBack,null,tint=Color(0xFF6CCBFF),modifier=Modifier.size(27.dp))
                            Column { Text("IMPORTAR",color=Color.White,fontSize=14.sp,fontWeight=FontWeight.Black); Text("EDOPro • YDKE • YDK",color=Muted,fontSize=10.sp) }
                        }
                    }
                    Spacer(Modifier.height(11.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                        Column(Modifier.weight(1f).height(112.dp).background(Color(0xFF14243A),RoundedCornerShape(19.dp)).clickable { deck.clear();deckName="";limit=100;activeDeckId=null;query="";tab=5 }.padding(15.dp),verticalArrangement=Arrangement.SpaceBetween) {
                            Icon(Icons.Default.Add,null,tint=AccentGreen,modifier=Modifier.size(24.dp))
                            Text("NOVO DECK",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.Black)
                        }
                        Column(Modifier.weight(1f).height(112.dp).background(Color(0xFF14243A),RoundedCornerShape(19.dp)).clickable { tab=0 }.padding(15.dp),verticalArrangement=Arrangement.SpaceBetween) {
                            Icon(Icons.Default.Search,null,tint=Color(0xFFB69AFF),modifier=Modifier.size(24.dp))
                            Text("EXPLORAR CARTAS",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("GENESYS FORMAT • PONTUAÇÃO OFICIAL KONAMI",color=Muted,fontSize=9.sp,letterSpacing=1.sp,modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=18.dp))
                }
                0 -> {
                    Row(Modifier.fillMaxWidth().background(SurfaceRaised,RoundedCornerShape(12.dp)).padding(horizontal=12.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Search,null,tint=Muted)
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(query,{query=it},Modifier.weight(1f).padding(vertical=12.dp),singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(color=Color.White,fontSize=15.sp),decorationBox={ inner -> if(query.isEmpty()) Text("Buscar entre todas as cartas...",color=Muted); inner() })
                    }
                    if(loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(),color=Blue)
                        Text("Baixando e preparando o catálogo completo...",color=Muted,fontSize=12.sp)
                    } else {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text("${catalogue.size} cartas • pontos oficiais Konami",color=Muted,fontSize=11.sp,modifier=Modifier.weight(1f))
                            TextButton(onClick={loadCatalogue(true)}) { Text("ATUALIZAR PONTOS",fontSize=10.sp) }
                        }
                    }
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=18.dp)) {
                        items(filtered,key={it.id}) { card ->
                            Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(12.dp)).padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                AsyncImage(model=card.image,contentDescription=card.name,modifier=Modifier.width(58.dp).height(82.dp).clickable { selectedCard=card })
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f).clickable { selectedCard=card }) {
                                    Text(card.name,color=Color.White,fontWeight=FontWeight.Bold,fontSize=13.sp)
                                    Text(card.type,color=Color(0xFFD2DCE8),fontSize=10.sp,maxLines=2)
                                    Text(if(card.points==null) "Sem pontuação disponível" else "${card.points} pts Genesys",color=Blue,fontWeight=FontWeight.Bold,fontSize=12.sp)
                                }
                                IconButton(onClick={ addCardToDeck(card,section) }) { Icon(Icons.Default.Add,"Adicionar",tint=Blue) }
                            }
                        }
                    }
                }
                1 -> {
                    Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("DECKS SALVOS",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.weight(1f))
                        Button(onClick={deck.clear();deckName="";limit=100;activeDeckId=null;query="";tab=5},colors=ButtonDefaults.buttonColors(containerColor=Blue)) { Text("NOVO DECK",color=Navy,fontWeight=FontWeight.Bold,fontSize=11.sp) }
                    }
                    if(savedDecks.isEmpty()) {
                        Text("Nenhum deck salvo ainda. Crie um deck ou importe um código para ele aparecer aqui.",color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=18.dp))
                    } else {
                        LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp),contentPadding=PaddingValues(bottom=16.dp)) {
                            items(savedDecks,key={it.id}) { saved ->
                                Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(12.dp)).padding(9.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable {
                                        deck.clear();deck.addAll(saved.cards);deckName=saved.name;limit=saved.limit;activeDeckId=saved.id;section=DeckSection.MAIN;editingDeck=false;query="";tab=2
                                    }.padding(vertical=4.dp)) {
                                        Text(saved.name,color=Color.White,fontWeight=FontWeight.Bold,fontSize=15.sp)
                                        Text(saved.cards.size.toString()+" cartas • "+saved.cards.sumOf { it.card.points ?: 0 }+" / "+saved.limit+" pts",color=Muted,fontSize=11.sp)
                                    }
                                    TextButton(onClick={clipboard.setText(AnnotatedString(buildYdke(saved.cards)));message="Código YDKE copiado."}) { Text("EXPORTAR",color=Blue,fontSize=10.sp) }
                                    TextButton(onClick={savedDecks.remove(saved);persistSavedDecks()}) { Text("EXCLUIR",color=Color(0xFFFF8888),fontSize=10.sp) }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    if(editingDeck && deckSearchActive) {
                        Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(12.dp)).padding(horizontal=10.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.Search,null,tint=Muted)
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(query,{query=it},Modifier.weight(1f).padding(vertical=12.dp),singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(color=Color.White,fontSize=14.sp),decorationBox={ inner -> if(query.isEmpty()) Text("Pesquise cartas para adicionar...",color=Muted,fontSize=12.sp); inner() })
                            TextButton(onClick={deckSearchActive=false;query=""}) { Text("FECHAR",fontSize=9.sp,color=Blue) }
                        }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(top=8.dp,bottom=12.dp)) {
                            if(query.isBlank()) {
                                item { Text("Digite o nome da carta para pesquisar.",color=Muted,fontSize=11.sp) }
                            } else if(loading) {
                                item { Text("Carregando catálogo...",color=Muted,fontSize=11.sp) }
                            } else if(filtered.isEmpty()) {
                                item { Text("Nenhuma carta encontrada.",color=Muted,fontSize=11.sp) }
                            } else {
                                items(filtered,key={it.id}) { card ->
                                    val copies=deck.count { it.card.id==card.id }
                                    Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(10.dp)).padding(7.dp),verticalAlignment=Alignment.CenterVertically) {
                                        AsyncImage(model=card.image,contentDescription=card.name,modifier=Modifier.width(43.dp).height(62.dp).clickable { selectedCard=card })
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f).clickable { selectedCard=card }) {
                                            Text(card.name,color=Color.White,fontWeight=FontWeight.Bold,fontSize=11.sp,maxLines=2)
                                            Text("${card.points ?: 0} pts • ${card.type}",color=Muted,fontSize=9.sp,maxLines=1)
                                            Text("$copies/3 cópias no deck",color=Blue,fontSize=9.sp)
                                        }
                                        IconButton(onClick={ if(copies>0) { val index=deck.indexOfLast { it.card.id==card.id }; if(index>=0) deck.removeAt(index); message="${card.name} removida do deck." } },enabled=copies>0) {
                                            Icon(Icons.Default.Remove,"Remover",tint=if(copies>0) Color(0xFFFF8888) else Muted)
                                        }
                                        IconButton(onClick={addCardToDeck(card,section)}) { Icon(Icons.Default.Add,"Adicionar",tint=Blue) }
                                    }
                                }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top=3.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(deckName,color=Color.White,fontSize=13.sp,fontWeight=FontWeight.Bold,maxLines=1)
                                Text("PONTUAÇÃO DO DECK",color=Muted,fontSize=9.sp,fontWeight=FontWeight.Bold)
                                Text("$total / $limit",color=if(total>limit) Color(0xFFFF7474) else Blue,fontSize=25.sp,fontWeight=FontWeight.ExtraBold)
                            }
                            Column(horizontalAlignment=Alignment.End) {
                                Text("${deck.size} cartas",color=Color.White,fontWeight=FontWeight.Bold,fontSize=13.sp)
                                Text("Limite: $limit pts",color=Muted,fontSize=10.sp)
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("SEÇÕES DO DECK",color=Muted,fontSize=10.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                            Button(onClick={editingDeck=!editingDeck;query="";deckSearchActive=false},modifier=Modifier.height(46.dp).defaultMinSize(minWidth=124.dp),shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.buttonColors(containerColor=if(editingDeck) Color(0xFF24415F) else Blue)) {
                                Text(if(editingDeck) "CONCLUIR" else "EDITAR DECK",color=if(editingDeck) Color.White else Navy,fontWeight=FontWeight.ExtraBold,fontSize=13.sp)
                            }
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            DeckSection.values().forEach { area ->
                                FilterChip(selected=section==area,onClick={section=area},label={Text(when(area){DeckSection.MAIN->"Main";DeckSection.EXTRA->"Extra";DeckSection.SIDE->"Side"})},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Blue.copy(alpha=.25f),selectedLabelColor=Blue,labelColor=Color.White))
                            }
                        }
                        if(editingDeck) Text("ADICIONAR CARTAS EM: "+when(section){DeckSection.MAIN->"Main Deck";DeckSection.EXTRA->"Extra Deck";DeckSection.SIDE->"Side Deck"},color=Blue,fontSize=10.sp,modifier=Modifier.padding(bottom=4.dp))
                        if(editingDeck) Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(12.dp)).clickable { deckSearchActive=true }.padding(horizontal=10.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.Search,null,tint=Muted)
                            Spacer(Modifier.width(8.dp))
                            Text("Pesquise cartas para adicionar...",color=Muted,fontSize=12.sp,modifier=Modifier.weight(1f).padding(vertical=11.dp))
                        }
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom=10.dp)) {
                            val area=section
                            val areaCards=deck.filter { it.section==area }
                            Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(when(area) { DeckSection.MAIN -> "DECK PRINCIPAL"; DeckSection.EXTRA -> "DECK ADICIONAL"; DeckSection.SIDE -> "DECK AUXILIAR" },color=Blue,fontWeight=FontWeight.ExtraBold,fontSize=13.sp,letterSpacing=1.sp)
                                Spacer(Modifier.weight(1f))
                                Text("${areaCards.size} cartas",color=Muted,fontSize=11.sp)
                            }
                            HorizontalDivider(color=Blue.copy(alpha=.35f),thickness=1.dp)
                            if(areaCards.isEmpty()) Text("Nenhuma carta nesta seção",color=Muted,fontSize=11.sp,modifier=Modifier.padding(vertical=14.dp))
                            else areaCards.chunked(5).forEach { rowCards ->
                                Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=2.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                    rowCards.forEach { entry ->
                                        Column(Modifier.weight(1f).clickable { selectedCard=entry.card },horizontalAlignment=Alignment.CenterHorizontally) {
                                            Box(Modifier.fillMaxWidth().aspectRatio(0.69f)) {
                                                AsyncImage(model=entry.card.image,contentDescription="Abrir detalhes de ${entry.card.name}",modifier=Modifier.fillMaxSize())
                                                if(entry.card.points!=null) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).background(Blue,RoundedCornerShape(6.dp)).padding(horizontal=4.dp,vertical=2.dp)) { Text("${entry.card.points} pts",color=Navy,fontSize=8.sp,fontWeight=FontWeight.ExtraBold) }
                                            }
                                            Text(entry.card.name,color=Color.White,fontSize=8.sp,lineHeight=9.sp,maxLines=2,minLines=2,fontWeight=FontWeight.Medium)
                                            Text("${entry.card.points ?: 0} pts",color=Blue,fontSize=8.sp,fontWeight=FontWeight.Bold)
                                        }
                                    }
                                    repeat(5-rowCards.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                            Text(if(editingDeck) "Toque numa carta para removê-la. Use a pesquisa para adicionar." else "Toque em EDITAR para adicionar ou remover cartas.",color=Muted,fontSize=10.sp,modifier=Modifier.padding(top=8.dp))
                        }
                        Button(onClick={saveCurrentDeck()},modifier=Modifier.fillMaxWidth().height(46.dp),shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.buttonColors(containerColor=Blue)) { Text("SALVAR DECK",color=Navy,fontWeight=FontWeight.ExtraBold,fontSize=13.sp) }
                        OutlinedButton(onClick={clipboard.setText(AnnotatedString(buildYdke(deck.toList())));message="Código YDKE copiado."},modifier=Modifier.fillMaxWidth().height(42.dp),shape=RoundedCornerShape(12.dp)) { Text("EXPORTAR / COPIAR CÓDIGO YDKE",fontSize=11.sp) }
                    }
                }
                else -> {
                    Text("Importar deck do EDOPro",color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Bold)
                    Text("No EDOPro, use a opção de copiar/compartilhar o deck. Cole aqui o código ydke:// ou o conteúdo de um arquivo .ydk.",color=Muted,fontSize=12.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value=deckName,onValueChange={deckName=it},label={Text("Nome do deck")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=importText,onValueChange={importText=it},label={Text("Código YDKE ou texto .ydk")},modifier=Modifier.fillMaxWidth().weight(1f),minLines=8,maxLines=16,colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedLabelColor=Blue,unfocusedLabelColor=Muted,cursorColor=Blue))
                    Spacer(Modifier.height(10.dp))
                    Button(onClick={importDeck()},enabled=!importLoading && catalogue.isNotEmpty(),modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Blue)) {
                        Text(if(importLoading) "IMPORTANDO..." else "IMPORTAR DECK",color=Navy,fontWeight=FontWeight.Bold)
                    }
                    TextButton(onClick={tab=1},modifier=Modifier.align(Alignment.CenterHorizontally)) { Text("Voltar aos decks",color=Muted) }
                }
            }
            if(selectedCard!=null) {
                val card=selectedCard!!
                AlertDialog(onDismissRequest={selectedCard=null},containerColor=Panel,
                    title={Text(card.name,color=Color.White,fontWeight=FontWeight.ExtraBold)},
                    text={
                        Column(Modifier.fillMaxWidth()) {
                            if(tab==2 && editingDeck) {
                                val copies=deck.count { it.card.id==card.id }
                                Row(Modifier.fillMaxWidth().padding(bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                                    Text("CÓPIAS NO DECK: $copies/3",color=Blue,fontSize=11.sp,fontWeight=FontWeight.Bold)
                                    Spacer(Modifier.width(12.dp))
                                    IconButton(onClick={
                                        val index=deck.indexOfLast { it.card.id==card.id }
                                        if(index>=0) { deck.removeAt(index); message="${card.name} removida do deck." }
                                    },enabled=copies>0) {
                                        Icon(Icons.Default.Remove,"Remover cópia",tint=if(copies>0) Color(0xFFFF8888) else Muted)
                                    }
                                    IconButton(onClick={addCardToDeck(card,section)},enabled=copies<3 && !isForbiddenGenesysCard(card)) {
                                        Icon(Icons.Default.Add,"Adicionar cópia",tint=if(copies<3) Blue else Muted)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().heightIn(max=470.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                AsyncImage(model=card.image,contentDescription=card.name,modifier=Modifier.width(125.dp).height(185.dp))
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                    Text(card.type,color=Blue,fontSize=11.sp,fontWeight=FontWeight.Bold)
                                    Text("${card.points ?: 0} pontos Genesys",color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=5.dp,bottom=10.dp))
                                    Text(card.desc.ifBlank { "Texto/efeito não disponível no catálogo." },color=Color.White,fontSize=12.sp,lineHeight=17.sp)
                                }
                            }
                        }
                    },
                    confirmButton={TextButton(onClick={selectedCard=null}) { Text("FECHAR",color=Blue,fontWeight=FontWeight.Bold) }}
                )
            }
            Spacer(Modifier.height(5.dp))
            Text("Pontuação oficial Konami • sincronização ao abrir o app",color=Muted,fontSize=9.sp,modifier=Modifier.align(Alignment.CenterHorizontally))
            Text(
                "Criado por @yohann_boostel  •  Instagram",
                color=Blue,
                fontSize=10.sp,
                fontWeight=FontWeight.SemiBold,
                modifier=Modifier.align(Alignment.CenterHorizontally).clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/yohann_boostel/")))
                }.padding(top=4.dp,bottom=8.dp)
            )
            }
        }
    }
}