package com.vertil.miningbtc

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
 private val bg=Color.rgb(8,14,23); private val panel=Color.rgb(17,27,40); private val muted=Color.rgb(145,163,184); private val white=Color.rgb(239,245,251); private val orange=Color.rgb(247,147,26); private val executor=Executors.newSingleThreadExecutor()
 private lateinit var root:LinearLayout; private lateinit var benchmarkPanel:LinearLayout; private lateinit var stratumPanel:LinearLayout
 private lateinit var benchStatus:TextView; private lateinit var benchRate:TextView; private lateinit var benchAvg:TextView; private lateinit var benchHashes:TextView; private lateinit var benchThreads:TextView; private lateinit var benchPath:TextView; private lateinit var benchSelf:TextView; private lateinit var benchDetails:TextView
 private lateinit var hostInput:EditText; private lateinit var portInput:EditText; private lateinit var userInput:EditText; private lateinit var passInput:EditText; private lateinit var tlsInput:CheckBox
 private lateinit var status:TextView; private lateinit var rate:TextView; private lateinit var hashes:TextView; private lateinit var accepted:TextView; private lateinit var rejected:TextView; private lateinit var detail:TextView; private lateinit var startButton:Button; private lateinit var stopButton:Button
 private var miner:StratumMiner?=null; private var benchRunning=false
 private val header=ByteArray(80).also { val h="01000000"+"0000000000000000000000000000000000000000000000000000000000000000"+"3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a"+"29ab5f49"+"ffff001d"+"1dac2b7c"; for(i in 0 until 80) it[i]=h.substring(i*2,i*2+2).toInt(16).toByte() }
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=bg;window.navigationBarColor=bg
  root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(20),dp(18),dp(26));setBackgroundColor(bg)}
  root.addView(label("₿  POW LAB",23f,white,true));root.addView(label("VERTIL  /  SHA-256d PROOF OF WORK",10f,muted,true).padBottom(16))
  val selector=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
  selector.addView(action("BENCHMARK LOCAL"){showMode(true)},LinearLayout.LayoutParams(0,dp(48),1f))
  selector.addView(action("MINERÍA STRATUM"){showMode(false)},LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(8)})
  root.addView(selector); benchmarkPanel=buildBenchmark();stratumPanel=buildStratum();root.addView(benchmarkPanel);root.addView(stratumPanel);showMode(true)
  setContentView(ScrollView(this).apply{isFillViewport=true;addView(root)})
  runSelfTest()
 }
 private fun buildBenchmark():LinearLayout{
  val p=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  p.addView(label("BENCHMARK NATIVO SHA-256d",11f,muted,true).padTop(20))
  val c=card();benchStatus=label("Preparando autoprueba…",13f,white,true);c.addView(benchStatus)
  benchSelf=label("Autoprueba: pendiente",11f,muted,false).padTop(10);c.addView(benchSelf)
  benchPath=label("Motor: detectando…",11f,muted,false).padTop(8);c.addView(benchPath)
  benchRate=label("0 H/s",34f,white,true).padTop(16);c.addView(benchRate)
  benchAvg=label("Promedio de sesión: 0 H/s",11f,muted,false);c.addView(benchAvg)
  benchHashes=label("Hashes totales: 0",12f,white,true).padTop(10);c.addView(benchHashes)
  benchThreads=label("Hilos: —",11f,muted,false).padTop(5);c.addView(benchThreads)
  benchDetails=label("La autoprueba compara el bloque génesis con SHA2 ARM y el fallback portable cuando ARM SHA2 está disponible.",10f,muted,false).padTop(10);c.addView(benchDetails);p.addView(c)
  val buttons=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  buttons.addView(action("INICIAR NATIVO MULTIHILO"){runNative(0)})
  buttons.addView(action("NATIVO 1 HILO"){runNative(1)})
  buttons.addView(action("COMPARAR JAVA / NATIVO 1 HILO / MULTIHILO"){runComparison()})
  buttons.addView(action("DETENER BENCHMARK"){benchRunning=false})
  p.addView(buttons);return p
 }
 private fun buildStratum():LinearLayout{
  val p=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val prefs=getSharedPreferences("pool_config",MODE_PRIVATE)
  p.addView(label("STRATUM V1 · CONEXIÓN AL POOL",11f,muted,true).padTop(20))
  val c=card();hostInput=field("Pool hostname",prefs.getString("host","")?:"");portInput=field("Port",prefs.getString("port","3333")?:"3333",true);userInput=field("Worker username / wallet.worker",prefs.getString("user","")?:"");passInput=field("Worker password",prefs.getString("pass","x")?:"x")
  tlsInput=CheckBox(this).apply{text="Use TLS";setTextColor(white);isChecked=prefs.getBoolean("tls",false)}
  for((title,v) in listOf("POOL HOST" to hostInput,"POOL PORT" to portInput,"POOL USERNAME" to userInput,"POOL PASSWORD" to passInput)){c.addView(label(title,9f,muted,true));c.addView(v)}
  c.addView(tlsInput);p.addView(c)
  val s=card();status=label("● DISCONNECTED",12f,white,true);s.addView(status);detail=label("Configure pool and worker.",11f,muted,false).padTop(8);s.addView(detail)
  rate=label("0 H/s",25f,white,true).padTop(12);s.addView(rate);hashes=label("Hashes: 0",11f,muted,false);s.addView(hashes)
  val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};accepted=label("Accepted: 0",11f,white,true);rejected=label("Rejected: 0",11f,white,true);row.addView(accepted,LinearLayout.LayoutParams(0,-2,1f));row.addView(rejected,LinearLayout.LayoutParams(0,-2,1f));s.addView(row);p.addView(s)
  val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};startButton=action("START MINING"){startMining()};stopButton=action("STOP"){stopMining()};actions.addView(startButton,LinearLayout.LayoutParams(0,dp(48),1f));actions.addView(stopButton,LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(8)});p.addView(actions)
  return p
 }
 private fun showMode(local:Boolean){benchmarkPanel.visibility=if(local)android.view.View.VISIBLE else android.view.View.GONE;stratumPanel.visibility=if(local)android.view.View.GONE else android.view.View.VISIBLE}
 private fun runSelfTest(){executor.execute{try{val report=NativePowEngine.nativeSelfTest();runOnUiThread{benchSelf.text=report;benchStatus.text=if(report.contains("genesis_portable=OK")&&(report.contains("arm_vs_portable=OK")||report.contains("arm_vs_portable=NOT_AVAILABLE")))"AUTOPRUEBA PORTABLE OK"+if(report.contains("arm_vs_portable=OK")?" · ARM OK":" · ARM NO DISPONIBLE") else "AUTOPRUEBA FALLIDA";benchPath.text="Motor: "+NativePowEngine.nativeCapabilities();benchDetails.text=report}}catch(t:Throwable){runOnUiThread{benchStatus.text="AUTOPRUEBA FALLIDA";benchSelf.text=t.message?:t.javaClass.simpleName}}}}
 private fun runNative(threads:Int){if(benchRunning)return;benchRunning=true;benchStatus.text="Benchmark nativo en ejecución…";executor.execute{try{val useArm=NativePowEngine.nativeCapabilities().contains("arm64_sha2=true");val result=NativePowEngine.nativeBenchmark(header,10000L,threads,useArm,0);val rate=result[0]/10.0;runOnUiThread{benchRunning=false;benchRate.text=formatRate(rate);benchAvg.text="Promedio de 10 s: "+formatRate(rate);benchHashes.text="Hashes totales: "+String.format(Locale.US,"%,d",result[0]);benchThreads.text="Hilos: ${result[1]} · CPUs detectadas: ${result[2]}";benchStatus.text="Benchmark finalizado";benchPath.text=if(useArm)"Motor: ARMv8 SHA2" else "Motor: portable SHA-256"}}catch(t:Throwable){runOnUiThread{benchRunning=false;benchStatus.text="ERROR: ${t.message}"}}}}
 private fun runComparison(){if(benchRunning)return;benchRunning=true;benchStatus.text="Comparación en ejecución: Java → nativo 1 hilo → nativo multihilo";executor.execute{try{val javaCount=javaBenchmark(5000);val useArm=NativePowEngine.nativeCapabilities().contains("arm64_sha2=true");val one=NativePowEngine.nativeBenchmark(header,5000,1,useArm,0);val multi=NativePowEngine.nativeBenchmark(header,5000,0,useArm,0);runOnUiThread{benchRunning=false;benchRate.text="Java "+formatRate(javaCount/5.0);benchAvg.text="Nativo 1 hilo: "+formatRate(one[0]/5.0)+" · Multihilo: "+formatRate(multi[0]/5.0);benchHashes.text="Hashes: Java ${javaCount} · Nativo 1H ${one[0]} · Multi ${multi[0]}";benchThreads.text="Hilos nativo: 1 / ${multi[1]}";benchStatus.text="Comparación completada";benchDetails.text="Mediciones independientes de 5 segundos. Resultados sujetos a temperatura y estado del dispositivo."}}catch(t:Throwable){runOnUiThread{benchRunning=false;benchStatus.text="ERROR: ${t.message}"}}}}
 private fun javaBenchmark(ms:Long):Long{val md=MessageDigest.getInstance("SHA-256");var n=0L;val until=System.nanoTime()+ms*1_000_000;while(System.nanoTime()<until){val a=md.digest(header);md.digest(a);n++};return n}
 private fun startMining(){if(miner!=null)return;val host=hostInput.text.toString().trim();val port=portInput.text.toString().trim().toIntOrNull();val user=userInput.text.toString().trim();val pass=passInput.text.toString();if(host.isBlank()||port==null||port !in 1..65535||user.isBlank()){status.text="● CONFIGURATION ERROR";detail.text="Enter valid pool hostname, port and worker.";return};getSharedPreferences("pool_config",MODE_PRIVATE).edit().putString("host",host).putString("port",port.toString()).putString("user",user).putString("pass",pass).putBoolean("tls",tlsInput.isChecked).apply();val engine=StratumMiner(PoolConfig(host,port,user,pass,tlsInput.isChecked)){stats->runOnUiThread{rate.text=formatRate(stats.hashrate);hashes.text="Hashes: "+String.format(Locale.US,"%,d",stats.hashes);accepted.text="Accepted: "+stats.accepted;rejected.text="Rejected: "+stats.rejected;status.text="● "+stats.state.uppercase(Locale.US);detail.text=stats.message+" · Pool difficulty: "+stats.difficulty;if(stats.state=="Error"||stats.state=="Disconnected"||stats.state=="Stopped"){miner=null;startButton.isEnabled=true;stopButton.isEnabled=false}}};miner=engine;startButton.isEnabled=false;stopButton.isEnabled=true;engine.start()}
 private fun stopMining(){miner?.stop();miner=null;startButton.isEnabled=true;stopButton.isEnabled=false;status.text="● STOPPED"}
 override fun onDestroy(){benchRunning=false;miner?.stop();executor.shutdownNow();super.onDestroy()}
 private fun card()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=rounded(panel,16);setPadding(dp(16),dp(16),dp(16),dp(16));layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10);bottomMargin=dp(10)}}
 private fun field(h:String,v:String,n:Boolean=false)=EditText(this).apply{hint=h;setText(v);setTextColor(white);setHintTextColor(muted);textSize=13f;setSingleLine(true);inputType=if(n)android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT;background=rounded(bg,9);setPadding(dp(10),dp(8),dp(10),dp(8));layoutParams=LinearLayout.LayoutParams(-1,dp(44)).apply{bottomMargin=dp(8)}}
 private fun action(t:String,fn:()->Unit)=Button(this).apply{text=t;isAllCaps=false;textSize=11f;setTextColor(Color.BLACK);typeface=Typeface.DEFAULT_BOLD;background=rounded(orange,11);setOnClickListener{fn()};layoutParams=LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(7)}}
 private fun label(t:String,size:Float,color:Int,bold:Boolean)=TextView(this).apply{text=t;textSize=size;setTextColor(color);typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT}
 private fun rounded(color:Int,r:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(r).toFloat()}
 private fun TextView.padTop(v:Int)=apply{setPadding(paddingLeft,dp(v),paddingRight,paddingBottom)}
 private fun TextView.padBottom(v:Int)=apply{setPadding(paddingLeft,paddingTop,paddingRight,dp(v))}
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 private fun formatRate(v:Double)=when{v>=1_000_000->String.format(Locale.US,"%.2f MH/s",v/1_000_000);v>=1_000->String.format(Locale.US,"%.2f kH/s",v/1_000);else->String.format(Locale.US,"%.2f H/s",v)}
}