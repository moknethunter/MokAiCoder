const byId = (id) => document.getElementById('project-' + id);
const unlock = byId('unlock');
const form = byId('run');
const model = byId('model');
const status = byId('status');
const result = byId('result');
const publish = byId('publish');
const compare = byId('compare');
const prefix = '/api/ai/project-assistant';
let token = '';
let job;
let plan;
let mayPublish = false;

const errors = {
  AI_UNAUTHORIZED: 'تحقق من رمز الوصول.',
  AI_SETUP_REQUIRED: 'يلزم إعداد رمز وصول الخادم.',
  PROJECT_SETUP: 'يلزم إعداد Grok ومستودع GitHub في الخادم.',
  PROJECT_BUSY: 'يجري فحص مشروع بالفعل.',
  PROJECT_EMPTY: 'لا توجد ملفات نصية مؤهلة للمراجعة.',
  PROJECT_REVIEW_LIMIT: 'المشروع يتجاوز حدود المراجعة في جلسة واحدة.',
  PROJECT_PLAN_INVALID:
    'تعذّر التحقق من جواب Grok لهذا الطلب. جرّب تحديد المهمة بدقة أكبر.',
  PROJECT_PLAN_EXPIRED: 'انتهت صلاحية المعاينة. أعد توليدها.',
  WORKSPACE_CHANGED: 'تغيّر الفرع منذ الفحص. أعد المعاينة قبل النشر.',
  WORKSPACE_WRITE_DISABLED: 'يلزم إعداد صلاحية كتابة مستودع GitHub.',
  AI_RATE_LIMIT: 'بلغ Grok حد الطلبات. أعد المحاولة لاحقًا.',
  AI_PROVIDER_AUTH: 'تحقق من مفتاح xAI في الخادم.',
  AI_PROVIDER_CREDITS: 'تحقق من رصيد حساب xAI.',
  AI_TIMEOUT: 'انتهى وقت الفحص. ابدأ طلبًا جديدًا.',
};
const message = (code) =>
  errors[code] ?? 'تعذّر إكمال الطلب. تحقق من الخادم والمزوّد.';

function notice(text, error = false) {
  status.hidden = !text;
  status.textContent = text;
  status.dataset.state = error ? 'error' : '';
}

async function request(path, options = {}) {
  const response = await fetch(prefix + path, {
    ...options,
    headers: {
      Authorization: 'Bearer ' + token,
      ...(options.body && { 'Content-Type': 'application/json' }),
    },
  });
  if (!response.ok) {
    const fault = await response.json().catch(() => ({}));
    throw new Error(message(fault.code));
  }
  return response;
}

function reset() {
  plan = undefined;
  result.hidden = true;
  compare.hidden = true;
  compare.removeAttribute('href');
  publish.hidden = true;
}

function progress({ phase, batch, total }) {
  notice(
    {
      snapshot: 'جارٍ جلب نسخة ثابتة من ملفات المستودع…',
      review: 'جارٍ فحص الدفعة ' + batch + ' من ' + total + ' عبر Grok…',
      summary: 'جارٍ جمع نتائج ملفات المشروع…',
      proposal: 'جارٍ إعداد التعديلات المترابطة…',
      verification: 'جارٍ مراجعة التعديلات وفحص التبعيات مجددًا…',
    }[phase] ?? 'جارٍ الفحص…',
  );
}

function details({ path, before, content, create }) {
  const row = document.createElement('details');
  const heading = document.createElement('summary');
  heading.dir = 'ltr';
  heading.textContent = path;
  row.append(heading);
  for (const [label, value] of create
    ? [['الإضافة', content]]
    : [
        ['قبل', before],
        ['بعد', content],
      ]) {
    const title = document.createElement('strong');
    title.textContent = label;
    const code = document.createElement('pre');
    code.dir = 'auto';
    code.textContent = value;
    row.append(title, code);
  }
  return row;
}

function display(data) {
  const info = data.coverage;
  plan = data.id;
  byId('coverage').textContent =
    'راجع ' +
    info.reviewed +
    ' ملفًا نصيًا مؤهلًا على ' +
    info.batches +
    ' دفعات. الإصدار ' +
    data.revision.slice(0, 12) +
    '.';
  byId('report').textContent = data.report;
  byId('plan-summary').textContent = data.summary ?? '';
  byId('changes').replaceChildren(...data.changes.map(details));
  const check = data.verification;
  byId('verification').textContent = check
    ? check.review +
      '\nدورات التبعيات: ' +
      check.cyclesBefore +
      ' ← ' +
      check.cyclesAfter +
      ' · الاستيرادات المحلية غير المطابقة: ' +
      check.missingBefore +
      ' ← ' +
      check.missingAfter
    : '';
  publish.hidden = !plan || !mayPublish;
  result.hidden = false;
  notice('اكتمل الفحص.');
}

async function receive(response) {
  if (!response.body) throw new Error('البث غير متاح في هذا المتصفح.');
  let buffer = '';
  let complete = false;
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      buffer += decoder.decode(next.value, { stream: true });
      for (
        let split = buffer.indexOf('\n\n');
        split >= 0;
        split = buffer.indexOf('\n\n')
      ) {
        const frame = buffer.slice(0, split);
        buffer = buffer.slice(split + 2);
        const [name, value] = frame.split('\n', 2);
        const data = JSON.parse(value.slice(6));
        if (name === 'event: error') throw new Error(message(data.code));
        if (name === 'event: progress') progress(data);
        if (name === 'event: result') {
          display(data);
          complete = true;
        }
      }
    }
  } finally {
    await reader.cancel().catch(() => {});
  }
  if (!complete) throw new Error('انقطع اتصال الفحص قبل اكتماله.');
}

unlock.addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = unlock.querySelector('button');
  button.disabled = true;
  token = byId('token').value.trim();
  try {
    const { project, models } = await (await request('')).json();
    model.replaceChildren(
      ...models.map(({ id, label }) => new Option(label, id)),
    );
    byId('repo').textContent = project.repository + ' · ' + project.branch;
    mayPublish = project.publish;
    byId('token').value = '';
    unlock.hidden = true;
    byId('session').hidden = false;
    notice(models.length ? '' : 'تهيئة Grok لا تعرض أي نموذج.');
  } catch (error) {
    token = '';
    notice(error.message, true);
  } finally {
    button.disabled = false;
  }
});

byId('lock').addEventListener('click', () => {
  job?.abort();
  token = '';
  reset();
  byId('task').value = '';
  byId('session').hidden = true;
  unlock.hidden = false;
  notice('');
});
byId('stop').addEventListener('click', () => job?.abort());

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (job || !model.value) return;
  reset();
  job = new AbortController();
  byId('start').disabled = true;
  byId('stop').hidden = false;
  const body = JSON.stringify({
    model: model.value,
    action: byId('action').value,
    task: byId('task').value,
    shareCloud: byId('share').checked,
    allowPaid: byId('paid').checked,
  });
  byId('share').checked = byId('paid').checked = false;
  try {
    await receive(
      await request('/run', { method: 'POST', body, signal: job.signal }),
    );
  } catch (error) {
    if (token)
      notice(
        job.signal.aborted ? 'أُوقف الفحص.' : error.message,
        !job.signal.aborted,
      );
  } finally {
    job = undefined;
    byId('start').disabled = false;
    byId('stop').hidden = true;
  }
});

publish.addEventListener('click', async () => {
  if (!plan || publish.disabled) return;
  const id = plan;
  publish.disabled = true;
  try {
    const data = await (
      await request('/publish', {
        method: 'POST',
        body: JSON.stringify({ id }),
      })
    ).json();
    if (plan !== id) return;
    compare.href = data.compareUrl;
    compare.hidden = false;
    publish.hidden = true;
    notice('أُنشئ فرع التعديلات على GitHub.');
  } catch (error) {
    if (plan === id) notice(error.message, true);
  } finally {
    publish.disabled = false;
  }
});
