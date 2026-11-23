package com.mamanagmachin.app

import android.app.DatePickerDialog
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
import java.util.Calendar
import java.util.Locale

private class AppDb(context: android.content.Context) : SQLiteOpenHelper(context, "mamanagmachin.db", null, 3) {
 override fun onCreate(db:SQLiteDatabase) {
  db.execSQL("CREATE TABLE items(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,unit TEXT NOT NULL,qty REAL NOT NULL,min_qty REAL NOT NULL,note TEXT)")
  db.execSQL("CREATE TABLE movements(id INTEGER PRIMARY KEY AUTOINCREMENT,item_id INTEGER NOT NULL,type TEXT NOT NULL,qty REAL NOT NULL,vehicle TEXT,note TEXT,created TEXT NOT NULL)")
  db.execSQL("CREATE TABLE machines(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,code TEXT,hours REAL NOT NULL,driver TEXT,fuel_avg REAL NOT NULL,note TEXT)")
  db.execSQL("CREATE TABLE purchases(id INTEGER PRIMARY KEY AUTOINCREMENT,supplier TEXT,invoice TEXT,note TEXT,created TEXT NOT NULL)")
  db.execSQL("CREATE TABLE maintenance(id INTEGER PRIMARY KEY AUTOINCREMENT,vehicle TEXT NOT NULL,title TEXT NOT NULL,due_hours REAL NOT NULL,note TEXT)")
  createExtra(db)
  createLogs(db)
 }
 private fun createLogs(db:SQLiteDatabase) {
  db.execSQL("CREATE TABLE IF NOT EXISTS audit_log(id INTEGER PRIMARY KEY AUTOINCREMENT,action TEXT NOT NULL,details TEXT NOT NULL,created TEXT NOT NULL)")
  db.execSQL("CREATE TABLE IF NOT EXISTS maintenance_records(id INTEGER PRIMARY KEY AUTOINCREMENT,machine_id INTEGER NOT NULL,title TEXT NOT NULL,details TEXT,note TEXT,maintenance_date TEXT NOT NULL,hours REAL NOT NULL DEFAULT 0,cost REAL NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'منفذة',created TEXT NOT NULL)")
 }
 private fun log(d:SQLiteDatabase,action:String,details:String,date:String) { d.insert("audit_log",null,ContentValues().apply { put("action",action);put("details",details);put("created",date) }) }
 private fun createExtra(db:SQLiteDatabase) {
  db.execSQL("CREATE TABLE IF NOT EXISTS machine_stock(id INTEGER PRIMARY KEY AUTOINCREMENT,item_id INTEGER NOT NULL,machine_id INTEGER NOT NULL,qty REAL NOT NULL DEFAULT 0,UNIQUE(item_id,machine_id))")
  db.execSQL("CREATE TABLE IF NOT EXISTS fuel_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,machine_id INTEGER NOT NULL,hours_delta REAL NOT NULL,fuel_used REAL NOT NULL,created TEXT NOT NULL)")
 }
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) { if(oldVersion<2) createExtra(db);if(oldVersion<3) createLogs(db) }
 fun addItem(name:String,unit:String,qty:Double,min:Double,note:String,warehouse:Boolean,machines:List<Long>,date:String) {
  val d=writableDatabase;d.beginTransaction()
  try { val id=d.insertOrThrow("items",null,ContentValues().apply { put("name",name);put("unit",unit);put("qty",if(warehouse)qty else 0.0);put("min_qty",min);put("note",note) })
   machines.forEach { mid->d.execSQL("INSERT INTO machine_stock(item_id,machine_id,qty) VALUES(?,?,?) ON CONFLICT(item_id,machine_id) DO UPDATE SET qty=qty+excluded.qty",arrayOf(id,mid,qty)) };log(d,"إضافة مادة","$name • $qty $unit",date);d.setTransactionSuccessful()
  } finally { d.endTransaction() }
 }
 fun addMachine(name:String,code:String,hours:Double,driver:String,date:String) { val d=writableDatabase;d.insert("machines",null,ContentValues().apply { put("name",name);put("code",code);put("hours",hours);put("driver",driver);put("fuel_avg",0.0) });log(d,"إضافة آلية","$name • $code",date) }
 private fun scalar(d:SQLiteDatabase)=d.rawQuery("SELECT changes()",null).use { if(it.moveToFirst())it.getLong(0) else 0L }
 fun move(itemId:Long,type:String,qty:Double,machineId:Long,note:String,date:String) {
  require(qty>0);val d=writableDatabase;d.beginTransaction()
  try {
   when(type) {
    "إدخال"->d.execSQL("UPDATE items SET qty=qty+? WHERE id=?",arrayOf(qty,itemId))
    "صرف"->{d.execSQL("UPDATE items SET qty=qty-? WHERE id=? AND qty>=?",arrayOf(qty,itemId,qty));check(scalar(d)>0){"رصيد المستودع غير كافٍ"}}
    "تحويل إلى آلية"->{require(machineId>0){"اختر الآلية"};d.execSQL("UPDATE items SET qty=qty-? WHERE id=? AND qty>=?",arrayOf(qty,itemId,qty));check(scalar(d)>0){"رصيد المستودع غير كافٍ"};d.execSQL("INSERT INTO machine_stock(item_id,machine_id,qty) VALUES(?,?,?) ON CONFLICT(item_id,machine_id) DO UPDATE SET qty=qty+excluded.qty",arrayOf(itemId,machineId,qty))}
    "إرجاع"->{require(machineId>0){"اختر الآلية"};d.execSQL("UPDATE machine_stock SET qty=qty-? WHERE item_id=? AND machine_id=? AND qty>=?",arrayOf(qty,itemId,machineId,qty));check(scalar(d)>0){"رصيد الآلية غير كافٍ"};d.execSQL("UPDATE items SET qty=qty+? WHERE id=?",arrayOf(qty,itemId))}
   }
   val vehicle=if(machineId>0) d.rawQuery("SELECT name FROM machines WHERE id=?",arrayOf(machineId.toString())).use { if(it.moveToFirst())it.getString(0) else "" } else ""
   d.insert("movements",null,ContentValues().apply { put("item_id",itemId);put("type",type);put("qty",qty);put("vehicle",vehicle);put("note",note);put("created","$date ${SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date())}") });log(d,"حركة مخزون","$type • مادة $itemId • كمية $qty • $vehicle • $note",date);d.setTransactionSuccessful()
  } finally { d.endTransaction() }
 }
 fun recordWork(mid:Long,hours:Double,fuel:Double,workDate:String) {
  require(hours>0&&fuel>=0){"أدخل ساعات وكمية وقود صحيحتين"}
  require(workDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))){"أدخل التاريخ بصيغة سنة-شهر-يوم"}
  val d=writableDatabase;d.beginTransaction()
  try {
   if(fuel>0) {
    val candidates=mutableListOf<Long>()
    d.rawQuery("SELECT i.id FROM items i JOIN machine_stock s ON s.item_id=i.id WHERE s.machine_id=? AND s.qty>=? AND (i.name LIKE '%مازوت%' OR i.name LIKE '%ديزل%' OR i.name LIKE '%وقود%') ORDER BY s.qty DESC",arrayOf(mid.toString(),fuel.toString())).use { c->while(c.moveToNext())candidates.add(c.getLong(0)) }
    val fid=candidates.firstOrNull()
    if(fid==null) {
     val exists=d.rawQuery("SELECT COUNT(*) FROM items WHERE name LIKE '%مازوت%' OR name LIKE '%ديزل%' OR name LIKE '%وقود%'",null).use { it.moveToFirst() && it.getInt(0)>0 }
     if(!exists) throw IllegalArgumentException("لم تُسجّل مادة وقود بعد. أضف مادة باسم مازوت أو ديزل، ثم حوّل رصيدها إلى الآلية.")
     throw IllegalArgumentException("رصيد المازوت/الديزل في هذه الآلية غير كافٍ. حوّل الكمية من المستودع إلى الآلية أولاً.")
    }
    d.execSQL("UPDATE machine_stock SET qty=qty-? WHERE item_id=? AND machine_id=? AND qty>=?",arrayOf(fuel,fid,mid,fuel))
   }
   d.insert("fuel_sessions",null,ContentValues().apply { put("machine_id",mid);put("hours_delta",hours);put("fuel_used",fuel);put("created","$workDate ${SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date())}") });log(d,"تسجيل تشغيل","آلية $mid • ساعات $hours • وقود $fuel لتر",workDate)
   d.execSQL("UPDATE machines SET hours=hours+? WHERE id=?",arrayOf(hours,mid));d.setTransactionSuccessful()
  } finally { d.endTransaction() }
 }
 fun addMaintenance(mid:Long,title:String,details:String,note:String,date:String,hours:Double,cost:Double,status:String) { val d=writableDatabase;d.beginTransaction();try { d.insert("maintenance_records",null,ContentValues().apply { put("machine_id",mid);put("title",title);put("details",details);put("note",note);put("maintenance_date",date);put("hours",hours);put("cost",cost);put("status",status);put("created",date) });log(d,"سجل صيانة","آلية $mid • $title • $status • $details",date);d.setTransactionSuccessful() } finally { d.endTransaction() } }
 fun maintenanceRows():List<List<String>> { val x=mutableListOf<List<String>>();readableDatabase.rawQuery("SELECT r.id,m.name,r.title,r.maintenance_date,r.hours,r.cost,r.status,r.details FROM maintenance_records r LEFT JOIN machines m ON m.id=r.machine_id ORDER BY r.id DESC",null).use { c->while(c.moveToNext())x.add((0 until c.columnCount).map{c.getString(it)?: ""}) };return x }
 fun auditRows():List<List<String>> { val x=mutableListOf<List<String>>();readableDatabase.rawQuery("SELECT action,details,created FROM audit_log ORDER BY id DESC",null).use { c->while(c.moveToNext())x.add((0 until c.columnCount).map{c.getString(it)?: ""}) };return x }
 fun machineFuel(mid:Long)=readableDatabase.rawQuery("SELECT COALESCE(SUM(s.qty),0) FROM machine_stock s JOIN items i ON i.id=s.item_id WHERE s.machine_id=? AND (i.name LIKE '%مازوت%' OR i.name LIKE '%ديزل%' OR i.name LIKE '%وقود%')",arrayOf(mid.toString())).use { if(it.moveToFirst())it.getDouble(0) else 0.0 }
 fun machineAverage(mid:Long)=readableDatabase.rawQuery("SELECT CASE WHEN SUM(hours_delta)>0 THEN SUM(fuel_used)/SUM(hours_delta) ELSE 0 END FROM fuel_sessions WHERE machine_id=?",arrayOf(mid.toString())).use { if(it.moveToFirst())it.getDouble(0) else 0.0 }
 fun machines():List<Pair<Long,String>> { val x=mutableListOf<Pair<Long,String>>();readableDatabase.rawQuery("SELECT id,name FROM machines ORDER BY name",null).use { c->while(c.moveToNext())x.add(c.getLong(0) to c.getString(1)) };return x }
 fun itemOptions():List<Pair<Long,String>> { val x=mutableListOf<Pair<Long,String>>();readableDatabase.rawQuery("SELECT id,name,unit,qty FROM items ORDER BY name",null).use { c->while(c.moveToNext())x.add(c.getLong(0) to "${c.getString(1)} (${c.getString(3)} ${c.getString(2)})") };return x }
 fun rows(table:String):List<List<String>> {
  val cols=when(table){"items"->arrayOf("id","name","unit","qty","min_qty","note");"machines"->arrayOf("id","name","code","hours","driver","fuel_avg");"movements"->arrayOf("id","item_id","type","qty","vehicle","created");else->arrayOf("*")}
  val x=mutableListOf<List<String>>();readableDatabase.rawQuery("SELECT ${cols.joinToString(",")} FROM $table ORDER BY id DESC",null).use { c->while(c.moveToNext())x.add((0 until c.columnCount).map{c.getString(it)?: ""}) };return x
 }
 fun count(table:String)=readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use{if(it.moveToFirst())it.getInt(0)else 0}
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db=AppDb(this)
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF174A72),secondary=androidx.compose.ui.graphics.Color(0xFF287D8E))) { MainScreen(db) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MainScreen(db:AppDb) {
    var page by remember { mutableStateOf("الرئيسية") }
    var refresh by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val pages=listOf("الرئيسية","المستودع","الآليات","الحركات","الصيانة","التقارير")
    Scaffold(
        topBar={ CenterAlignedTopAppBar(title={ Text("إدارة الآليات",fontWeight=FontWeight.Bold) }, actions={ IconButton(onClick={refresh++}) { Icon(Icons.Default.Refresh,"تحديث") } }) },
        bottomBar={ NavigationBar { pages.forEach { p -> NavigationBarItem(selected=page==p,onClick={page=p},icon={Icon(when(p){"الرئيسية"->Icons.Default.Home;"المستودع"->Icons.Default.Inventory2;"الآليات"->Icons.Default.Construction;"الحركات"->Icons.Default.SwapHoriz;"الصيانة"->Icons.Default.Build;else->Icons.Default.Assessment},null)},label={Text(p)}) } } },
        floatingActionButton={ if(page=="المستودع" || page=="الآليات" || page=="الحركات" || page=="الصيانة") FloatingActionButton(onClick={showAdd=true}) { Icon(Icons.Default.Add,"إضافة") } }
    ) { pad ->
        key(refresh,page) {
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
                when(page) {
                    "الرئيسية" -> Dashboard(db)
                    "المستودع" -> ItemList(db)
                    "الآليات" -> MachineList(db)
                    "الحركات" -> MovementList(db)
                    "الصيانة" -> MaintenanceScreen(db,{refresh++;showAdd=false})
                    else -> ReportsScreen(db)
                }
            }
        }
        if(showAdd) {
            when(page) {
                "الصيانة" -> MaintenanceAddDialog(db,{refresh++;showAdd=false},{showAdd=false})
                "المستودع" -> AddItemDialog(db,{n,u,q,m,note,warehouse,machineIds -> db.addItem(n,u,q,m,note,warehouse,machineIds,today());refresh++;showAdd=false},{showAdd=false})
                "الآليات" -> AddMachineDialog({n,c,h,d -> db.addMachine(n,c,h,d,today());refresh++;showAdd=false},{showAdd=false})
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
 var workFor by remember { mutableStateOf<Pair<Long,String>?>(null) }
 Text("الآليات",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
 val rows=db.rows("machines")
 if(rows.isEmpty()) EmptyState("لا توجد آليات مسجلة","اضغط + لإضافة آلية")
 LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) { items(rows) { r->
  val id=r[0].toLongOrNull()?:-1L
  Card { Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
   Text(r[1],fontWeight=FontWeight.Bold);Text("الرمز: ${r[2]} • عداد الساعات: ${r[3]}");Text("السائق: ${r[4]}")
   Text("متوسط الاستهلاك المحسوب: %.2f لتر/ساعة".format(db.machineAverage(id)))
   Text("الوقود المتبقي في الآلية: %.2f لتر".format(db.machineFuel(id)))
   Button(onClick={workFor=id to r[1]},modifier=Modifier.fillMaxWidth()){Text("تسجيل ساعات العمل واستهلاك الوقود")}
  } }
 } }
 workFor?.let { v->AddWorkDialog(v.second,{h,f,date->db.recordWork(v.first,h,f,date);workFor=null},{workFor=null}) }
}
@Composable private fun MovementList(db:AppDb) {
    Text("سجل حركات المستودع",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
    val rows=db.rows("movements")
    if(rows.isEmpty()) EmptyState("لا توجد حركات","سجّل إدخالاً أو صرفاً من زر +")
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) { items(rows) { r -> Card { Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Column { Text("${r[2]} • مادة رقم ${r[1]}",fontWeight=FontWeight.Bold);Text("${r[5]} • ${r[4]}") };Text(r[3])
    } } } }
}
@Composable private fun MaintenanceScreen(db:AppDb,refresh:()->Unit,add:()->Unit) {
 Column {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text("سجل الصيانة",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Button(onClick=add){Text("إضافة صيانة")} }
  val rows=db.maintenanceRows()
  if(rows.isEmpty()) EmptyState("لا توجد سجلات صيانة","اضغط إضافة صيانة لتسجيل أعمال الصيانة")
  LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) { items(rows) { r->Card { Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) { Text(r[2],fontWeight=FontWeight.Bold);Text("الآلية: ${r[1]} • التاريخ: ${r[3]}");Text("عداد الساعات: ${r[4]} • التكلفة: ${r[5]}");Text("الحالة: ${r[6]}");if(r[7].isNotBlank())Text(r[7]) } } } }
 }
}
@Composable private fun ReportsScreen(db:AppDb) {
 var tab by remember{mutableStateOf("كل السجلات")}
 Column {
  Text("التقارير والسجلات",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
  Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("كل السجلات","الحركات","الصيانة").forEach { t->FilterChip(selected=tab==t,onClick={tab=t},label={Text(t)}) } }
  val rows=if(tab=="الصيانة") db.maintenanceRows().map{listOf("صيانة",it[1]+" • "+it[2],it[3]+" • "+it[6])} else if(tab=="الحركات") db.rows("movements").map{listOf(it[2],"مادة ${it[1]} • كمية ${it[3]} • ${it[4]}",it[5])} else db.auditRows()
  if(rows.isEmpty()) EmptyState("لا توجد سجلات","ستظهر هنا كل الإضافات والحركات والتشغيل والصيانة")
  LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp)) { items(rows) { r->Card { Column(Modifier.fillMaxWidth().padding(10.dp)) { Text(r[0],fontWeight=FontWeight.Bold);Text(r[1]);Text(r[2],style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } } } }
 }
}
@Composable private fun MaintenanceAddDialog(db:AppDb,save:()->Unit,close:()->Unit) {
 val machines=db.machines();var machine by remember{mutableStateOf(machines.firstOrNull()?.first?:-1L)}
 var title by remember{mutableStateOf("")};var details by remember{mutableStateOf("")};var note by remember{mutableStateOf("")};var date by remember{mutableStateOf(today())};var hours by remember{mutableStateOf("0")};var cost by remember{mutableStateOf("0")};var status by remember{mutableStateOf("منفذة")}
 AlertDialog(onDismissRequest=close,title={Text("إضافة سجل صيانة")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
  if(machines.isEmpty()) Text("أضف آلية أولاً") else { Text("الآلية");machines.forEach{(id,n)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(machine==id,{machine=id});Text(n)}} }
  Field("نوع الصيانة",title,{title=it});Field("تفاصيل الأعمال وقطع الغيار",details,{details=it});DateField("تاريخ الصيانة",date,{date=it});Field("عداد الساعات وقت الصيانة",hours,{hours=it},true);Field("التكلفة (اختياري)",cost,{cost=it},true)
  Text("الحالة");Row { listOf("منفذة","مجدولة","مؤجلة").forEach{FilterChip(selected=status==it,onClick={status=it},label={Text(it)})} }
  Field("ملاحظات",note,{note=it})
 }},confirmButton={TextButton(enabled=machines.isNotEmpty()&&title.isNotBlank(),onClick={db.addMaintenance(machine,title,details,note,date,hours.toDoubleOrNull()?:0.0,cost.toDoubleOrNull()?:0.0,status);save()}){Text("حفظ السجل")}},dismissButton={TextButton(onClick=close){Text("إلغاء")}})
}
private fun today():String=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
@Composable private fun DateField(label:String,value:String,onChange:(String)->Unit) {
 val context=androidx.compose.ui.platform.LocalContext.current
 OutlinedButton(onClick={
  val p=value.split("-");val y=p.getOrNull(0)?.toIntOrNull()?:Calendar.getInstance().get(Calendar.YEAR);val m=(p.getOrNull(1)?.toIntOrNull()?:1)-1;val d=p.getOrNull(2)?.toIntOrNull()?:1
  DatePickerDialog(context,{_,year,month,day->onChange(String.format(Locale.US,"%04d-%02d-%02d",year,month+1,day))},y,m,d).show()
 },modifier=Modifier.fillMaxWidth()) { Text("$label: $value") }
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
@Composable private fun AddItemDialog(db:AppDb,save:(String,String,Double,Double,String,Boolean,List<Long>)->Unit,close:()->Unit) {
 var name by remember{mutableStateOf("")};var unit by remember{mutableStateOf("قطعة")};var customUnit by remember{mutableStateOf("")};var qty by remember{mutableStateOf("0")};var min by remember{mutableStateOf("0")};var note by remember{mutableStateOf("")};var date by remember{mutableStateOf(today())}
 var warehouse by remember{mutableStateOf(true)};val machines=db.machines();val selected=remember{mutableStateListOf<Long>()}
 AlertDialog(onDismissRequest=close,title={Text("إضافة مادة")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
  Field("اسم المادة",name,{name=it});Text("وحدة القياس");var expanded by remember{mutableStateOf(false)};Box { OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(unit)};DropdownMenu(expanded,{expanded=false}) { listOf("لتر","كيلو","قطعة","أخرى").forEach { opt->DropdownMenuItem(text={Text(opt)},onClick={unit=opt;expanded=false}) } } };if(unit=="أخرى")Field("اكتب وحدة القياس",customUnit,{customUnit=it});DateField("تاريخ الإضافة",date,{date=it});Field("الكمية المضافة",qty,{qty=it},true);Field("حد التنبيه",min,{min=it},true)
  Text("أين تريد إضافة المادة؟",fontWeight=FontWeight.Bold)
  Row(verticalAlignment=Alignment.CenterVertically){Checkbox(warehouse,{warehouse=it});Text("المستودع")}
  machines.forEach{(id,label)->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(selected.contains(id),{if(it)selected.add(id)else selected.remove(id)});Text("الآليات: $label")}}
  Field("ملاحظات",note,{note=it})
 }},confirmButton={TextButton(onClick={if(name.isNotBlank()&&(warehouse||selected.isNotEmpty()))save(name,if(unit=="أخرى")customUnit else unit,qty.toDoubleOrNull()?:0.0,min.toDoubleOrNull()?:0.0,note,warehouse,selected.toList())}){Text("حفظ")}},dismissButton={TextButton(onClick=close){Text("إلغاء")}})
}
@Composable private fun AddMachineDialog(save:(String,String,Double,String)->Unit,close:()->Unit) {
 var name by remember{mutableStateOf("")};var code by remember{mutableStateOf("")};var hours by remember{mutableStateOf("0")};var driver by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("إضافة آلية")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
  Field("اسم الآلية",name,{name=it});Field("الرقم/الرمز",code,{code=it});Field("قراءة عداد الساعات الحالية",hours,{hours=it},true);Field("السائق",driver,{driver=it})
  Text("سيحسب البرنامج متوسط استهلاك الوقود تلقائياً من سجلات التشغيل.",style=MaterialTheme.typography.bodySmall)
 }},confirmButton={TextButton(onClick={if(name.isNotBlank())save(name,code,hours.toDoubleOrNull()?:0.0,driver)}){Text("حفظ")}},dismissButton={TextButton(onClick=close){Text("إلغاء")}})
}
@Composable private fun AddWorkDialog(machine:String,save:(Double,Double,String)->Unit,close:()->Unit) {
 var hours by remember{mutableStateOf("")};var fuel by remember{mutableStateOf("")}
 var workDate by remember{mutableStateOf(today())}
 var error by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("تسجيل تشغيل: $machine")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  DateField("تاريخ التشغيل",workDate,{workDate=it})
  Field("ساعات العمل خلال هذه الفترة",hours,{hours=it},true)
  Field("الوقود المستهلك فعلياً (لتر)",fuel,{fuel=it},true)
  Text("سيُحدّث عداد الساعات، ويُخصم الوقود من رصيد الآلية، ويُحسب المتوسط التراكمي لتر/ساعة.",style=MaterialTheme.typography.bodySmall)
  if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
 }},confirmButton={TextButton(onClick={
  val h=hours.toDoubleOrNull();val f=fuel.toDoubleOrNull()
  if(h==null||f==null||h<=0||f<0) error="أدخل ساعات العمل والوقود بشكل صحيح."
  else try { save(h,f,workDate.trim()) } catch(e:Exception) { error=e.message?: "تعذر حفظ سجل التشغيل." }
 }){Text("حفظ")}},dismissButton={TextButton(onClick=close){Text("إلغاء")}})
}
@Composable private fun AddMovementDialog(db:AppDb,done:()->Unit,close:()->Unit) {
 val opts=db.itemOptions();val machines=db.machines();var selected by remember{mutableStateOf(opts.firstOrNull()?.first?:-1L)}
 var type by remember{mutableStateOf("إدخال")};var qty by remember{mutableStateOf("1")};var machine by remember{mutableStateOf(-1L)};var note by remember{mutableStateOf("")};var date by remember{mutableStateOf(today())};var error by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=close,title={Text("حركة مخزون")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
  if(opts.isEmpty())Text("أضف مادة أولاً.") else {
   Text("المادة");opts.forEach{(id,label)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(selected==id,{selected=id});Text(label)}}
   Text("نوع الحركة");listOf("إدخال","صرف","تحويل إلى آلية","إرجاع").forEach{t->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(type==t,{type=t});Text(t)}}
   if(type=="تحويل إلى آلية"||type=="إرجاع"){Text("اختر الآلية");machines.forEach{(id,label)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(machine==id,{machine=id});Text(label)}}}
   DateField("تاريخ الحركة",date,{date=it});Field("الكمية",qty,{qty=it},true);Field("ملاحظات",note,{note=it});if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
  }
 }},confirmButton={TextButton(enabled=opts.isNotEmpty(),onClick={try{val amount=qty.toDoubleOrNull()?:0.0;require(amount>0){"أدخل كمية صحيحة"};db.move(selected,type,amount,machine,note,date);done()}catch(e:Exception){error=e.message?:"تعذر حفظ الحركة"}}){Text("حفظ")}},dismissButton={TextButton(onClick=close){Text("إلغاء")}})
}
