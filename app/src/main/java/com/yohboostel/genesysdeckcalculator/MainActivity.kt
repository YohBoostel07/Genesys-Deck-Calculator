package com.yohboostel.genesysdeckcalculator

import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF111F32)
private val Blue = Color(0xFF38A5FF)
private val Muted = Color(0xFF9BAEC5)

private const val OFFICIAL_GENESYS_URL = "https://www.yugioh-card.com/en/genesys/"
private fun normalizeCardName(value:String):String = Normalizer.normalize(value.lowercase(Locale.ROOT),Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"),"").replace(Regex("[^a-z0-9]"),"")

data class YgoCard(val id:Int,val name:String,val type:String,val image:String,val points:Int?)
enum class DeckSection { MAIN, EXTRA, SIDE }
data class DeckCard(val card:YgoCard,val section:DeckSection)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GenesysApp() }
    }
}

private fun parseCard(obj: JSONObject): YgoCard {
    val images=obj.optJSONArray("card_images")
    val image=if(images!=null && images.length()>0) images.getJSONObject(0).optString("image_url_small") else ""
    val raw=obj.opt("genesys_points")
    val points=when(raw) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }
    return YgoCard(obj.optInt("id"),obj.optString("name"),obj.optString("type"),image,points)
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
fun GenesysApp() {
    val context=androidx.compose.ui.platform.LocalContext.current
    val deck= remember { mutableStateListOf<DeckCard>() }
    var catalogue by remember { mutableStateOf<List<YgoCard>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var deckName by remember { mutableStateOf("Meu Deck") }
    var loading by remember { mutableStateOf(false) }
    var importLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var section by remember { mutableStateOf(DeckSection.MAIN) }
    var limit by remember { mutableIntStateOf(100) }
    val scope= rememberCoroutineScope()
    val total=deck.sumOf { it.card.points ?: 0 }
    val filtered=remember(query,catalogue) {
        if(query.isBlank()) emptyList() else catalogue.asSequence()
            .filter { it.name.contains(query.trim(),true) || it.id.toString()==query.trim() }
            .take(100).toList()
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
                val missing=allIds.size-imported.size
                deck.clear()
                deck.addAll(imported)
                tab=1
                importText=""
                message=if(missing>0) "Deck importado. $missing código(s) não foram encontrados no catálogo." else "Deck importado com sucesso: ${imported.size} cartas."
            } catch(e:Exception) { message=e.message ?: "Não foi possível importar o deck." }
            finally { importLoading=false }
        }
    }

    LaunchedEffect(Unit) { loadCatalogue() }

    MaterialTheme(colorScheme=darkColorScheme(primary=Blue,background=Navy,surface=Panel,onBackground=Color.White,onSurface=Color.White)) {
        Column(Modifier.fillMaxSize().background(Navy).padding(horizontal=16.dp)) {
            Spacer(Modifier.height(22.dp))
            Text("GENESYS",color=Blue,fontSize=12.sp,fontWeight=FontWeight.Bold,letterSpacing=3.sp)
            Text("Deck Calculator",color=Color.White,fontSize=28.sp,fontWeight=FontWeight.ExtraBold)
            Text("Catálogo completo • Importação EDOPro",color=Muted,fontSize=12.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).padding(5.dp)) {
                listOf("Cartas","Meu deck","Adicionar deck").forEachIndexed { index,label ->
                    Box(Modifier.weight(1f).background(if(tab==index) Blue.copy(alpha=.20f) else Color.Transparent,RoundedCornerShape(10.dp)).clickable { tab=index }.padding(vertical=11.dp,horizontal=3.dp),contentAlignment=Alignment.Center) {
                        Text(label,color=if(tab==index) Blue else Muted,fontWeight=FontWeight.Bold,fontSize=11.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if(message.isNotEmpty()) Text(message,color=if(message.contains("não") || message.contains("Falha") || message.contains("Erro")) Color(0xFFFFA0A0) else Color(0xFF79D9AD),fontSize=12.sp,modifier=Modifier.padding(vertical=4.dp))
            when(tab) {
                0 -> {
                    Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).padding(horizontal=12.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
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
                            Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(13.dp)).padding(9.dp),verticalAlignment=Alignment.CenterVertically) {
                                AsyncImage(model=card.image,contentDescription=card.name,modifier=Modifier.width(58.dp).height(82.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(card.name,fontWeight=FontWeight.Bold,fontSize=13.sp)
                                    Text(card.type,color=Muted,fontSize=10.sp,maxLines=2)
                                    Text(if(card.points==null) "Sem pontuação disponível" else "${card.points} pts Genesys",color=Blue,fontWeight=FontWeight.Bold,fontSize=12.sp)
                                }
                                IconButton(onClick={deck.add(DeckCard(card,section));message="Carta adicionada ao ${section.name}."}) { Icon(Icons.Default.Add,"Adicionar",tint=Blue) }
                            }
                        }
                    }
                }
                1 -> {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("PONTUAÇÃO DO DECK",color=Muted,fontSize=10.sp,fontWeight=FontWeight.Bold)
                            Text("$total / $limit",color=if(total>limit) Color(0xFFFF7474) else Blue,fontSize=30.sp,fontWeight=FontWeight.ExtraBold)
                        }
                        Text("${deck.size} cartas",color=Color.White,fontWeight=FontWeight.Bold)
                    }
                    OutlinedTextField(value=deckName,onValueChange={deckName=it},label={Text("Nome do deck")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=limit.toString(),onValueChange={it.toIntOrNull()?.takeIf { n -> n in 0..999 }?.let { limit=it }},label={Text("Limite de pontos")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        DeckSection.values().forEach { area ->
                            FilterChip(selected=section==area,onClick={section=area},label={Text(when(area){DeckSection.MAIN->"Main";DeckSection.EXTRA->"Extra";DeckSection.SIDE->"Side"})})
                        }
                    }
                    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(bottom=10.dp)) {
                        items(deck.toList().filter { it.section==section }.mapIndexed { index, entry -> index to entry },key={ it.first }) { indexedEntry ->
                            val entry=indexedEntry.second
                            Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(12.dp)).padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                AsyncImage(model=entry.card.image,contentDescription=entry.card.name,modifier=Modifier.width(44.dp).height(62.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.card.name,fontWeight=FontWeight.Bold,fontSize=12.sp)
                                    Text((entry.card.points?.toString() ?: "—")+" pts • "+entry.section.name,color=Blue,fontSize=11.sp)
                                }
                                IconButton(onClick={deck.remove(entry)}) { Icon(Icons.Default.Remove,null,tint=Muted) }
                            }
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={tab=2},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=Blue)) { Text("ADICIONAR DECK",color=Navy,fontWeight=FontWeight.Bold,fontSize=11.sp) }
                        OutlinedButton(onClick={deck.clear()},modifier=Modifier.weight(1f)) { Text("LIMPAR",fontSize=11.sp) }
                    }
                }
                else -> {
                    Text("Importar deck do EDOPro",color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Bold)
                    Text("No EDOPro, use a opção de copiar/compartilhar o deck. Cole aqui o código ydke:// ou o conteúdo de um arquivo .ydk.",color=Muted,fontSize=12.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value=deckName,onValueChange={deckName=it},label={Text("Nome do deck")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=importText,onValueChange={importText=it},label={Text("Código YDKE ou texto .ydk")},modifier=Modifier.fillMaxWidth().weight(1f),minLines=8,maxLines=16)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick={importDeck()},enabled=!importLoading && catalogue.isNotEmpty(),modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Blue)) {
                        Text(if(importLoading) "IMPORTANDO..." else "IMPORTAR DECK",color=Navy,fontWeight=FontWeight.Bold)
                    }
                    TextButton(onClick={tab=1},modifier=Modifier.align(Alignment.CenterHorizontally)) { Text("Voltar ao deck",color=Muted) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Pontuação oficial Konami • sincronização ao abrir o app",color=Muted,fontSize=9.sp,modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=10.dp))
        }
    }
}
