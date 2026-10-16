package ru.arc.origin

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

internal data class WaiterBrawlPreviewActor(val id: Int, val x: Double, val z: Double, val yaw: Float, val pitch: Float)
internal data class WaiterBrawlPreviewSample(val millis: Long, val phase: String, val actors: List<WaiterBrawlPreviewActor>)
internal data class WaiterBrawlPreviewEvent(val millis: Long, val action: String, val actor: Int, val x: Double, val z: Double)

/** Records actual production callbacks; walking interpolation is the test fixture's explicit approximation. */
internal object WaiterBrawlPreviewExport {
    fun write(directory: Path, samples: List<WaiterBrawlPreviewSample>, events: List<WaiterBrawlPreviewEvent>) {
        Files.createDirectories(directory)
        val data = GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "source" to "src/main/kotlin/ru/arc/origin/OriginWaiterBrawl.kt",
            "evidence" to "Production start/tick/intervention callbacks. Mock walking at 0.12 block/tick. No Minecraft rendering, collision, skins or native hurt/swing playback.",
            "samples" to samples, "events" to events,
        ))
        Files.writeString(directory.resolve("choreography.json"), data)
        Files.writeString(directory.resolve("index.html"), """
<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Драка гостей — трасса хореографии</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#141a1c;color:#f3e9d8;font:16px/1.5 system-ui,sans-serif}
main{max-width:1040px;margin:auto;padding:28px}h1{font-size:27px;margin:0 0 5px}p{margin:6px 0 18px;color:#d6cfbf}
.badge{font-size:12px;letter-spacing:.11em;text-transform:uppercase;color:#e9af7b}.panel{background:#1d2729;border:1px solid #4c5751;border-radius:14px;padding:20px}
canvas{display:block;width:100%;height:auto}button{color:#fff3df;background:#466861;border:1px solid #789287;border-radius:7px;padding:9px 18px;font:inherit;cursor:pointer}
.controls{display:flex;gap:14px;align-items:center;margin:18px 0}input{flex:1;accent-color:#dcac72}.time{font-variant-numeric:tabular-nums;min-width:100px;text-align:right}
.legend{display:flex;gap:28px;flex-wrap:wrap;font-size:14px}.dot{display:inline-block;width:11px;height:11px;border-radius:50%;margin-right:6px}.a{background:#efba7e}.b{background:#83bfc1}
.phase{font-size:21px;font-weight:650}.detail{min-height:29px;font-size:14px;color:#d6cfbf}.scope{font-size:13px;margin:17px 0 0;color:#bbc4bf}
@media(max-width:650px){main{padding:16px}.panel{padding:12px}h1{font-size:23px}.controls{gap:9px}}
</style><main>
<div class="badge">Трасса production-контроллера · схема сверху · 1×</div>
<h1>Гости сначала дерутся, затем расходятся</h1>
<p>Показаны вызовы движения, замаха и реакции из рабочего Kotlin-контроллера.</p>
<section class="panel"><div id="phase" class="phase"></div><div id="detail" class="detail"></div>
<canvas id="scene" width="960" height="330" aria-label="Два гостя и их движение в проходе"></canvas>
<div class="legend"><span><i class="dot a"></i>Первый гость</span><span><i class="dot b"></i>Второй гость</span><span>Красная обводка — вызов hurt-анимации</span></div>
<div class="controls"><button id="play">Пауза</button><input id="time" aria-label="Время анимации" type="range" min="0" step="50"><span id="clock" class="time"></span></div>
<canvas id="timeline" width="960" height="84" aria-label="Чередование ударов на временной шкале"></canvas></section>
<p class="scope">Это схема вызовов, а не запись клиента Minecraft. Пешее движение смоделировано со скоростью 0,12 блока/тик. Citizens pathfinding, мебель, скины, нативные замахи, звук и hurt-кадры здесь не воспроизводятся. Цвета обозначают актёров, а не их игровые скины.</p>
</main><script>
const data = $data;
const samples=data.samples, events=data.events, end=samples[samples.length-1].millis;
const scene=document.querySelector('#scene'), ctx=scene.getContext('2d'), timeline=document.querySelector('#timeline'), tl=timeline.getContext('2d');
const slider=document.querySelector('#time'), button=document.querySelector('#play');slider.max=end;
const names={BRAWL:'Сходятся и обмениваются ударами',FIRST_SIDE:'Разнять: отвести первого гостя',SECOND_SIDE:'Разнять: отвести второго гостя',WATER:'Набрать воду',BRING_WATER:'Передать воду и дать перевести дух',REASSURE:'Успокоить и вернуть к обслуживанию'};
const details={BRAWL:'Сначала действуют обе стороны. До этого вмешательство ещё не готово.',FIRST_SIDE:'После начала вмешательства новых ударов больше нет.',SECOND_SIDE:'Между гостями остаётся свободный проход.',WATER:'Гости ждут на раздельных точках.',BRING_WATER:'Реакция на воду занимает 2 секунды.',REASSURE:'После завершения DiningModule возвращает исходных NPC на их места.'};
const color=id=>id===1?'#efba7e':'#83bfc1', px=x=>480+x*110, pz=z=>155+z*110;
let time=0,playing=true,last=performance.now();
function draw(t){
 const sample=samples[Math.min(samples.length-1,Math.max(0,Math.floor(t/50)-1))];
 document.querySelector('#phase').textContent=names[sample.phase];document.querySelector('#detail').textContent=details[sample.phase];
 document.querySelector('#clock').textContent=(t/1000).toFixed(1)+' / '+(end/1000).toFixed(1)+' с';slider.value=t;
 ctx.clearRect(0,0,960,330);ctx.fillStyle='#222f2f';ctx.fillRect(80,88,800,134);
 ctx.strokeStyle='#425452';ctx.lineWidth=1;for(let x=-3.5;x<=3.5;x++){ctx.beginPath();ctx.moveTo(px(x),88);ctx.lineTo(px(x),222);ctx.stroke()}
 ctx.setLineDash([5,7]);ctx.strokeStyle='#748176';ctx.beginPath();ctx.moveTo(px(-2.5),155);ctx.lineTo(px(2.5),155);ctx.stroke();ctx.setLineDash([]);
 for(const x of [-1.5,-.5,.5,1.5]){ctx.fillStyle='#8c9782';ctx.beginPath();ctx.arc(px(x),155,4,0,Math.PI*2);ctx.fill()}
 for(const actor of sample.actors){const x=px(actor.x),z=pz(actor.z),recent=events.filter(e=>e.actor===actor.id&&e.millis<=t&&t-e.millis<280);
  ctx.fillStyle=color(actor.id);ctx.strokeStyle=recent.some(e=>e.action==='hurt')?'#ff705c':'#dce8dd';ctx.lineWidth=recent.some(e=>e.action==='hurt')?6:2;
  ctx.beginPath();ctx.arc(x,z,27,0,Math.PI*2);ctx.fill();ctx.stroke();
  const angle=actor.yaw*Math.PI/180,dx=-Math.sin(angle),dz=Math.cos(angle);ctx.strokeStyle='#253431';ctx.lineWidth=4;
  ctx.beginPath();ctx.moveTo(x+dx*18,z+dz*18);ctx.lineTo(x+dx*34,z+dz*34);ctx.stroke();
  if(recent.some(e=>e.action==='swing')){const other=sample.actors.find(a=>a.id!==actor.id),d=Math.hypot(other.x-actor.x,other.z-actor.z);ctx.strokeStyle=color(actor.id);ctx.lineWidth=8;ctx.beginPath();ctx.moveTo(x,z);ctx.lineTo(x+(other.x-actor.x)/d*56,z+(other.z-actor.z)/d*56);ctx.stroke()}
  ctx.font='600 15px system-ui';ctx.textAlign='center';ctx.fillStyle='#f7eddc';ctx.fillText(actor.id===1?'Первый':'Второй',x,z-42);
 }
 ctx.textAlign='left';ctx.font='13px system-ui';ctx.fillStyle='#b7c4bc';ctx.fillText('1 клетка = 1 блок',80,273);ctx.fillText('Занимают проход только два арендованных гостя',80,296);
 tl.clearRect(0,0,960,84);tl.font='13px system-ui';tl.textAlign='left';tl.fillStyle='#d6cfbf';tl.fillText('Первый',0,27);tl.fillText('Второй',0,64);
 for(const id of [1,2]){const y=id===1?22:59;tl.strokeStyle='#55615b';tl.beginPath();tl.moveTo(85,y);tl.lineTo(950,y);tl.stroke();for(const e of events.filter(e=>e.actor===id&&e.action==='swing')){tl.fillStyle=color(id);tl.fillRect(85+865*e.millis/end-4,y-9,8,18)}}
 tl.strokeStyle='#f5ebd7';tl.lineWidth=2;tl.beginPath();tl.moveTo(85+865*t/end,3);tl.lineTo(85+865*t/end,78);tl.stroke();
}
button.onclick=()=>{playing=!playing;button.textContent=playing?'Пауза':'Пуск'};slider.oninput=()=>{time=+slider.value;draw(time)};
function frame(now){if(playing){time+=now-last;if(time>end)time=0}last=now;draw(time);requestAnimationFrame(frame)}requestAnimationFrame(frame);
</script></html>
""".trimIndent())
    }
}
