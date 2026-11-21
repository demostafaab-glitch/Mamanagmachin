package com.mamanagmachin.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class AppDb(context: android.content.Context) : SQLiteOpenHelper(context, "mamanagmachin.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE items(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,unit TEXT NOT NULL,qty REAL NOT NULL,min_qty REAL NOT NULL,note TEXT)")
        db.execSQL("CREATE TABLE movements(id INTEGER PRIMARY KEY AUTOINCREMENT,item_id INTEGER NOT NULL,type TEXT NOT NULL,qty REAL NOT NULL,vehicle TEXT,note TEXT,created TEXT NOT NULL)")
        db.execSQL("CREATE TABLE machines(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,code TEXT,hours REAL NOT NULL,driver TEXT,fuel_avg REAL NOT NULL,note TEXT)")
        db.execSQL("CREATE TABLE purchases(id INTEGER PRIMARY KEY AUTOINCREMENT,supplier TEXT,invoice TEXT,note TEXT,created TEXT NOT NULL)")
        db.execSQL("CREATE TABLE maintenance(id INTEGER PRIMARY KEY AUTOINCREMENT,vehicle TEXT NOT NULL,title TEXT NOT NULL,due_hours REAL NOT NULL,note TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun addItem(name:String,unit:String,qty:Double,min:Double,note:String) {
        writableDatabase.insert("items",null,ContentValues().apply { put("name",name);put("unit",unit);put("qty",qty);put("min_qty",min);put("note",note) })
    }
    fun addMachine(name:String,code:String,hours:Double,driver:String,avg:Double) {
        writableDatabase.insert("machines",null,ContentValues().apply { put("name",name);put("code",code);put("hours",hours);put("driver",driver);put("fuel_avg",avg) })
    }
    fun move(itemId:Long,type:String,qty:Double,vehicle:String,note:String) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val sign=if(type=="إدخال" || type=="إرجاع") 1 else -1
            db.execSQL("UPDATE items SET qty=qty+? WHERE id=?", arrayOf(sign*qty,itemId))
            db.insert("movements",null,ContentValues().apply {
                put("item_id",itemId);put("type",type);put("qty",qty);put("vehicle",vehicle);put("note",note)
                put("created",SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(Date()))
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun rows(table:String):List<List<String>> {
        val cols=when(table) { "items"-> arrayOf("id","name","unit","qty","min_qty","note"); "machines"->arrayOf("id","name","code","hours","driver","fuel_avg"); "movements"->arrayOf("id","item_id","type","qty","vehicle","created"); else->arrayOf("*") }
        val out= mutableListOf<List<String>>()
        readableDatabase.rawQuery("SELECT ${cols.joinToString(",")} FROM $table ORDER BY id DESC",null).use { c ->
            while(c.moveToNext()) out.add((0 until c.columnCount).map { c.getString(it) ?: "" })
        }
        return out
    }
    fun count(table:String)=readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use { if(it.moveToFirst()) it.getInt(0) else 0 }
    fun itemOptions():List<Pair<Long,String>> {
        val out= mutableListOf<Pair<Long,String>>()
        readableDatabase.rawQuery("SELECT id,name,unit,qty FROM items ORDER BY name",null).use { c -> while(c.moveToNext()) out.add(c.getLong(0) to "${c.getString(1)} (${c.getString(3)} ${c.getString(2)})") }
        return out
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db=AppDb(this)
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF174A72),secondary=androidx.compose.ui.graphics.Color(0xFF287D8E))) { MainScreen(db) } }
    }
}

@Composable private fun MainScreen(db:AppDb) {
    var page by remember { mutableStateOf("الرئيسية") }
    var refresh by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val pages=listOf("الرئيسية","المستودع","الآليات","الحركات","الصيانة")
    Scaffold(
        topBar={ CenterAlignedTopAppBar(title={ Text("إدارة الآليات",fontWeight=FontWeight.Bold) }, actions={ IconButton(onClick={refresh++}) { Icon(Icons.Default.Refresh,"تحديث") } }) },
        bottomBar={ NavigationBar { pages.forEach { p -> NavigationBarItem(selected=page==p,onClick={page=p},icon={Icon(when(p){"الرئيسية"->Icons.Default.Home;"المستودع"->Icons.Default.Inventory2;"الآليات"->Icons.Default.Construction;"الحركات"->Icons.Default.SwapHoriz;else->Icons.Default.Build},null)},label={Text(p)}) } } },
        floatingActionButton={ if(page=="المستودع" || page=="الآليات" || page=="الحركات") FloatingActionButton(onClick={showAdd=true}) { Icon(Icons.Default.Add,"إضافة") } }
    ) { pad ->
        key(refresh,page) {
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                when(page) {
                    "الرئيسية" -> Dashboard(db)
                    "المستودع" -> ItemList(db)
                    "الآليات" -> MachineList(db)
                    "الحركات" -> MovementList(db)
                    else -> MaintenanceInfo()
                }
            }
        }
        if(showAdd) {
            when(page) {
                "المستودع" -> AddItemDialog({n,u,q,m,note -> db.addItem(n,u,q,m,note);refresh++;showAdd=false},{showAdd=false})
                "الآليات" -> AddMachineDialog({n,c,h,d,a -> db.addMachine(n,c,h,d,a);refresh++;showAdd=false},{showAdd=false})
                "الحركات" -> AddMovementDialog(db,{refresh++;showAdd=false},{showAdd=false})
            }
        }
    }
}

@Composable private fun Dashboard(db:AppDb) {
    Text("لوحة التحكم",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        StatCard("المواد",db.count("items").toString(),Modifier.weight(1f))
        StatCard("الآليات",db.count("machines").toString(),Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        StatCard("الحركات",db.count("movements").toString(),Modifier.weight(1f))
        StatCard("الفواتير",db.count("purchases").toString(),Modifier.weight(1f))
    }
    Spacer(Modifier.height(20.dp))
    Text("ملخص النظام",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
    Text("قاعدة البيانات محفوظة محلياً على الجهاز. لا يحتاج التطبيق إلى اتصال بالإنترنت.",Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text("ابدأ بإضافة المواد والآليات، ثم سجّل حركات الإدخال والصرف.",Modifier.padding(top=8.dp))
}
@Composable private fun StatCard(title:String,value:String,modifier:Modifier=Modifier) {
    Card(modifier) { Column(Modifier.padding(16.dp)) { Text(title,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold) } }
}
@Composable private fun ItemList(db:AppDb) {
    Text("المستودع والمواد",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    val rows=db.rows("items")
    if(rows.isEmpty()) EmptyState("لا توجد مواد بعد","اضغط + لإضافة أول مادة")
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items(rows) { r ->
            val low=r[3].toDoubleOrNull()?.let { it <= (r[4].toDoubleOrNull() ?: 0.0) } ?: false
            Card { Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(r[1],fontWeight=FontWeight.Bold);Text("الحد الأدنى: ${r[4]} ${r[2]} • ${r[5]}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                Column(horizontalAlignment=Alignment.End) { Text("${r[3]} ${r[2]}",fontWeight=FontWeight.Bold);if(low) Text("مخزون منخفض",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall) }
            } }
        }
    }
}
@Composable private fun MachineList(db:AppDb) {
    Text("الآليات",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    val rows=db.rows("machines")
    if(rows.isEmpty()) EmptyState("لا توجد آليات مسجلة","اضغط + لإضافة آلية")
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) { items(rows) { r -> Card { Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text(r[1],fontWeight=FontWeight.Bold);Text("الرمز: ${r[2]} • الساعات: ${r[3]}");Text("السائق: ${r[4]} • متوسط الوقود: ${r[5]}")
    } } } }
}
@Composable private fun MovementList(db:AppDb) {
    Text("سجل حركات المستودع",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    val rows=db.rows("movements")
    if(rows.isEmpty()) EmptyState("لا توجد حركات","سجّل إدخالاً أو صرفاً من زر +")
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) { items(rows) { r -> Card { Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Column { Text("${r[2]} • مادة رقم ${r[1]}",fontWeight=FontWeight.Bold);Text("${r[5]} • ${r[4]}") };Text(r[3])
    } } } }
}
@Composable private fun MaintenanceInfo() {
    Text("الصيانة والتذكيرات",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    EmptyState("سجل الصيانة قيد التجهيز","سيضاف تسجيل الصيانة الدورية والتنبيه حسب ساعات التشغيل في الإصدار التالي.")
}
@Composable private fun EmptyState(title:String,desc:String) {
    Column(Modifier.fillMaxWidth().padding(vertical=40.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Default.Inbox,null,tint=MaterialTheme.colorScheme.secondary,modifier=Modifier.size(42.dp))
        Text(title,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp))
        Text(desc,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun Field(label:String,value:String,onChange:(String)->Unit,number:Boolean=false) {
    OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(number) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable private fun AddItemDialog(save:(String,String,Double,Double,String)->Unit,close:()->Unit) {
    var name by remember { mutableStateOf("") };var unit by remember { mutableStateOf("قطعة") };var qty by remember { mutableStateOf("0") };var min by remember { mutableStateOf("0") };var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=close,title={Text("إضافة مادة")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Field("اسم المادة",name){name=it};Field("وحدة القياس",unit){unit=it};Field("الكمية الحالية",qty,{qty=it},true);Field("حد التنبيه",min,{min=it},true);Field("ملاحظات",note){note=it}
    }},confirmButton={TextButton(onClick={if(name.isNotBlank()) save(name,unit,qty.toDoubleOrNull()?:0.0,min.toDoubleOrNull()?:0.0,note)}){Text("حفظ")}},dismissButton={TextButton(onClick=close){Text("إلغاء")}})
}
@Composable private fun AddMachineDialog(save:(String,String,Double,String,Double)->Unit,close:()->Unit) {
    var name by remember { mutableStateOf("") };var code by remember { mutableStateOf("") };var hours by remember { mutableStateOf("0") };var driver by remember { mutableStateOf("") };var avg by remember { mutableStateOf("0") }
    AlertDialog(onDismissRequest=close,title={Text("إضافة آلية")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Field("اسم الآلية",name){name=it};Field("الرقم/الرمز",code){code=it};Field("عداد الساعات",hours,{hours=it},true);Field("السائق",driver){driver=it};Field("متوسط استهلاك الوقود",avg,{avg=it},true)
    }},confirmButton={TextButton(onClick={if(name.isNotBlank()) save(name,code,hours.toDoubleOrNull()?:0.0,driver,avg.toDoubleOrNull()?:0.0)}){Text("حفظ")}},dismissButton={TextButton(onClick=close){Text("إلغاء")}})
}
@Composable private fun AddMovementDialog(db:AppDb,done:()->Unit,close:()->Unit) {
    val opts=db.itemOptions();var selected by remember { mutableStateOf(opts.firstOrNull()?.first ?: -1L) };var type by remember { mutableStateOf("إدخال") };var qty by remember { mutableStateOf("1") };var vehicle by remember { mutableStateOf("") };var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=close,title={Text("تسجيل حركة")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(opts.isEmpty()) Text("أضف مادة أولاً قبل تسجيل الحركة.")
        else {
            Text("المادة");opts.forEach { (id,label)->Row(verticalAlignment=Alignment.CenterVertically) { RadioButton(selected==id,{selected=id});Text(label) } }
            Text("نوع الحركة");Row { listOf("إدخال","صرف","إرجاع").forEach { t->Row(verticalAlignment=Alignment.CenterVertically) { RadioButton(type==t,{type=t});Text(t) } } }
            Field("الكمية",qty,{qty=it},true);Field("الآلية (اختياري)",vehicle){vehicle=it};Field("ملاحظات",note){note=it}
        }
    }},confirmButton={TextButton(enabled=opts.isNotEmpty(),onClick={val amount=qty.toDoubleOrNull()?:0.0;if(amount>0){db.move(selected,type,amount,vehicle,note);done()}}){Text("حفظ")}},dismissButton={TextButton(onClick=close){Text("إلغاء")}})
}
