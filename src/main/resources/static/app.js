'use strict';
let selectedId = null;
let selectedEvent = null;
let events = [];
let refreshing = false;
const $ = id => document.getElementById(id);
const escapeHtml = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function api(path, method='GET') {
  const response = await fetch(path, {method, headers:{Accept:'application/json'}});
  if (!response.ok) throw new Error(`Request failed (${response.status}). Check that the local application is running.`);
  return response.json();
}
function renderEvents() {
  $('eventRows').innerHTML = events.length ? events.map(e => `<tr data-id="${escapeHtml(e.ID)}" class="${e.ID===selectedId?'selected':''}" tabindex="0" aria-label="Inspect ${escapeHtml(e.EVENT_KEY)}"><td><b>${escapeHtml(e.EVENT_KEY)}</b><small>${escapeHtml(e.TENANT)}</small></td><td>${escapeHtml(e.CURRENCY)} ${(e.AMOUNT_MINOR/100).toFixed(2)}<small>SIMULATED</small></td><td><span class="status ${escapeHtml(e.STATUS)}">${escapeHtml(e.STATUS.replaceAll('_',' '))}</span></td><td>${e.ATTEMPTS}</td></tr>`).join('') : '<tr><td colspan="4" class="empty">Your first event starts here.</td></tr>';
  $('eventRows').querySelectorAll('[data-id]').forEach(row => {
    const select = async () => { selectedId=row.dataset.id; renderEvents(); try { await renderTimeline(); } catch(e) { $('notice').textContent=e.message; } };
    row.addEventListener('click',select);
    row.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault(); select();}});
  });
}
async function renderTimeline() {
  if (!selectedId) return;
  const currentId=selectedId;
  const attempts=await api(`/api/events/${encodeURIComponent(currentId)}/attempts`);
  if(currentId!==selectedId) return;
  selectedEvent=events.find(e=>e.ID===currentId);
  $('selectedKey').textContent=selectedEvent?.EVENT_KEY || 'Event';
  $('timeline').innerHTML=attempts.length?attempts.map(a=>`<li><b>#${a.ATTEMPT_NUMBER} · ${escapeHtml(a.OUTCOME.replaceAll('_',' '))}</b><p>${escapeHtml(a.DETAIL)}</p><time>${escapeHtml(new Date(a.CREATED_AT).toLocaleTimeString())}</time></li>`).join(''):'<li class="empty">Accepted. Waiting for the worker.</li>';
  $('replay').hidden=selectedEvent?.STATUS!=='DEAD_LETTER';
}
async function refresh() {
  if(refreshing) return;
  refreshing=true;
  try {
    const [metrics, rows]=await Promise.all([api('/api/metrics'),api('/api/events')]);
    for(const name of ['received','delivered','retrying','dead']) $(name).textContent=metrics[name];
    events=rows; renderEvents(); await renderTimeline();
    $('connection').textContent=`● LIVE · ${metrics.ledgerEntries} LEDGER ENTRIES`;
  } catch(e) { $('connection').textContent='○ RECONNECTING'; $('notice').textContent=e.message; }
  finally { refreshing=false; }
}
document.querySelectorAll('[data-scenario]').forEach(button=>button.addEventListener('click',async()=>{
  button.disabled=true;
  try { const result=await api(`/api/demo/${button.dataset.scenario}`,'POST'); selectedId=result.ids[0]; $('notice').textContent=result.message; await refresh(); }
  catch(e) { $('notice').textContent=e.message; }
  finally { button.disabled=false; }
}));
$('replay').addEventListener('click',async()=>{
  $('replay').disabled=true;
  try { await api(`/api/events/${encodeURIComponent(selectedId)}/replay`,'POST'); $('notice').textContent='Simulated outage cleared. Event requeued; the old timeline is retained.'; await refresh(); }
  catch(e) { $('notice').textContent=e.message; }
  finally { $('replay').disabled=false; }
});
refresh(); setInterval(()=>{if(!document.hidden) refresh();},2000);
