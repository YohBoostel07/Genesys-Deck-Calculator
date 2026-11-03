package com.yohboostel.genesysdeckcalculator

import android.os.Bundle
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
import java.net.URLEncoder

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF111F32)
private val Blue = Color(0xFF38A5FF)
private val Muted = Color(0xFF9BAEC5)

data class YgoCard(val id:Int,val name:String,val type:String,val image:String,val points:Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GenesysApp() }
    }
}

@Composable
fun GenesysApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val deck = remember { mutableStateListOf<YgoCard>() }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<YgoCard>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var deckName by remember { mutableStateOf("Meu Deck") }
    val scope = rememberCoroutineScope()
    val total = deck.sumOf { it.points }

    fun search() {
        if (query.isBlank()) return
        loading = true
        error = ""
        scope.launch {
            try {
                results = withContext(Dispatchers.IO) {
                    val encoded = URLEncoder.encode(query.trim(), "UTF-8")
                    val url = "https://db.ygoprodeck.com/api/v7/cardinfo.php?fname=$encoded&format=genesys&language=pt&num=30&offset=0"
                    val request = Request.Builder().url(url).build()
                    OkHttpClient().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("Erro ao consultar banco de cartas: " + response.code)
                        val root = JSONObject(response.body?.string() ?: "{}")
                        val array = root.optJSONArray("data") ?: return@withContext emptyList()
                        buildList {
                            for (i in 0 until array.length()) {
                                val obj = array.getJSONObject(i)
                                val type = obj.optString("type")
                                if (type.contains("Link", true) || type.contains("Pendulum", true)) continue
                                val images = obj.optJSONArray("card_images")
                                val image = if (images != null && images.length() > 0) images.getJSONObject(0).optString("image_url_small") else ""
                                add(YgoCard(obj.optInt("id"),obj.optString("name"),type,image,obj.optString("genesys_points").toIntOrNull() ?: 0))
                            }
                        }
                    }
                }
                if (results.isEmpty()) error = "Nenhuma carta encontrada. Tente outro nome."
            } catch (e: Exception) { error = e.message ?: "Falha na pesquisa." }
            finally { loading = false }
        }
    }

    MaterialTheme(colorScheme=darkColorScheme(primary=Blue,background=Navy,surface=Panel,onBackground=Color.White,onSurface=Color.White)) {
        Column(Modifier.fillMaxSize().background(Navy).padding(horizontal=18.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("GENESYS",color=Blue,fontSize=13.sp,fontWeight=FontWeight.Bold,letterSpacing=3.sp)
            Text("Deck Calculator",color=Color.White,fontSize=29.sp,fontWeight=FontWeight.ExtraBold)
            Text("Monte. Calcule. Vença.",color=Muted,fontSize=13.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).padding(5.dp)) {
                listOf("Cartas","Meu deck").forEachIndexed { index,label ->
                    Box(Modifier.weight(1f).background(if(tab==index) Blue.copy(alpha=.20f) else Color.Transparent,RoundedCornerShape(10.dp)).clickable { tab=index }.padding(12.dp),contentAlignment=Alignment.Center) {
                        Text(label,color=if(tab==index) Blue else Muted,fontWeight=FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            if(tab==0) {
                Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(14.dp)).padding(horizontal=12.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Search,null,tint=Muted)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(query,{query=it},Modifier.weight(1f).padding(vertical=12.dp),singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(color=Color.White,fontSize=15.sp),decorationBox={ inner -> if(query.isEmpty()) Text("Pesquisar carta...",color=Muted); inner() })
                    TextButton(onClick={search()}) { Text("BUSCAR") }
                }
                Spacer(Modifier.height(8.dp))
                if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Blue)
                if(error.isNotEmpty()) Text(error,color=Color(0xFFFF9999),fontSize=12.sp,modifier=Modifier.padding(vertical=8.dp))
                LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp),contentPadding=PaddingValues(bottom=20.dp)) {
                    items(results,key={it.id}) { card ->
                        Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(13.dp)).padding(9.dp),verticalAlignment=Alignment.CenterVertically) {
                            AsyncImage(model=card.image,contentDescription=card.name,modifier=Modifier.width(62.dp).height(88.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(card.name,fontWeight=FontWeight.Bold,fontSize=14.sp)
                                Text(card.type,color=Muted,fontSize=11.sp)
                                Text(card.points.toString()+" pts Genesys",color=Blue,fontWeight=FontWeight.Bold,fontSize=13.sp)
                            }
                            IconButton(onClick={if(deck.count { it.id==card.id }<3) deck.add(card) else error="Limite de 3 cópias por carta."}) { Icon(Icons.Default.Add,"Adicionar",tint=Blue) }
                        }
                    }
                }
            } else {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PONTUAÇÃO TOTAL",color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold)
                        Text(total.toString()+" / 100",color=if(total>100) Color(0xFFFF7474) else Blue,fontSize=32.sp,fontWeight=FontWeight.ExtraBold)
                    }
                    Text(deck.size.toString()+" cartas",color=Color.White,fontWeight=FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value=deckName,onValueChange={deckName=it},label={Text("Nome do deck")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                if(deck.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { Text("Seu deck está vazio. Pesquise cartas e toque em +.",color=Muted) }
                } else {
                    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=10.dp)) {
                        items(deck.toList().distinctBy { it.id },key={it.id}) { card ->
                            val count=deck.count { it.id==card.id }
                            Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(12.dp)).padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                AsyncImage(model=card.image,contentDescription=card.name,modifier=Modifier.width(46.dp).height(65.dp))
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(card.name,fontWeight=FontWeight.Bold,fontSize=13.sp)
                                    Text(card.points.toString()+" pts • "+(card.points*count)+" total",color=Blue,fontSize=12.sp)
                                }
                                IconButton(onClick={deck.remove(card)}) { Icon(Icons.Default.Remove,null,tint=Muted) }
                                Text(count.toString(),fontWeight=FontWeight.Bold)
                                IconButton(onClick={if(count<3) deck.add(card)}) { Icon(Icons.Default.Add,null,tint=Blue) }
                            }
                        }
                    }
                    Button(onClick={
                        val text=deckName+"\n"+deck.joinToString("\n") { it.name+" — "+it.points+" pts" }+"\nTOTAL: "+total+"/100"
                        val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(deckName,text))
                    },modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Blue)) { Text("COPIAR LISTA DO DECK",color=Navy,fontWeight=FontWeight.Bold) }
                    TextButton(onClick={deck.clear()},modifier=Modifier.align(Alignment.CenterHorizontally)) { Text("Limpar deck",color=Color(0xFFFF9999)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Cartas e pontuação: YGOPRODeck API v7",color=Muted,fontSize=10.sp,modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=12.dp))
        }
    }
}
