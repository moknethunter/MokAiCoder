const get = (id) => document.getElementById(id);
const unlock = get('unlock');
const tokenField = get('chat-token');
const room = get('chat-room');
const status = get('chat-status');
const form = get('chat-form');
const providerField = get('chat-provider');
const modelField = get('chat-model');
const fileField = get('chat-file');
const messageField = get('chat-message');
const transcript = get('chat-messages');
const send = get('chat-send');
const stop = get('chat-stop');
const share = get('chat-share');
const paid = get('chat-paid');
const reset = get('chat-reset');
const lock = get('chat-lock');
const loadFiles = get('chat-load-files');

let accessToken = '';
let providers = [];
let turns = [];
let scanId = '';
let request;
let catalogRequest;

const tiers = { free: 'سحابي مجاني', local: 'محلي', paid: 'سحابي مدفوع' };
const messages = {
  AI_SETUP_REQUIRED:
    'اضبط رمز المحادثة ومزوّدًا واحدًا على الأقل في إعدادات الخادم.',
  AI_UNAUTHORIZED: 'تحقّق من رمز وصول MokAiCoder.',
  AI_RATE_LIMIT: 'بلغت حدّ الاستخدام. حاول لاحقًا.',
  AI_BUSY: 'النظام يجهّز ردودًا أخرى الآن. حاول بعد قليل.',
  AI_TIMEOUT: 'انتهت مهلة انتظار النموذج.',
  AI_PROVIDER_AUTH: 'رفض المزوّد بيانات الاعتماد المهيأة على الخادم.',
  AI_PROVIDER_ERROR: 'تعذّر الاتصال بالمزوّد أو إكمال الرد.',
  AI_INVALID_STREAM: 'أعاد المزوّد استجابة غير مدعومة.',
  AI_INCOMPLETE: 'انقطع الرد قبل الاكتمال.',
  AI_RESPONSE_LIMIT: 'تجاوز الرد الحجم المسموح.',
  AI_MODEL_UNAVAILABLE: 'هذا النموذج غير متاح لدى المزوّد.',
  AI_PAID_CONSENT: 'أكّد موافقتك على تكلفة الطلب.',
  AI_FILE_CONSENT: 'أكّد مشاركة الملف مع المزوّد السحابي.',
  FILE_NOT_FOUND: 'هذا الملف غير متاح للمحادثة.',
  SCAN_REQUIRED: 'شغّل فحص المشروع أولًا.',
  SCAN_CHANGED: 'تغيّرت نتيجة الفحص. أعد تحميل قائمة الملفات.',
};

function feedback(text, error = false) {
  status.textContent = text;
  status.dataset.state = error ? 'error' : '';
  status.hidden = !text;
}

function errorText(code, retryAfter) {
  const text = Object.hasOwn(messages, code)
    ? messages[code]
    : 'تعذّر إكمال الطلب.';
  return retryAfter && /^\d+$/.test(retryAfter)
    ? `${text} الانتظار المقترح ${retryAfter} ثانية.`
    : text;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { ...options.headers, Authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(errorText(error.code, response.headers.get('Retry-After')));
  }
  return response;
}

function activeProvider() {
  return providers.find((provider) => provider.id === providerField.value);
}

function syncControls() {
  const tier = activeProvider()?.tier;
  get('chat-share-label').hidden = !fileField.value || tier === 'local';
  get('chat-paid-label').hidden = tier !== 'paid';
  share.required = !get('chat-share-label').hidden;
  paid.required = !get('chat-paid-label').hidden;
  send.disabled = !!request || !modelField.value;
}

function clearConversation() {
  turns = [];
  transcript.replaceChildren();
}

async function loadModels() {
  catalogRequest?.abort();
  const loading = new AbortController();
  catalogRequest = loading;
  const provider = activeProvider();
  clearConversation();
  modelField.replaceChildren();
  share.checked = paid.checked = false;
  syncControls();
  if (!provider) return;
  feedback('جارٍ جلب النماذج…');
  try {
    const response = await api(`/api/ai/providers/${provider.id}/models`, {
      signal: loading.signal,
    });
    const models = await response.json();
    if (loading.signal.aborted) return;
    modelField.replaceChildren(
      ...models.map((model) => new Option(model.label, model.id)),
    );
    feedback(
      models.length
        ? `المزوّد جاهز · ${tiers[provider.tier]}`
        : 'لم يعرض المزوّد أي نماذج متاحة.',
    );
  } catch (error) {
    if (!loading.signal.aborted) feedback(error.message, true);
  } finally {
    if (catalogRequest === loading) {
      catalogRequest = undefined;
      syncControls();
    }
  }
}

unlock.addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = unlock.querySelector('button');
  button.disabled = true;
  accessToken = tokenField.value.trim();
  try {
    const response = await api('/api/ai/providers');
    providers = await response.json();
    tokenField.value = '';
    providerField.replaceChildren(
      ...providers.map(
        (provider) =>
          new Option(
            `${provider.label} · ${tiers[provider.tier]}`,
            provider.id,
          ),
      ),
    );
    unlock.hidden = true;
    room.hidden = false;
    await loadModels();
  } catch (error) {
    accessToken = '';
    feedback(error.message, true);
  } finally {
    button.disabled = false;
  }
});

providerField.addEventListener('change', loadModels);
modelField.addEventListener('change', () => {
  clearConversation();
  paid.checked = false;
  syncControls();
});
reset.addEventListener('click', clearConversation);
lock.addEventListener('click', () => {
  catalogRequest?.abort();
  accessToken = '';
  clearConversation();
  messageField.value = '';
  providers = [];
  scanId = '';
  fileField.replaceChildren(new Option('دون ملف', ''));
  room.hidden = true;
  unlock.hidden = false;
  feedback('');
  tokenField.focus();
});

loadFiles.addEventListener('click', async () => {
  loadFiles.disabled = true;
  const currentToken = accessToken;
  try {
    const response = await api('/api/project/tree');
    const files = await response.json();
    if (currentToken !== accessToken || room.hidden) return;
    scanId = response.headers.get('X-Scan-Id') || '';
    fileField.replaceChildren(
      new Option('دون ملف', ''),
      ...files.map((file) => new Option(file.path, file.path)),
    );
    share.checked = false;
    syncControls();
    feedback('حمّلت قائمة ملفات آخر فحص.');
  } catch (error) {
    if (currentToken === accessToken) feedback(error.message, true);
  } finally {
    loadFiles.disabled = false;
  }
});

fileField.addEventListener('change', () => {
  share.checked = false;
  syncControls();
});

function addTurn(role, text) {
  const item = document.createElement('li');
  item.dataset.role = role;
  const label = document.createElement('strong');
  label.textContent = role === 'user' ? 'أنت' : 'المساعد';
  const content = document.createElement('p');
  content.dir = 'auto';
  content.textContent = text;
  item.append(label, content);
  transcript.append(item);
  if (transcript.children.length > 80) transcript.firstElementChild.remove();
  transcript.scrollTop = transcript.scrollHeight;
  return content;
}

async function readReply(response, output) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finished = false;
  try {
    while (!finished) {
      const chunk = await reader.read();
      if (chunk.done) break;
      buffer += decoder.decode(chunk.value, { stream: true });
      let end;
      while ((end = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        const [event, line] = frame.split('\n', 2);
        const data = JSON.parse(line.slice('data: '.length));
        if (event === 'event: delta') {
          const follow =
            transcript.scrollHeight -
              transcript.scrollTop -
              transcript.clientHeight <
            40;
          output.append(data.text);
          if (follow) transcript.scrollTop = transcript.scrollHeight;
        } else if (event === 'event: error') {
          throw new Error(errorText(data.code, data.retryAfter));
        } else if (event === 'event: done') {
          finished = true;
          break;
        }
      }
    }
    if (!finished) throw new Error(messages.AI_INCOMPLETE);
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const content = messageField.value.trim();
  if (!content || request || !modelField.value) return;
  request = new AbortController();
  syncControls();
  stop.hidden = false;
  for (const field of [
    providerField,
    modelField,
    reset,
    lock,
    fileField,
    loadFiles,
  ])
    field.disabled = true;

  const next = { role: 'user', content };
  const history = [...turns.slice(-12), next];
  while (
    history.reduce((size, turn) => size + turn.content.length, 0) > 24_000 &&
    history.length > 1
  ) {
    history.splice(0, 2);
  }
  const body = {
    provider: providerField.value,
    model: modelField.value,
    messages: history,
    ...(fileField.value && {
      attachment: { path: fileField.value, scanId, shareCloud: share.checked },
    }),
    ...(paid.checked && { allowPaid: true }),
  };
  share.checked = paid.checked = false;
  messageField.value = '';
  const sent = addTurn('user', content);
  const output = addTurn('assistant', '');
  feedback('جارٍ إعداد الرد…');

  try {
    const response = await api('/api/ai/chat', {
      method: 'POST',
      signal: request.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    await readReply(response, output);
    turns = [...history, { role: 'assistant', content: output.textContent }];
    feedback('اكتمل الرد.');
  } catch (error) {
    if (!output.textContent) {
      sent.parentElement.remove();
      output.parentElement.remove();
      if (!messageField.value) messageField.value = content;
    }
    feedback(
      request.signal.aborted ? 'أوقفت الرد.' : error.message,
      !request.signal.aborted,
    );
  } finally {
    request = undefined;
    stop.hidden = true;
    for (const field of [
      providerField,
      modelField,
      reset,
      lock,
      fileField,
      loadFiles,
    ])
      field.disabled = false;
    syncControls();
  }
});

stop.addEventListener('click', () => request?.abort());
