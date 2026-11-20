const API = '/api';
const chat = document.getElementById('chat');
const form = document.getElementById('form');
const input = document.getElementById('input');
const sendBtn = document.getElementById('sendBtn');
const scanBtn = document.getElementById('scanBtn');
const statusEl = document.getElementById('status');

function addMsg(text, cls) {
  const div = document.createElement('div');
  div.className = 'msg ' + cls;
  div.textContent = text;
  chat.appendChild(div);
  chat.scrollTop = chat.scrollHeight;
}

function setStatus(s) {
  statusEl.textContent = s;
}

async function scanProject() {
  scanBtn.disabled = true;
  setStatus('يفحص...');
  addMsg('⏳ جاري فحص المشروع...', 'bot');
  try {
    const res = await fetch(`${API}/project/scan`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ branch: 'main' }),
    });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    addMsg(
      `✅ تم الفحص:\n` +
        `📄 ملفات: ${data.fileCount}\n` +
        `🔗 روابط: ${data.edgeCount}\n` +
        `🔄 دورات: ${data.cycleCount}\n` +
        `🏝️ ملفات يتيمة: ${data.orphanCount}\n` +
        `⚠️ رموز مكررة: ${data.duplicateCount}`,
      'bot'
    );
    setStatus('تم الفحص');
  } catch (e) {
    addMsg('❌ فشل الفحص: ' + e.message, 'err');
    setStatus('فشل');
  } finally {
    scanBtn.disabled = false;
  }
}

scanBtn.addEventListener('click', scanProject);

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  const text = input.value.trim();
  if (!text) return;
  input.value = '';
  sendBtn.disabled = true;
  addMsg(text, 'user');
  addMsg('🤖 Llama 3 يفكر...', 'bot');
  try {
    // Placeholder: API المحادثة لم يُبنَ بعد، لذا نرسل scan فقط
    // عند إضافة /api/project/chat سنستبدل هذا
    const res = await fetch(`${API}/project/tree`);
    if (!res.ok) throw new Error('يجب فحص المشروع أولاً');
    addMsg('💡 المحادثة الذكية قيد التطوير. استخدم زر "فحص المشروع" حالياً.', 'bot');
  } catch (e) {
    addMsg('❌ ' + e.message, 'err');
  } finally {
    sendBtn.disabled = false;
  }
});
